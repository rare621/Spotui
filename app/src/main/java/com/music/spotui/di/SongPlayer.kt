package com.music.spotui.di

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.utils.YouTubeUrlParser
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.YTPlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Plays audio resolved from YouTube. The `song` argument is a "title artist"
 * search query (set by the Spotify-backed data layer): it's matched to a
 * YouTube video, whose stream URL is resolved via the ported [YTPlayerUtils]
 * flow (cipher / PoToken / sabr) and handed to ExoPlayer.
 */
object SongPlayer {
    private const val TAG = "SongPlayer"
    private const val SPOTIFY_TRACK_PREFIX = "spotify:track:"
    private var player: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // A stream may resolve successfully but never buffer after a network/Bluetooth resume.
    private var bufferingWatch: kotlinx.coroutines.Job? = null
    private var bufferingRecoveryFor: String? = null
    private val bufferingGate = com.music.spotui.audio.BufferingWatchPolicy()
    private const val STARTUP_TIMEOUT_MS = 60_000L
    private const val BUFFER_TIMEOUT_MS = 18_000L

    // Cache of resolved stream URLs keyed by the "title artist" query, so replays
    // and prefetched neighbours start instantly instead of re-hitting the network.
    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Which engine each cached stream came from ("YouTube", "Lossless • …") so a
    // cache hit can restore the correct source badge.
    private val sourceCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val streamCachedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ── Lossless (SpotiFLAC) ──
    // When enabled, playback first tries to resolve a lossless FLAC stream (Tidal/
    // Amazon via SpotiFLAC's free community proxies) for the current track and only
    // falls back to YouTube if no FLAC is available or the proxies are throttled.
    // Trades a little first-tap latency for true lossless audio.
    @Volatile var losslessStreaming = true
    @Volatile var losslessHiRes = true

    // Source kill-switches. The Spotify web player is currently broken (off).
    // YouTube is the last-resort fallback, kept on so tracks SpotiFLAC misses or
    // can't serve during a proxy cooldown still play — with the wrong-song guards
    // (videoId match check + artist/title scoring + candidate fallback).
    @Volatile var webPlayerEnabled = false
    @Volatile var youtubeEnabled = true

    // ── Deezer ──
    // When enabled and a Deezer account is logged in (ARL stored), playback tries
    // Deezer FIRST (ISRC → Deezer track → encrypted CDN stream, decrypted on the
    // fly by DeezerDataSource). Quality follows the account tier: free → MP3 128,
    // Premium → MP3 320 / FLAC. Falls back to SpotiFLAC/YouTube on any miss.
    @Volatile var deezerEnabled = true

    // Soulseek P2P as the last lossless fallback (see SoulseekSource).
    @Volatile var soulseekEnabled = true

    // Which engine is feeding the CURRENT track, for the on-screen source badge.
    // "Lossless" (SpotiFLAC: Tidal/Qobuz/Amazon) is NOT Spotify — surfaced so the
    // user knows real Spotify vs a lossless mirror vs the YouTube fallback.
    @Volatile var currentSource: String = "YouTube"
        private set
    // Human-readable quality of the CURRENT stream (e.g. "FLAC 16-bit",
    // "OPUS 141 kbps"), shown next to the source badge.
    @Volatile var currentQuality: String = ""
        private set
    private val qualityCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Maps a "title artist" play query -> the track's real Spotify id, so the
    // lossless resolver can be seeded from a play site that only has the query.
    // Populated centrally whenever the queue changes (see CurrentSongState).
    private val trackIdRegistry = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val alternativeKeyRegistry = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Register query→spotifyTrackId pairs so lossless can be resolved by query. */
    fun registerLossless(pairs: List<Pair<String, String>>) {
        pairs.forEach { (query, spotifyId) ->
            if (query.isNotBlank() && spotifyId.isNotBlank()) trackIdRegistry[query] = spotifyId
        }
    }

    fun registerAlternativeKeys(pairs: List<Pair<String, String>>) {
        pairs.forEach { (query, key) ->
            if (query.isNotBlank() && key.isNotBlank()) alternativeKeyRegistry[query] = key
        }
    }

    // Whether each play query is the explicit version on Spotify, so the YouTube
    // fallback can pick the matching (explicit vs clean) edit.
    private val explicitRegistry = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Register query→explicit pairs (populated whenever the queue changes). */
    fun registerExplicit(pairs: List<Pair<String, Boolean>>) {
        pairs.forEach { (query, explicit) ->
            if (query.isNotBlank()) explicitRegistry[query] = explicit
        }
    }

    // Expected track length (ms) per query, from Spotify — lets the YouTube match
    // reject a same-title song by a different artist (different duration).
    private val durationRegistry = java.util.concurrent.ConcurrentHashMap<String, Int>()

    internal fun diagnosticSong(query: String): String {
        val meta = metadataRegistry[query]
        if (meta != null) return com.music.spotui.audio.DiagnosticSongLabel.create(meta.title, meta.artist)
        val track = boundState?.queue?.value?.firstOrNull { it.url == query }
        if (track != null) return com.music.spotui.audio.DiagnosticSongLabel.create(track.title, track.singer)
        return if (query == currentRequest) com.music.spotui.audio.DiagnosticSongLabel.create(metaTitle, metaArtist) else ""
    }

    /** Register query→durationMs pairs (populated whenever the queue changes). */
    fun registerDuration(pairs: List<Pair<String, Int>>) {
        pairs.forEach { (query, ms) ->
            if (query.isNotBlank() && ms > 0) durationRegistry[query] = ms
        }
    }

    data class TrackMatchMetadata(
        val title: String,
        val artist: String,
        val album: String,
        val isrc: String = "",
    )

    private val metadataRegistry =
        java.util.concurrent.ConcurrentHashMap<String, TrackMatchMetadata>()
    // GQL track objects do not consistently carry the explicit flag. Keep this
    // separate from explicitRegistry so the model's default `false` is not
    // mistaken for a confirmed clean track.
    private val spotifyMetadataRepaired = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Register query→Spotify metadata pairs for strict YouTube result scoring. */
    fun registerMetadata(pairs: List<Pair<String, TrackMatchMetadata>>) {
        pairs.forEach { (query, meta) ->
            if (query.isNotBlank() && meta.title.isNotBlank()) metadataRegistry[query] = meta
        }
    }
    // Tracks which query is the latest play request so a slow resolve for an old
    // tap doesn't clobber a newer one (fast switching).
    @Volatile private var currentRequest: String = ""
    // Next queue track already appended to the player (its play query), so ExoPlayer
    // moves on by itself without passing through ENDED/IDLE (#67: Android Auto left
    // Now Playing on that gap). Null when nothing is queued ahead.
    @Volatile private var preloadedNext: String? = null
    private var preparationJob: kotlinx.coroutines.Job? = null
    private var preparationKey: String? = null
    private var warmedNext: String? = null
    private val preparationBackoff = com.music.spotui.audio.PreparationBackoff()
    @Volatile var onQueueTailApproaching: (() -> Unit)? = null
    private var tailNotified: String? = null

    /** Fired (main thread) when the player moved on to the preloaded track [query]. */
    @Volatile var onPreloadedTrackStarted: ((query: String) -> Unit)? = null

    // Latest track metadata (title / artist / cover URL) so the MediaItem we build
    // carries it into the system media notification. Set via [setNowPlayingMeta]
    // (driven by CurrentSongState) just before / as playback starts.
    @Volatile private var metaTitle: String = ""
    @Volatile private var metaArtist: String = ""
    @Volatile private var metaCover: String = ""

    fun setNowPlayingMeta(title: String, artist: String, coverUri: String) {
        metaTitle = title
        metaArtist = artist
        metaCover = coverUri
    }

    /**
     * Build a stable playback identity for Spotify tracks. The full value is used
     * as cache/registry key, while only the text after "|" is sent to YouTube
     * search. This prevents same-title/same-artist tracks from reusing each
     * other's resolved stream.
     */
    fun buildSpotifyPlayQuery(spotifyTrackId: String, title: String, artist: String): String {
        val searchText = listOf(cleanSpotifySearchTitle(title), artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return if (spotifyTrackId.isBlank()) searchText else "$SPOTIFY_TRACK_PREFIX$spotifyTrackId|$searchText"
    }

    private val featSearchPattern = Regex("""\s*[\(\[]\s*(feat|ft)\..*?[\)\]]""", RegexOption.IGNORE_CASE)

    /** What the queue registered for [song], so lossless matching needs no Spotify lookup. */
    private fun losslessTrackInfo(song: String): com.music.spotui.lossless.LosslessSource.TrackInfo {
        val meta = metadataRegistry[song] ?: return com.music.spotui.lossless.LosslessSource.TrackInfo()
        return com.music.spotui.lossless.LosslessSource.TrackInfo(
            title = meta.title,
            artist = meta.artist.substringBefore(",").trim(),
            durationSec = (durationRegistry[song] ?: 0) / 1000,
            isrc = meta.isrc,
        )
    }

    private fun cleanSpotifySearchTitle(title: String): String =
        com.music.spotui.audio.MusicTitlePolicy.clean(title)

    private fun searchTextForPlayback(song: String): String =
        if (song.startsWith(SPOTIFY_TRACK_PREFIX) && song.contains('|')) {
            song.substringAfter('|').ifBlank { song }
        } else {
            song
        }

    private fun spotifyTrackIdForPlayback(song: String): String? =
        if (song.startsWith(SPOTIFY_TRACK_PREFIX)) {
            song.removePrefix(SPOTIFY_TRACK_PREFIX).substringBefore('|').takeIf { it.isNotBlank() }
        } else {
            null
        }

    fun videoIdFromYouTubeLink(text: String): String? =
        YouTubeUrlParser.extractVideoId(text)
            ?: text.trim().takeIf { it.matches(Regex("""[A-Za-z0-9_-]{11}""")) }

    fun invalidateResolvedStream(song: String) {
        streamCache.remove(song)
        sourceCache.remove(song)
        qualityCache.remove(song)
        streamCachedAt.remove(song)
    }

    /** A skip to the exact prepared next item keeps its buffered audio instead of resolving again. */
    private fun tryPlayPrepared(song: String): Boolean {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper() || isCrossfading || webPlaybackActive()) return false
        val p = player ?: return false
        val ctx = appCtx ?: return false
        val cachedAt = streamCachedAt[song]
        if (cachedAt != null && (android.os.SystemClock.elapsedRealtime() - cachedAt !in 0L..(15 * 60_000L))) return false
        val selectedSource = com.music.spotui.data.preferences.getPrimaryMusicSource(ctx)
        val cachedSource = sourceCache[song]
        if (cachedSource == "Deezer" && selectedSource != com.music.spotui.data.preferences.MusicSource.DEEZER) return false
        if (cachedSource == "YouTube" && selectedSource == com.music.spotui.data.preferences.MusicSource.DEEZER) return false
        if (preloadedNext != song || p.currentMediaItemIndex < 0 || p.currentMediaItemIndex + 1 >= p.mediaItemCount) return false
        if (p.getMediaItemAt(p.currentMediaItemIndex + 1).mediaId != song) return false
        return runCatching {
            preparationJob?.cancel(); preparationJob = null; preparationKey = null; warmedNext = null
            tailNotified = null
            // onMediaItemTransition updates currentRequest, playbackVisit, queue UI and source badge.
            p.seekToNextMediaItem()
            p.play()
            true
        }.getOrDefault(false)
    }

    private var playbackResolveJob: kotlinx.coroutines.Job? = null

    fun playSong(song: String, context: Context) {
        recoveryAllowed = true
        onPlaybackRequestChanged?.invoke()
        playbackResolveJob?.cancel(); playbackResolveJob = null
        val appContext = context.applicationContext
        appCtx = appContext
        if (tryPlayPrepared(song)) return
        currentRequest = song
        com.music.spotui.audio.PlaybackPresentation.set(com.music.spotui.audio.PlaybackPresentation.Stage.CONNECTING)
        com.music.spotui.data.preferences.PlaybackDiagnostics.record(appContext,"Lookup started",song=diagnosticSong(song))
        playbackVisit++
        trackEndGate.reset()
        bufferingRecoveryFor = null
        bufferingWatch?.cancel()
        preloadedNext = null
        preparationJob?.cancel()
        preparationJob = null
        preparationKey = null
        warmedNext = null
        tailNotified = null
        // A manual play (tap / next / prev) supersedes any in-flight crossfade.
        cancelCrossfade()
        // Do not keep the previous track audible while this request resolves.
        // Otherwise the UI can show the newly tapped track while old audio keeps
        // playing for several seconds, or forever if resolution fails.
        runCatching {
            player?.pause()
            player?.clearMediaItems()
        }

        // Podcast episodes are encoded as "episode:<id>" queries — play them via the
        // Spotify web player's episode page (same engine as tracks).
        if (song.startsWith("episode:") && webPlayerEnabled && SpotifyWebPlayer.canPlay &&
            com.music.spotui.data.preferences.isWebPlaybackEnabled(appContext)
        ) {
            runCatching { player?.pause() }
            currentSource = "Spotify"
            currentQuality = ""
            SpotifyWebPlayer.playEpisode(song.removePrefix("episode:"))
            return
        }

        // Downloaded tracks ALWAYS play the local file — even with Spotify web
        // playback on. (Web is now the default and used to run first, so a
        // downloaded track streamed from Spotify instead of playing offline.)
        val downloadedPath = com.music.spotui.data.preferences.downloadedPathForQuery(appContext, song)
        if (downloadedPath == null && webPlayerEnabled &&
            // Experimental: stream through Spotify's own web player (real Spotify audio,
            // no bypass) when enabled AND the device WebView has Widevine. Otherwise
            // fall through to the normal YouTube/FLAC engine so playback is never silent.
            com.music.spotui.data.preferences.isWebPlaybackEnabled(appContext) &&
            SpotifyWebPlayer.canPlay
        ) {
            val spotifyId = trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song)
            if (spotifyId != null) {
                runCatching { player?.pause() }
                currentSource = "Spotify"
                currentQuality = ""
                SpotifyWebPlayer.play(spotifyId)
                return
            }
            Log.w(TAG, "web playback on but no Spotify id for query: $song — using fallback engine")
        }
        playbackResolveJob = scope.launch(com.music.spotui.audio.PlaybackDiagnosticSong.context(diagnosticSong(song))) {
            try {
                if (wantsQuickStart(song, appContext)) {
                    val started = kotlinx.coroutines.withTimeoutOrNull(STARTUP_TIMEOUT_MS) {
                        quickStartThenUpgrade(song, appContext)
                        true
                    }
                    if (started == null && currentRequest == song && !isPlaying()) {
                        // A slow lossless upgrade must not hold the initial play request indefinitely.
                        invalidateResolvedStream(song)
                        val fallback = resolveYtPlayback(
                            song, com.music.spotui.data.preferences.currentStreamingQuality(appContext).audioQuality, appContext
                        )
                        if (fallback != null && currentRequest == song) startStream(song, fallback.streamUrl, appContext)
                        else streamFailed(song, appContext)
                    }
                    return@launch
                }
                val streamUrl = kotlinx.coroutines.withTimeoutOrNull(STARTUP_TIMEOUT_MS) {
                    resolveStreamUrl(song, appContext, forPlayback = true)
                } ?: run {
                    streamFailed(song, appContext)
                    return@launch
                }
                // A newer tap superseded this one while we were resolving — drop it.
                if (currentRequest != song) return@launch
                startStream(song, streamUrl, appContext)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "playSong failed for query: $song", e)
                streamFailed(song, appContext)
            }
        }
    }

    private suspend fun startStream(song: String, streamUrl: String, appContext: Context, mimeType: String? = streamMimeType(streamUrl)) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        withContext(Dispatchers.Main) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (currentRequest != song) return@withContext
            ensurePlayer(appContext)
            com.music.spotui.data.preferences.PlaybackDiagnostics.record(appContext,"Stream prepared",currentSource.replace("•"," "),song=diagnosticSong(song))
            player!!.setMediaItem(buildMediaItem(streamUrl, mimeType))
            player!!.prepare()
            // Restored session: continue from where the last run stopped.
            if (song == restoreQuery && restorePositionMs > 0) {
                val knownDuration = boundState?.queue?.value?.firstOrNull { it.url == song }?.durationMs?.toLong() ?: 0L
                player!!.seekTo(com.music.spotui.audio.PlaybackClockPolicy.position(restorePositionMs, knownDuration))
            }
            restoreQuery = null
            player!!.playWhenReady = true
        }
        startPositionWatch()
        // Quick-start songs trigger it after their own upgrade instead.
        if (!wantsQuickStart(song, appContext)) soulseekLookahead(appContext)
    }

    private fun streamFailed(song: String, appContext: Context) {
        if (currentRequest != song) return
        com.music.spotui.data.preferences.PlaybackDiagnostics.record(appContext,"Lookup failed","No playable source or deadline reached",song=diagnosticSong(song))
        com.music.spotui.audio.PlaybackPresentation.set(com.music.spotui.audio.PlaybackPresentation.Stage.RETRYING)
        val visit = playbackVisit
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (currentRequest == song && visit == playbackVisit) onStreamFailed?.invoke(song)
        }
    }

    fun isCurrentRequest(query: String): Boolean = currentRequest == query
    val playbackRequestId: Long get() = playbackVisit
    @Volatile var onPlaybackRecovered: (() -> Unit)? = null

    // Lossless lookups take ~20 s against ~4 s for YouTube, so a fresh lossless track
    // starts on YouTube and switches over when the FLAC is ready.
    private fun wantsQuickStart(song: String, ctx: Context): Boolean =
        losslessStreaming && youtubeEnabled &&
            com.music.spotui.data.preferences.currentStreamingQuality(ctx).lossless &&
            !song.startsWith("content://") && !song.startsWith("file://") &&
            !streamCache.containsKey(song) &&
            com.music.spotui.data.preferences.downloadedPathForQuery(ctx, song) == null &&
            alternativeStreamForPlayback(song, ctx) == null &&
            (trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song)) != null

    private fun soulseekOn(ctx: Context): Boolean =
        soulseekEnabled && com.music.spotui.data.preferences.isSoulseekEnabled(ctx)

    /** Snapshot the current and prepared local tracks without touching the player from an IO thread. */
    internal fun protectedLosslessCacheKeys(): Set<String> {
        val current = currentRequest
        val queue = boundState?.queue?.value.orEmpty()
        val index = queue.indexOfFirst { it.url == current }
        val next = if (index >= 0) queue.getOrNull(index + 1)?.url else null
        return listOfNotNull(current.takeIf { it.isNotBlank() }, preloadedNext, pendingNextSong?.url, next)
            .map(::soulseekKey).toSet()
    }

    private fun soulseekKey(song: String): String =
        trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song) ?: song.hashCode().toUInt().toString()

    // One look-ahead at a time; the client moves one file at a time anyway.
    private val lookaheadLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Fetch the next queued track's FLAC from Soulseek while this one plays, so it
     * starts lossless instead of upgrading a minute in. Only the next track: a
     * skipped song's file is wasted data.
     */
    private fun soulseekLookahead(ctx: Context) {
        if (!soulseekOn(ctx) || !losslessStreaming || !youtubeEnabled) return
        if (!com.music.spotui.data.preferences.currentStreamingQuality(ctx).lossless) return
        val state = boundState ?: return
        if (lookaheadLock.isLocked) return
        scope.launch {
            lookaheadLock.withLock {
                val next = withContext(Dispatchers.Main) {
                    val q = state.queue.value
                    val cur = q.indexOfFirst { it.id == state.songId.value }
                    q.getOrNull(cur + 1)?.takeIf { cur >= 0 }
                } ?: return@withLock
                val query = next.url
                if (com.music.spotui.data.preferences.downloadedPathForQuery(ctx, query) != null) return@withLock
                val key = soulseekKey(query)
                if (com.music.spotui.soulseek.SoulseekSource.cached(ctx, key) != null) return@withLock
                val info = losslessTrackInfo(query)
                if (info.title.isBlank() || info.artist.isBlank()) return@withLock
                Log.d(TAG, "look-ahead: fetching ${info.title} from Soulseek")
                runCatching { com.music.spotui.soulseek.SoulseekSource.flac(ctx, key, info.title, info.artist, info.durationSec) }
            }
        }
    }

    private fun isLosslessSource(song: String): Boolean =
        sourceCache[song]?.startsWith("Lossless") == true || qualityCache[song] == "FLAC"

    private suspend fun quickStartThenUpgrade(song: String, ctx: Context) = kotlinx.coroutines.coroutineScope {
        val quality = com.music.spotui.data.preferences.currentStreamingQuality(ctx)
        val best = async { resolveStreamUrl(song, ctx, allowYouTube = false) }
        val quick = async { resolveYtPlayback(song, quality.audioQuality, ctx) }
        val bestFirst = kotlinx.coroutines.selects.select<Boolean> {
            best.onAwait { true }
            quick.onAwait { false }
        }
        // Lossless was ready first: play it straight away.
        val early = if (bestFirst) best.await()?.takeIf { isLosslessSource(song) } else null
        if (early != null) {
            quick.cancel()
            currentSource = sourceCache[song] ?: currentSource
            currentQuality = qualityCache[song].orEmpty()
            startStream(song, early, ctx, streamMimeType(early, sourceCache[song].orEmpty()))
            return@coroutineScope
        }
        val fast = quick.await()
        if (currentRequest != song) return@coroutineScope
        if (fast != null) {
            currentSource = "YouTube"
            currentQuality = ytQualityLabel(fast)
            startStream(song, fast.streamUrl, ctx)
        }
        val better = best.await()
        // This song's lookup is done; use the time left for the next one.
        soulseekLookahead(ctx)
        if (currentRequest != song) return@coroutineScope
        when {
            better != null && isLosslessSource(song) && fast != null -> swapToLossless(song, better)
            better != null && fast == null -> startStream(song, better, ctx, streamMimeType(better, sourceCache[song].orEmpty()))
            fast == null -> streamFailed(song, ctx)
            else -> {
                // No lossless copy: remember the YouTube stream so a replay starts at once.
                streamCache[song] = fast.streamUrl
                streamCachedAt[song] = android.os.SystemClock.elapsedRealtime()
                sourceCache[song] = "YouTube"
                qualityCache[song] = ytQualityLabel(fast)
            }
        }
    }

    /**
     * Replace the quick-start stream with the lossless one without a gap: the FLAC
     * loads muted on a second player a few seconds ahead, starts when playback gets
     * there, and a short crossfade (the crossfade engine) hides any small offset
     * between the two masters. Falls back to a plain switch if it can't line up.
     */
    private suspend fun swapToLossless(song: String, url: String) {
        val ctx = appCtx ?: return
        val mime = streamMimeType(url, sourceCache[song].orEmpty())
        val primary = player ?: return
        val playing = withContext(Dispatchers.Main) { primary.playWhenReady && primary.isPlaying }
        if (currentRequest != song || isCrossfading) return
        if (!playing || !seamlessSwap(song, url, mime, primary, ctx)) hardSwap(song, url, mime)
    }

    private suspend fun seamlessSwap(
        song: String, url: String, mime: String?, primary: ExoPlayer, ctx: Context,
    ): Boolean {
        val leadMs = 4_000L
        val (sp, sf) = withContext(Dispatchers.Main) { createPlayerWithFilter(ctx, handleAudioFocus = false) }
        val startAt = withContext(Dispatchers.Main) { primary.currentPosition + leadMs }
        isCrossfading = true // user actions now cancel this via cancelCrossfade()
        secondaryPlayer = sp
        secondaryPlayerFilter = sf
        pendingNextSong = null // same song: no queue/UI change when it completes
        fun stillOn() = currentRequest == song && secondaryPlayer === sp && player === primary
        withContext(Dispatchers.Main) {
            sp.setMediaItem(buildMediaItem(url, mime), startAt)
            sp.volume = 0f
            sp.playWhenReady = false
            sp.prepare()
        }
        // Wait until the FLAC is buffered at startAt, while the stream plays toward it.
        val ready = kotlinx.coroutines.withTimeoutOrNull(leadMs - 500) {
            while (withContext(Dispatchers.Main) { sp.playbackState != Player.STATE_READY }) {
                if (!stillOn()) return@withTimeoutOrNull false
                delay(50)
            }
            true
        } == true
        if (!ready || !stillOn()) {
            if (secondaryPlayer === sp) cancelCrossfade() // releases sp, restores state
            return false
        }
        // Start it the moment playback reaches startAt (a pause meanwhile: plain switch).
        while (stillOn()) {
            val (pos, playingNow) = withContext(Dispatchers.Main) { primary.currentPosition to primary.isPlaying }
            if (!playingNow) {
                cancelCrossfade()
                return false
            }
            if (pos >= startAt - 15) break
            delay(10)
        }
        if (!stillOn()) return false
        withContext(Dispatchers.Main) { sp.playWhenReady = true }
        preloadedNext = null // the promoted player starts without anything queued ahead
        performCrossfade(400, djMode = false)
        currentSource = sourceCache[song] ?: currentSource
        currentQuality = qualityCache[song].orEmpty()
        Log.d(TAG, "switched seamlessly to $currentSource for: $song")
        return true
    }

    /** Plain switch at the same position (used when paused or if lining up failed). */
    private suspend fun hardSwap(song: String, url: String, mime: String?) = withContext(Dispatchers.Main) {
        val p = player ?: return@withContext
        if (currentRequest != song || isCrossfading) return@withContext
        val pos = p.currentPosition
        val playing = p.playWhenReady
        preloadedNext = null // setMediaItem replaces anything queued ahead
        p.setMediaItem(buildMediaItem(url, mime), pos)
        p.prepare()
        p.playWhenReady = playing
        currentSource = sourceCache[song] ?: currentSource
        currentQuality = qualityCache[song].orEmpty()
        Log.d(TAG, "switched to $currentSource at ${pos / 1000}s for: $song")
    }

    // Build a MediaItem carrying the current track's metadata so the system media
    // notification (MediaSession) shows the right title / artist / artwork.
    private fun buildMediaItem(
        streamUrl: String,
        mimeType: String? = null,
        query: String = currentRequest,
        title: String = metaTitle,
        artist: String = metaArtist,
        cover: String = metaCover,
    ): MediaItem {
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .apply { if (cover.isNotBlank()) setArtworkUri(android.net.Uri.parse(cover)) }
            .build()
        return MediaItem.Builder()
            // The play query, so a gapless transition tells us which track is now playing.
            .setMediaId(query)
            .setUri(streamUrl)
            // Hint the container so ExoPlayer picks the right source/extractor even
            // when the URL has no extension: TIDAL lossless is a DASH .mpd manifest,
            // and single-file lossless is FLAC.
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .setMediaMetadata(metadata)
            .build()
    }

    /** MIME hint for a resolved stream: DASH manifest, single-file FLAC, or none. */
    private fun streamMimeType(streamUrl: String, source: String = currentSource): String? {
        val bare = streamUrl.substringBefore('?').lowercase()
        return when {
            streamUrl.startsWith("deezer://") ->
                if (streamUrl.contains("fmt=flac")) androidx.media3.common.MimeTypes.AUDIO_FLAC
                else androidx.media3.common.MimeTypes.AUDIO_MPEG
            streamUrl.startsWith("data:application/dash+xml") ||
                bare.endsWith(".mpd") || streamUrl.contains("manifest.tidal.com") || streamUrl.contains("/manifests/") ->
                androidx.media3.common.MimeTypes.APPLICATION_MPD
            bare.endsWith(".flac") || source.startsWith("Lossless") ->
                androidx.media3.common.MimeTypes.AUDIO_FLAC
            else -> null
        }
    }

    /** Warm the cache for an upcoming track (e.g. the next/previous queue item). */
    fun prefetch(song: String, context: Context) {
        if (song.isBlank() || streamCache.containsKey(song)) return
        val appContext = context.applicationContext
        // No point resolving YouTube streams while Spotify web is the engine — it's
        // wasted network/CPU that competes with the streaming audio (caused stutter).
        if (webPlaybackActive()) return
        // Lossless FLAC URLs from the backends are short-lived / single-use. Caching
        // one now + preloading a partial intro makes playback stop after ~30s when the
        // continuation hits a stale URL, so resolve those fresh at play time instead.
        if (losslessStreaming && com.music.spotui.data.preferences.currentStreamingQuality(appContext).lossless) return
        // Deezer resolves a fresh CDN url per play; a YouTube prefetch cached under
        // the same key would shadow it, so skip prefetch entirely when Deezer is on.
        if (deezerEnabled && com.music.spotui.data.preferences.isDeezerEnabled(appContext)) return
        scope.launch {
            val url = runCatching { resolveStreamUrl(song, appContext) }.getOrNull()
            if (url != null) cacheIntro(url, appContext)
        }
    }

    // ── Intro preloading (instant playback) ──
    // Resolving the stream URL hides most latency, but ExoPlayer still has to open the
    // connection and buffer the first segment on tap. We pre-cache the first ~1 MB (≈20–40s
    // of audio) of upcoming tracks into a media cache the player reads through, so a tap on a
    // preloaded track starts almost instantly. Skipped for local files (already instant) and
    // when the user turns preloading off in Settings.
    private const val PRELOAD_BYTES = 1L * 1024 * 1024

    @Volatile private var mediaCache: androidx.media3.datasource.cache.SimpleCache? = null

    internal fun mediaCache(context: Context): androidx.media3.datasource.cache.SimpleCache =
        mediaCache ?: synchronized(this) {
            mediaCache ?: androidx.media3.datasource.cache.SimpleCache(
                java.io.File(context.cacheDir, "media"),
                androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(com.music.spotui.cache.CacheBudget.AUDIO_BYTES),
                androidx.media3.database.StandaloneDatabaseProvider(context),
            ).also { mediaCache = it }
        }

    private fun cacheDataSourceFactory(context: Context): androidx.media3.datasource.cache.CacheDataSource.Factory {
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
            .setAllowCrossProtocolRedirects(true)
        val baseUpstream = androidx.media3.datasource.DefaultDataSource.Factory(context, http)
        val resolvedUpstream = androidx.media3.datasource.ResolvingDataSource.Factory(baseUpstream) { dataSpec ->
            val url = dataSpec.uri.toString()
            val backupHeaders = com.music.spotui.audio.StreamRequestHeaders.get(url)
            if (backupHeaders != null) dataSpec.withAdditionalHeaders(backupHeaders)
            else if (url.contains("googlevideo.com")) {
                dataSpec.withAdditionalHeaders(
                    com.metrolist.innertube.models.YouTubeClient.forStreamUrl(url).mediaHeaders(),
                )
            } else dataSpec
        }
        val upstream = com.music.spotui.playback.ChunkedDataSource.Factory(
            resolvedUpstream,
            2L * 1024 * 1024,
        )
        return androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(mediaCache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Pre-cache the first [PRELOAD_BYTES] of [url] into the media cache (http(s) only). */
    private fun cacheIntro(url: String, appContext: Context) {
        if (!url.startsWith("http")) return
        if (!com.music.spotui.data.preferences.isPreloadEnabled(appContext)) return
        runCatching {
            val ds = cacheDataSourceFactory(appContext).createDataSource()
            val spec = androidx.media3.datasource.DataSpec.Builder()
                .setUri(android.net.Uri.parse(url))
                .setLength(PRELOAD_BYTES)
                .build()
            androidx.media3.datasource.cache.CacheWriter(ds, spec, null, null).cache()
        }.onFailure { Log.d(TAG, "intro preload skipped: ${it.message}") }
    }

    // forPlayback=true only for the track actually being played — so background
    // prefetch of upcoming tracks doesn't clobber the current source badge (a
    // prefetch resolving the NEXT track via YouTube was flipping the badge to
    // "YouTube" while the current track streamed from Spotify).
    /** Resolves audio for the paired LAN browser without starting or changing the phone player. */
    internal suspend fun resolveForBrowser(song: com.music.spotui.data.entity.SongsModel, context: Context): Pair<String, String?>? =
        withContext(Dispatchers.IO) {
            registerLossless(listOf(song.url to song.spotifyTrackId))
            registerDuration(listOf(song.url to song.durationMs))
            registerExplicit(listOf(song.url to song.explicit))
            registerMetadata(listOf(song.url to TrackMatchMetadata(song.title, song.singer, song.album)))
            kotlinx.coroutines.withTimeoutOrNull(50_000L) {
                resolveStreamUrl(song.url, context.applicationContext, forPlayback = false)?.let { uri ->
                    uri to streamMimeType(uri, sourceCache[song.url].orEmpty())
                }
            }
        }

    internal fun browserDataSource(context: Context): androidx.media3.datasource.DataSource =
        com.music.spotui.deezer.DeezerAwareDataSourceFactory(cacheDataSourceFactory(context)).createDataSource()

    private suspend fun resolveStreamUrl(
        song: String, appContext: Context, forPlayback: Boolean = false, allowYouTube: Boolean = true,
    ): String? = withContext(com.music.spotui.audio.PlaybackDiagnosticSong.context(diagnosticSong(song).ifBlank { com.music.spotui.audio.PlaybackDiagnosticSong.current() })) {
        resolveStreamUrlInternal(song, appContext, forPlayback, allowYouTube)
    }

    private suspend fun resolveStreamUrlInternal(
        song: String,
        appContext: Context,
        forPlayback: Boolean = false,
        allowYouTube: Boolean = true,
    ): String? {
        // Imported local files: the play query IS the file's content:// / file:// URI.
        // ExoPlayer plays it directly (FLAC/MP3/WAV/… via its built-in extractors).
        if (song.startsWith("content://") || song.startsWith("file://")) {
            if (forPlayback) {
                currentSource = "Local file"
                currentQuality = song.substringBefore('?').substringAfterLast('.', "")
                    .uppercase().takeIf { it.length in 2..5 }.orEmpty()
            }
            return song
        }
        alternativeStreamForPlayback(song, appContext)?.let { alt ->
            invalidateResolvedStream(song)
            return when {
                alt.isLocal -> {
                    if (forPlayback) {
                        currentSource = "Alternative file"
                        currentQuality = alt.label.substringAfterLast('.', "").uppercase().takeIf { it.length in 2..5 }.orEmpty()
                    }
                    alt.value
                }
                alt.isYouTube -> {
                    if (forPlayback) {
                        currentSource = "Alternative YouTube"
                        currentQuality = ""
                    }
                    val quality = com.music.spotui.data.preferences.currentStreamingQuality(appContext)
                    val playback = resolveYtPlayback(alt.value, quality.audioQuality, appContext, forPlayback) ?: return null
                    if (forPlayback) currentQuality = ytQualityLabel(playback)
                    playback.streamUrl
                }
                else -> null
            }
        }
        // Offline: if this track was downloaded, play the local file instead of the network.
        com.music.spotui.data.preferences.downloadedPathForQuery(appContext, song)?.let { path ->
            if (forPlayback) {
                currentSource = "Downloaded"
                currentQuality = path.substringAfterLast('.', "").uppercase()
            }
            return android.net.Uri.fromFile(java.io.File(path)).toString()
        }
        streamCache[song]?.let { cachedUrl ->
            val age = android.os.SystemClock.elapsedRealtime() - (streamCachedAt[song] ?: 0L)
            val localMissing = cachedUrl.startsWith("file:") && runCatching {
                !java.io.File(android.net.Uri.parse(cachedUrl).path.orEmpty()).isFile
            }.getOrDefault(true)
            if (localMissing || age > 15 * 60_000L || age < 0L) {
                invalidateResolvedStream(song)
            } else {
            // Source choices take effect immediately; never let a URL cached under the
            // previous choice silently keep using that provider.
            val cachedSource = sourceCache[song]
            val selectedSource = com.music.spotui.data.preferences.getPrimaryMusicSource(appContext)
            val sourceStillSelected = when (cachedSource) {
                "Deezer" -> selectedSource == com.music.spotui.data.preferences.MusicSource.DEEZER
                "YouTube" -> selectedSource != com.music.spotui.data.preferences.MusicSource.DEEZER
                else -> true // local, downloaded, alternative and lossless sources are independent
            }
            if (!sourceStillSelected) {
                invalidateResolvedStream(song)
            } else {
            // Cache hits must still update the badge — returning early kept the
            // previous track's label (e.g. "Downloaded") on a streamed track.
                if (forPlayback) {
                    currentSource = cachedSource ?: "YouTube"
                    currentQuality = qualityCache[song] ?: ""
                }
                return cachedUrl
            }
            }
        }
        // Quality for the current network (Wi-Fi vs cellular), from Settings.
        val quality = com.music.spotui.data.preferences.currentStreamingQuality(appContext)

        // A Soulseek FLAC fetched earlier (look-ahead or a past play) starts right away.
        if (soulseekOn(appContext) && quality.lossless) {
            com.music.spotui.soulseek.SoulseekSource.cached(appContext, soulseekKey(song))?.let { file ->
                if (forPlayback) { currentSource = "Lossless • Soulseek"; currentQuality = "FLAC" }
                sourceCache[song] = "Lossless • Soulseek"
                qualityCache[song] = "FLAC"
                return android.net.Uri.fromFile(file).toString()
            }
        }

        // Deezer — preferred, but when Lossless is selected a FREE Deezer account only
        // yields MP3. In that case we HOLD the MP3 as a fallback and try the real FLAC
        // sources first, so lossless isn't silently pre-empted by Deezer MP3.
        var heldDeezer: com.music.spotui.deezer.DeezerSource.Result.Success? = null
        if (deezerEnabled && com.music.spotui.data.preferences.isDeezerEnabled(appContext)) {
            val spotifyId = trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song)
            val r = kotlinx.coroutines.withTimeoutOrNull(12_000) {
                com.music.spotui.deezer.DeezerSource.resolve(
                    appContext,
                    spotifyId = spotifyId,
                    isrc = null,
                    searchQuery = searchTextForPlayback(song),
                    expectedDurationSec = (durationRegistry[song] ?: 0) / 1000,
                )
            }
            if (r is com.music.spotui.deezer.DeezerSource.Result.Success) {
                // At High, YouTube Opus (~160 kbps) beats a free account's MP3 128 (#66).
                val belowYouTube = r.lowBitrate && youtubeEnabled &&
                    quality.audioQuality == com.metrolist.music.constants.AudioQuality.HIGH
                if (r.mimeFlac || (!quality.lossless && !belowYouTube)) {
                    Log.d(TAG, "deezer ${r.qualityLabel} for: $song")
                    if (forPlayback) { currentSource = "Deezer"; currentQuality = r.qualityLabel }
                    streamCache[song] = r.uri
                    streamCachedAt[song] = android.os.SystemClock.elapsedRealtime()
                    sourceCache[song] = "Deezer"
                    qualityCache[song] = r.qualityLabel
                    return r.uri
                }
                heldDeezer = r // MP3 only: try FLAC (Lossless) or YouTube (MP3 128 at High) first.
                Log.d(TAG, "deezer only ${r.qualityLabel}; trying better sources first for: $song")
            } else {
                Log.d(TAG, "deezer miss ($r), continuing for: $song")
            }
        }

        // Lossless FLAC: SpotiFLAC gated (if verified) + Tidal/community, ISRC-matched.
        if (losslessStreaming && quality.lossless) {
            (trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song))?.let { spotifyId ->
                val r = kotlinx.coroutines.withTimeoutOrNull(15_000) {
                    com.music.spotui.lossless.LosslessSource.resolve(appContext, spotifyId, losslessTrackInfo(song), losslessHiRes)
                }
                if (r is com.music.spotui.lossless.LosslessSource.Result.Success) {
                    val flacQuality = "FLAC ${r.track.quality}-bit"
                    if (forPlayback) {
                        currentSource = "Lossless • ${r.track.provider}"
                        currentQuality = flacQuality
                    }
                    streamCache[song] = r.track.url
                    streamCachedAt[song] = android.os.SystemClock.elapsedRealtime()
                    sourceCache[song] = "Lossless • ${r.track.provider}"
                    qualityCache[song] = flacQuality
                    return r.track.url
                } else {
                    Log.d(TAG, "lossless miss ($r) for: $song")
                }
            }
        }

        // Soulseek (P2P) when the lossless servers have nothing. A peer can take a
        // minute, so only for the background upgrade, while another stream plays.
        if (soulseekOn(appContext) && !allowYouTube && quality.lossless) {
            val info = losslessTrackInfo(song)
            val key = soulseekKey(song)
            if (info.title.isNotBlank() && info.artist.isNotBlank()) {
                val file = runCatching {
                    com.music.spotui.soulseek.SoulseekSource.flac(appContext, key, info.title, info.artist, info.durationSec)
                }.getOrNull()
                if (file != null) {
                    val url = android.net.Uri.fromFile(file).toString()
                    streamCache[song] = url
                    streamCachedAt[song] = android.os.SystemClock.elapsedRealtime()
                    sourceCache[song] = "Lossless • Soulseek"
                    qualityCache[song] = "FLAC"
                    return url
                }
            }
        }

        fun useHeldDeezer(r: com.music.spotui.deezer.DeezerSource.Result.Success): String {
            Log.d(TAG, "using Deezer MP3 fallback for: $song")
            if (forPlayback) { currentSource = "Deezer"; currentQuality = r.qualityLabel }
            streamCache[song] = r.uri
            streamCachedAt[song] = android.os.SystemClock.elapsedRealtime()
            sourceCache[song] = "Deezer"
            qualityCache[song] = r.qualityLabel
            return r.uri
        }
        // Deezer MP3 320 (held above) before YouTube; MP3 128 only if YouTube fails.
        heldDeezer?.takeIf { !it.lowBitrate }?.let { return useHeldDeezer(it) }
        if (!youtubeEnabled || !allowYouTube) {
            if (!youtubeEnabled) Log.w(TAG, "YouTube fallback disabled — no stream for: $song")
            return heldDeezer?.let(::useHeldDeezer)
        }
        if (forPlayback) {
            currentSource = "YouTube"
            // Clear the previous track's quality so a failed resolve can't leave
            // a stale "FLAC 24-bit" badge on a YouTube stream.
            currentQuality = ""
        }
        val playback = resolveYtPlayback(song, quality.audioQuality, appContext, forPlayback)
            ?: return heldDeezer?.let(::useHeldDeezer)
        val ytQuality = ytQualityLabel(playback)
        if (forPlayback) currentQuality = ytQuality
        streamCache[song] = playback.streamUrl
        streamCachedAt[song] = android.os.SystemClock.elapsedRealtime()
        sourceCache[song] = "YouTube"
        qualityCache[song] = ytQuality
        return playback.streamUrl
    }

    // e.g. "OPUS 141 kbps" from the chosen adaptive format.
    private fun ytQualityLabel(playback: YTPlayerUtils.PlaybackData): String {
        val codec = playback.format.mimeType
            .substringAfter("codecs=\"", "").substringBefore('"').substringBefore('.')
            .uppercase()
        return listOf(codec, "${playback.format.bitrate / 1000} kbps")
            .filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun alternativeStreamForPlayback(
        song: String,
        appContext: Context,
    ): com.music.spotui.data.preferences.AlternativeStream? {
        val key = alternativeKeyRegistry[song]
            ?: spotifyTrackIdForPlayback(song)?.let {
                com.music.spotui.data.preferences.alternativeStreamKeyForSpotifyId(it)
            }
        return key?.let { com.music.spotui.data.preferences.getAlternativeStream(appContext, it) }
    }

    /** Fired (main thread) when the current track plays to its end. */
    @Volatile var onTrackEnded: (() -> Unit)? = null

    /** Fired (main thread) when a stream can't be resolved for [song], so the caller
     *  can skip to the next track instead of leaving playback silent. */
    @Volatile var onStreamFailed: ((String) -> Unit)? = null

    private data class CandidateScore(
        val item: SongItem,
        val score: Double,
        val titleScore: Double,
        val artistScore: Double,
        val artistEvidenceScore: Double,
        val durationScore: Double?,
        val albumScore: Double?,
        val alternatePenalty: Double,
        val unexpectedAlternates: List<String>,
    )

    private fun normalizedForMatch(value: String): String =
        value.lowercase()
            .replace(featSearchPattern, "")
            .replace(Regex("""[^\p{L}\p{Nd}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private val anyLatinTransliterator by lazy {
        runCatching {
            android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII")
        }.getOrNull()
    }

    private fun foldLatinDiacritics(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")

    private fun transliterateCyrillic(value: String): String {
        val map = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
            'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
            'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
            'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
            'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch",
            'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
            'э' to "e", 'ю' to "yu", 'я' to "ya",
        )
        return buildString {
            value.lowercase().forEach { ch ->
                append(map[ch] ?: ch)
            }
        }
    }

    private fun bigramSimilarity(a: String, b: String): Double {
        fun variants(value: String): List<String> =
            listOfNotNull(
                value,
                foldLatinDiacritics(value),
                transliterateCyrillic(value),
                anyLatinTransliterator?.transliterate(value),
            )
                .map(::normalizedForMatch)
                .filter { it.isNotBlank() }
                .distinct()

        fun score(na: String, nb: String): Double {
            if (na == nb) return 1.0
            if (na.length < 2 || nb.length < 2) return 0.0
            val aBigrams = na.windowed(2).toSet()
            val bBigrams = nb.windowed(2).toSet()
            if (aBigrams.isEmpty() || bBigrams.isEmpty()) return 0.0
            val intersection = aBigrams.count { it in bBigrams }
            return (2.0 * intersection) / (aBigrams.size + bBigrams.size)
        }
        return variants(a).maxOf { aa ->
            variants(b).maxOf { bb -> score(aa, bb) }
        }
    }

    private data class VersionMarker(
        val name: String,
        val pattern: Regex,
        val hardReject: Boolean,
    )

    private fun markerPattern(terms: String): Regex =
        Regex("""(^|\s)($terms)(\s|$)""", RegexOption.IGNORE_CASE)

    private val alternateVersionMarkers = listOf(
        VersionMarker("remix", markerPattern("""re\s*mix|rmx|club mix|dance mix|dub mix|vip mix|ремикс|рмикс"""), true),
        VersionMarker("alternate", markerPattern("""alternative|alternate|alt version|demo|demo version|unreleased|rough mix|early version|альтернатив\w*|демо|неиздан\w*|чернов\w*"""), true),
        VersionMarker("sped up", markerPattern("""sped\s*up|speed\s*up|fast version|ускоренн\w*|быстрая версия"""), true),
        VersionMarker("slowed", markerPattern("""slowed|slowed reverb|slow version|замедленн\w*|медленная версия"""), true),
        VersionMarker("nightcore", markerPattern("""nightcore|daycore"""), true),
        VersionMarker("live", markerPattern("""live|concert|session|performance|лайв|концерт|с концерта|выступлен\w*"""), true),
        VersionMarker("acoustic", markerPattern("""acoustic|unplugged|piano version|guitar version|акустик\w*|пианино|гитар\w*"""), true),
        VersionMarker("cover", markerPattern("""cover|covered by|tribute|кавер|трибьют"""), true),
        VersionMarker("karaoke", markerPattern("""karaoke|minus one|караоке|минусовка"""), true),
        VersionMarker("instrumental", markerPattern("""instrumental|no vocals|инструментал|без вокала"""), true),
        VersionMarker("mashup", markerPattern("""mashup|mash up|bootleg|rework|flip|мешап|мэшап|бутлег"""), true),
        VersionMarker("fan edit", markerPattern("""fan edit|fanmade|right version|edit audio|перезалив|перезалит\w*"""), true),
        VersionMarker("extended", markerPattern("""extended mix|extended version|12 inch|12"""), false),
        VersionMarker("radio edit", markerPattern("""radio edit|single edit|edit version"""), false),
        VersionMarker("remaster", markerPattern("""remaster|remastered|anniversary edition"""), false),
    )

    private fun versionMarkers(value: String): Set<String> {
        val normalized = normalizedForMatch(value)
        return alternateVersionMarkers
            .filter { marker -> marker.pattern.containsMatchIn(normalized) }
            .map { it.name }
            .toSet()
    }

    private fun hardVersionMarkers(names: Collection<String>): Set<String> {
        val hardNames = alternateVersionMarkers.filter { it.hardReject }.map { it.name }.toSet()
        return names.filterTo(mutableSetOf()) { it in hardNames }
    }

    private fun ytmusicTransferScore(
        candidate: SongItem,
        expected: TrackMatchMetadata,
        expectedDurationMs: Int,
    ): CandidateScore {
        var candidateTitle = candidate.title
        if (candidate.isVideoSong) {
            val split = candidateTitle.split(" - ", limit = 2)
            if (split.size == 2) candidateTitle = split[1].trim()
        }

        val titleScore = bigramSimilarity(
            com.music.spotui.audio.MusicTitlePolicy.clean(candidateTitle),
            com.music.spotui.audio.MusicTitlePolicy.clean(expected.title),
        )
        val uploaderArtistScore = bigramSimilarity(
            candidate.artists.joinToString(" ") { it.name },
            expected.artist,
        )
        val titleArtistScore = if (candidate.isVideoSong) {
            bigramSimilarity(candidate.title.substringBefore("-"), expected.artist)
        } else {
            0.0
        }
        // Featured artists may be omitted or reordered by YouTube. A separately credited
        // primary artist is useful evidence without weakening the title/version gates.
        val primaryArtistScore = candidate.artists.maxOfOrNull {
            bigramSimilarity(it.name, expected.artist.substringBefore(','))
        } ?: 0.0
        val artistScore = maxOf(uploaderArtistScore, titleArtistScore, primaryArtistScore)

        val expectedDurationSec = expectedDurationMs / 1000.0
        val candidateDuration = candidate.duration
        val durationScore = if (expectedDurationSec > 0 && candidateDuration != null) {
            (1.0 - abs(candidateDuration - expectedDurationSec) * 2.0 / expectedDurationSec)
                .coerceIn(0.0, 1.0)
        } else {
            null
        }

        val albumScore = if (!candidate.isVideoSong && expected.album.isNotBlank()) {
            candidate.album?.name?.let { bigramSimilarity(it, expected.album) }
        } else {
            null
        }

        val expectedMarkers = versionMarkers(expected.title)
        val candidateMarkers = versionMarkers(
            listOf(
                candidate.title,
                candidate.album?.name.orEmpty(),
            ).joinToString(" "),
        )
        val unexpectedAlternates = (candidateMarkers - expectedMarkers).toList().sorted()
        val hardUnexpected = hardVersionMarkers(unexpectedAlternates).size
        val softUnexpected = unexpectedAlternates.size - hardUnexpected
        val alternatePenalty = hardUnexpected * 1.75 + softUnexpected * 0.65

        val parts = mutableListOf(titleScore, artistScore)
        durationScore?.let { parts += it * 5.0 }
        albumScore?.let { parts += it }
        val resultTypeBoost = if (candidate.isVideoSong) 1.0 else 2.0
        val baseScore = parts.average() * resultTypeBoost
        return CandidateScore(
            item = candidate,
            score = (baseScore - alternatePenalty).coerceAtLeast(0.0),
            titleScore = titleScore,
            artistScore = uploaderArtistScore,
            artistEvidenceScore = artistScore,
            durationScore = durationScore,
            albumScore = albumScore,
            alternatePenalty = alternatePenalty,
            unexpectedAlternates = unexpectedAlternates,
        )
    }

    private fun CandidateScore.isAcceptableMatch(): Boolean {
        val durationStrong = durationScore?.let { it >= 0.94 } ?: false
        val albumUseful = albumScore?.let { it >= 0.45 } ?: false
        val hasDuration = durationScore != null
        val minScore = when {
            hasDuration && item.isVideoSong -> 1.55
            hasDuration -> 2.25
            item.isVideoSong -> 0.78
            else -> 1.35
        }

        // Duration is deliberately weighted heavily, as in spotify_to_ytmusic,
        // but these gates prevent a same-length wrong-artist song from winning.
        // If Spotify did not ask for a remix/live/acoustic/etc version, do not
        // let that alternate upload win just because its title and length are close.
        // When duration is missing, allow strong title+artist/album matches
        // instead of rejecting every possible candidate.
        val hasUnexpectedHardAlternate = hardVersionMarkers(unexpectedAlternates).isNotEmpty()
        return score >= minScore &&
            !hasUnexpectedHardAlternate &&
            titleScore >= 0.45 &&
            (
                artistEvidenceScore >= 0.32 ||
                    (albumUseful && artistEvidenceScore >= 0.18) ||
                    (durationStrong && artistEvidenceScore >= 0.25)
                )
    }

    private suspend fun ensureSpotifyMatchMetadata(query: String): TrackMatchMetadata? {
        val currentMeta = metadataRegistry[query]
        val hasUsefulMeta = currentMeta?.let {
            it.title.isNotBlank() && it.artist.isNotBlank() && it.album.isNotBlank()
        } ?: false
        if (hasUsefulMeta && durationRegistry[query] != null && explicitRegistry.containsKey(query) &&
            spotifyMetadataRepaired.contains(query)) {
            return currentMeta
        }

        val spotifyId = trackIdRegistry[query] ?: spotifyTrackIdForPlayback(query) ?: return currentMeta
        val track = try {
            kotlinx.coroutines.withTimeoutOrNull(8_000L) { com.metrolist.spotify.Spotify.track(spotifyId).getOrNull() }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Spotify metadata repair deferred for $spotifyId", error)
            null
        } ?: return currentMeta

        val repaired = TrackMatchMetadata(
            title = track.name,
            artist = track.artists.joinToString(", ") { it.name },
            album = track.album?.name ?: currentMeta?.album.orEmpty(),
            isrc = track.isrc.orEmpty(),
        )
        metadataRegistry[query] = repaired
        trackIdRegistry[query] = spotifyId
        explicitRegistry[query] = track.explicit
        if (track.durationMs > 0) durationRegistry[query] = track.durationMs
        spotifyMetadataRepaired += query
        return repaired
    }

    private suspend fun resolveVideoCandidates(
        query: String,
        filter: YouTube.SearchFilter = YouTube.SearchFilter.FILTER_SONG,
    ): List<String> {
        val searchText = searchTextForPlayback(query)
        // A raw YouTube videoId is 11 chars with no spaces — accept it directly.
        if (searchText.length == 11 && !searchText.contains(' ')) return listOf(searchText)
        val exactMeta = ensureSpotifyMatchMetadata(query)
        val wantExplicit = explicitRegistry[query]

        suspend fun searchSongs(text: String): List<SongItem> {
            val firstPage = YouTube.search(text, filter)
                .onFailure { Log.w(TAG, "resolveVideoId: YouTube search failed for: $text", it) }
                .getOrNull() ?: return emptyList()
            val found = firstPage.items.filterIsInstance<SongItem>().toMutableList()

            // The explicit edition is often just below the clean edition. Walk two
            // continuation pages before accepting that YouTube Music has no match.
            var continuation = firstPage.continuation
            repeat(2) {
                if (wantExplicit != true || found.any { it.explicit } || continuation == null) return@repeat
                val next = YouTube.searchContinuation(continuation!!).getOrNull() ?: return@repeat
                found += next.items.filterIsInstance<SongItem>()
                continuation = next.continuation
            }
            return found
        }

        val searchQueries = buildList {
            if (wantExplicit == true && !exactMeta?.isrc.isNullOrBlank()) add(exactMeta!!.isrc)
            add(searchText)
            if (wantExplicit == true) add("$searchText explicit")
        }.distinct()
        val hits = searchQueries.flatMap { searchSongs(it) }.distinctBy { it.id }
        if (hits.isEmpty()) {
            appCtx?.let { com.music.spotui.data.preferences.PlaybackDiagnostics.record(it, "YouTube search result", "No song results") }
            Log.w(TAG, "resolveVideoId: no YouTube song results for: $searchText")
            return emptyList()
        }
        // YouTube's top hit is NOT always the requested song (worst for
        // non-English titles). Score every hit against the query — the query is
        // "title artist1, artist2":
        //   +2 artist match, +1 title match, +2 duration match.
        // Duration is the key disambiguator for same-title/different-artist: a
        // wrong-artist song with the same name almost always has a different
        // length, so it never ties the real track once duration is in the score.
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val qn = norm(searchText)
        val wantSec = durationRegistry[query]?.let { it / 1000 }
        val scored = hits.map { h ->
            val cleanTitle = norm(h.title.substringBefore('(').substringBefore('['))
            var s = 0
            if (cleanTitle.isNotEmpty() && qn.contains(cleanTitle)) s += 1
            if (h.artists.any { a -> norm(a.name).let { it.isNotEmpty() && qn.contains(it) } }) s += 2
            // Within ~4s of the Spotify track length → very likely the same recording.
            val hDur = h.duration
            if (wantSec != null && hDur != null && kotlin.math.abs(hDur - wantSec) <= 4) s += 2
            h to s
        }
        val transferScored = if (exactMeta != null) {
            hits.map { ytmusicTransferScore(it, exactMeta, durationRegistry[query] ?: 0) }
        } else {
            emptyList()
        }
        // A hit is "verified" as the right recording when its artist matches the
        // query, OR (when we know the Spotify length) its duration is within ~4s.
        fun verified(h: SongItem): Boolean {
            val artistOk = h.artists.any { a -> norm(a.name).let { it.isNotEmpty() && qn.contains(it) } }
            val d = h.duration
            val durOk = wantSec != null && d != null && kotlin.math.abs(d - wantSec) <= 4
            return artistOk || durOk
        }
        fun isRequestedEdition(item: SongItem): Boolean = when (wantExplicit) {
            true -> item.explicit || (filter == YouTube.SearchFilter.FILTER_VIDEO &&
                Regex("\\b(explicit|uncensored)\\b", RegexOption.IGNORE_CASE).containsMatchIn(item.title))
            false -> !item.explicit
            null -> true
        }
        fun explicitFirst(list: List<SongItem>) =
            if (wantExplicit != null) list.sortedByDescending { it.explicit == wantExplicit } else list
        val ordered = if (transferScored.isNotEmpty()) {
            val accepted = transferScored
                .filter { it.isAcceptableMatch() }
                .filter { isRequestedEdition(it.item) }
                .sortedWith(
                    compareByDescending<CandidateScore> { it.item.explicit == wantExplicit || wantExplicit == null }
                        .thenByDescending { it.score }
                )
                .map { it.item }
            if (accepted.isEmpty()) {
                appCtx?.let { com.music.spotui.data.preferences.PlaybackDiagnostics.record(it, "YouTube search result", "No accepted matches") }
                val reasons = transferScored.groupingBy {
                    when {
                        hardVersionMarkers(it.unexpectedAlternates).isNotEmpty() -> "Alternate recording"
                        it.titleScore < 0.45 -> "Title mismatch"
                        it.artistEvidenceScore < 0.18 -> "Artist mismatch"
                        !it.isAcceptableMatch() -> "Recording score too low"
                        else -> "Explicit edition mismatch"
                    }
                }.eachCount()
                reasons.forEach { (reason, count) ->
                    appCtx?.let { com.music.spotui.data.preferences.PlaybackDiagnostics.record(it, "YouTube matches rejected", "$reason: $count") }
                }
                val best = transferScored.maxByOrNull { it.score }
                Log.w(
                    TAG,
                    "resolveVideoId: rejecting weak YouTube match for '$searchText' " +
                        "(best='${best?.item?.title}' score=${"%.2f".format(best?.score ?: 0.0)} " +
                        "title=${"%.2f".format(best?.titleScore ?: 0.0)} " +
                        "artist=${"%.2f".format(best?.artistEvidenceScore ?: 0.0)} " +
                        "duration=${"%.2f".format(best?.durationScore ?: 0.0)} " +
                        "album=${"%.2f".format(best?.albumScore ?: 0.0)} " +
                        "alt=${best?.unexpectedAlternates.orEmpty().joinToString("/")})",
                )
                return emptyList()
            }
            accepted.distinctBy { it.id }
        } else {
            // Legacy/plain queries without registered Spotify metadata: keep the
            // old best-effort ordering.
            val verifiedRanked = scored
                .filter { verified(it.first) }
                .sortedByDescending { it.second }
                .map { it.first }
            val restRanked = scored
                .filter { !verified(it.first) }
                .sortedByDescending { it.second }
                .map { it.first }
            (explicitFirst(verifiedRanked) + explicitFirst(restRanked))
                .filter(::isRequestedEdition)
                .distinctBy { it.id }
        }

        if (ordered.isEmpty()) return emptyList()
        val chosen = ordered.first()
        if (transferScored.isEmpty() && !verified(chosen)) {
            Log.w(TAG, "resolveVideoId: no verified match for: $searchText (want=${wantSec}s) — best-effort '${chosen.title}'")
        }
        val chosenScore = transferScored.firstOrNull { it.item.id == chosen.id }
        Log.d(
            TAG,
            "resolveVideoId: '$searchText' -> '${chosen.title}' by " +
                chosen.artists.joinToString { it.name } +
                " [explicit=${chosen.explicit} dur=${chosen.duration}s want=${wantSec}s id=${chosen.id}] " +
                (chosenScore?.let {
                    "score=${"%.2f".format(it.score)} title=${"%.2f".format(it.titleScore)} " +
                        "artist=${"%.2f".format(it.artistEvidenceScore)} duration=${"%.2f".format(it.durationScore ?: 0.0)} " +
                        "album=${"%.2f".format(it.albumScore ?: 0.0)} " +
                        "altPenalty=${"%.2f".format(it.alternatePenalty)} " +
                        "alt=${it.unexpectedAlternates.joinToString("/")}"
                } ?: "${ordered.count { verified(it) }} verified/${ordered.size}"),
        )
        return ordered.map { it.id }.distinct()
    }

    /**
     * Resolves a playable YouTube stream for [query], falling back through up to
     * 3 ranked video candidates when one has no obtainable stream.
     */
    internal suspend fun resolveYtPlayback(
        query: String, audioQuality: com.metrolist.music.constants.AudioQuality, appContext: Context,
        forPlayback: Boolean = true, backupOnly: Boolean = false,
    ): YTPlayerUtils.PlaybackData? = withContext(com.music.spotui.audio.PlaybackDiagnosticSong.context(diagnosticSong(query).ifBlank { com.music.spotui.audio.PlaybackDiagnosticSong.current() })) {
        resolveYtPlaybackInternal(query, audioQuality, appContext, forPlayback, backupOnly)
    }

    private suspend fun resolveYtPlaybackInternal(
        query: String,
        audioQuality: com.metrolist.music.constants.AudioQuality,
        appContext: Context,
        forPlayback: Boolean = true,
        backupOnly: Boolean = false,
    ): YTPlayerUtils.PlaybackData? {
        val backupEnabled = com.music.spotui.data.preferences.isPlaybackBackupEnabled(appContext)
        val candidates = linkedSetOf<String>()
        val forceBackup = backupOnly || (backupEnabled && com.music.spotui.audio.RejectedStreamRecovery.preferBackup(query))
        com.music.spotui.data.preferences.PlaybackDiagnostics.record(appContext, "Playback backup", if (backupEnabled) "Enabled" else "Disabled")
        return com.music.spotui.audio.StreamLookupFallback.resolve(
            primaryBudgetMs = if (backupEnabled) 22_000L else 45_000L,
            backupBudgetMs = 32_000L,
            backupEnabled = backupEnabled,
            primary = {
                resolveYtPlaybackUnbounded(query, audioQuality, appContext, forPlayback, candidates)
            },
            backup = {
                com.music.spotui.data.preferences.PlaybackDiagnostics.record(appContext, "Backup requested",
                    if (forceBackup) "Retry after stream failure" else "${candidates.size} primary candidates")
                val directId = searchTextForPlayback(query)
                if (directId.matches(Regex("[A-Za-z0-9_-]{11}"))) candidates.add(directId)
                val meta = metadataRegistry[query] ?: if (candidates.isEmpty()) ensureSpotifyMatchMetadata(query) else null
                val target = meta?.let { com.music.spotui.audio.BackupSearchPolicy.Target(it.title, it.artist,
                    durationRegistry[query] ?: 0, explicitRegistry[query]?.takeIf { it || spotifyMetadataRepaired.contains(query) }) }
                com.music.spotui.audio.YtDlpBackup.lookup(appContext, candidates.toList(), target, audioQuality, forPlayback)
            },
            forceBackup = forceBackup,
        ).also { result ->
            if (result == null && backupEnabled) com.music.spotui.data.preferences.PlaybackDiagnostics.record(appContext,
                "Backup exhausted", "No accepted audio or lookup deadline reached")
        }
    }

    private suspend fun resolveYtPlaybackUnbounded(
        query: String,
        audioQuality: com.metrolist.music.constants.AudioQuality,
        appContext: Context,
        forPlayback: Boolean,
        backupCandidates: MutableSet<String>,
    ): YTPlayerUtils.PlaybackData? {
        com.music.spotui.data.preferences.PlaybackDiagnostics.record(appContext,"YouTube search started",if (forPlayback) "Playback" else "Preload")
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val tried = mutableSetOf<String>()
        suspend fun tryIds(ids: List<String>): YTPlayerUtils.PlaybackData? {
            backupCandidates.addAll(ids)
            for (videoId in ids) {
                if (!tried.add(videoId)) continue
                // Allow the existing bounded proof (12s) and signature (8s)
                // initializers to finish; the overall 45s lookup budget still applies.
                (kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                    YTPlayerUtils.playerResponseForPlayback(
                        videoId = videoId,
                        audioQuality = audioQuality,
                        connectivityManager = connectivityManager,
                    )
                } ?: Result.failure(java.net.SocketTimeoutException())).fold(
                    onSuccess = { return it },
                    onFailure = {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        val detail = (if (forPlayback) "Playback: " else "Preload: ") + ((it as? com.music.spotui.audio.StreamCandidateException)?.summary ?: it.javaClass.simpleName)
                        com.music.spotui.data.preferences.PlaybackDiagnostics.record(appContext,"YouTube candidate failed",detail); Log.w(TAG, "stream failed for $videoId (${it.message}) — trying next candidate for: ${searchTextForPlayback(query)}") },
                )
            }
            return null
        }
        tryIds(resolveVideoCandidates(query).take(3))?.let { return it }
        if (!com.music.spotui.data.preferences.isVideoFallbackEnabled(appContext)) {
            Log.w(TAG, "song candidates exhausted and video fallback disabled for: ${searchTextForPlayback(query)}")
            return null
        }
        // Song results exhausted (e.g. every official upload is age-restricted and
        // we're not signed in to YouTube). Regular video uploads — lyric videos,
        // reuploads — usually aren't age-gated: last-resort pass over those.
        Log.w(TAG, "song candidates exhausted, trying video search for: ${searchTextForPlayback(query)}")
        tryIds(resolveVideoCandidates(query, YouTube.SearchFilter.FILTER_VIDEO).take(3))?.let { return it }
        Log.e(TAG, "All YouTube candidates failed for: ${searchTextForPlayback(query)}")
        return null
    }

    private fun buildAudioAttributes() =
        androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()

    /**
     * Build an ExoPlayer that reads through the shared media cache (so preloaded intro
     * bytes are reused) and carries its own [CrossfadeFilterAudioProcessor] so the DJ-style
     * low/high-pass sweep can be applied per track during a crossfade. The filter is
     * disabled (pass-through) outside a crossfade, so there's no overhead in normal playback.
     *
     * @param handleAudioFocus true for the active/session player, false for the transient
     *   secondary (incoming) player so it doesn't fight the primary for focus mid-fade.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun createPlayerWithFilter(
        context: Context,
        handleAudioFocus: Boolean,
    ): Pair<ExoPlayer, com.music.spotui.audio.CrossfadeFilterAudioProcessor> {
        val filter = com.music.spotui.audio.CrossfadeFilterAudioProcessor()
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(
                        androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain(
                            filter,
                        ),
                    ).build()
        }
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    // Routes deezer:// URIs to the decrypting DeezerDataSource and
                    // everything else through the normal cached HTTP stack.
                    com.music.spotui.deezer.DeezerAwareDataSourceFactory(cacheDataSourceFactory(context)),
                ),
            )
            .setRenderersFactory(renderers)
            .setAudioAttributes(buildAudioAttributes(), handleAudioFocus)
            .setHandleAudioBecomingNoisy(handleAudioFocus)
            .build()
        com.music.spotui.audio.PlaybackEqualizer.observe(context, p)
        return p to filter
    }

    private fun ensurePlayer(context: Context) {
        appCtx = context.applicationContext
        if (player == null) {
            val (p, filter) = createPlayerWithFilter(context, handleAudioFocus = true)
            player = p
            currentPlayerFilter = filter
            p.addListener(midPlayErrorListener)
            onPlayerCreated?.invoke(p)
        }
    }

    /**
     * Catches ExoPlayer errors that happen mid-stream (expired CDN URL, network drop,
     * HTTP 403/404, decode failure, etc.) — none of which were previously handled,
     * so the player would silently go idle.
     *
     * Drop failed URLs and hand errors to the same timed recovery as initial lookups.
     */
    /** Called for BOTH state and Play-intent events, including cold/restored starts. */
    private fun updateBufferingWatch() {
        val observedPlayer=player
        val decision=bufferingGate.observe(playbackVisit,observedPlayer?.playbackState==Player.STATE_BUFFERING,observedPlayer?.playWhenReady==true,System.identityHashCode(observedPlayer))
        when(decision) {
            com.music.spotui.audio.BufferingWatchPolicy.Decision.STOP -> {bufferingWatch?.cancel();bufferingWatch=null;return}
            com.music.spotui.audio.BufferingWatchPolicy.Decision.KEEP -> return
            com.music.spotui.audio.BufferingWatchPolicy.Decision.START -> Unit
        }
        bufferingWatch?.cancel()
        val request=currentRequest
        val visit=playbackVisit
        if(request.isBlank())return
        appCtx?.let { com.music.spotui.data.preferences.PlaybackDiagnostics.record(it,"Buffering watch armed",song=diagnosticSong(request)) }
        bufferingWatch=scope.launch(com.music.spotui.audio.PlaybackDiagnosticSong.context(diagnosticSong(request))) {
            delay(BUFFER_TIMEOUT_MS)
            val stalled=withContext(Dispatchers.Main) {
                player===observedPlayer && currentRequest==request && playbackVisit==visit &&
                    observedPlayer?.playbackState==Player.STATE_BUFFERING && observedPlayer.playWhenReady
            }
            if(!stalled)return@launch
            if(bufferingRecoveryFor==request) {
                withContext(Dispatchers.Main) {
                    if(player===observedPlayer && currentRequest==request && playbackVisit==visit)onStreamFailed?.invoke(request)
                }
                return@launch
            }
            bufferingRecoveryFor=request
            val ctx=appCtx ?: return@launch
            com.music.spotui.data.preferences.PlaybackDiagnostics.record(ctx,"Buffering recovery","Buffer deadline reached")
            val position=withContext(Dispatchers.Main) {
                requestBackupAfterRejectedStream(request, observedPlayer?.currentMediaItem?.localConfiguration?.uri?.toString(), ctx, "Returned stream stalled")
                com.music.spotui.audio.PlaybackClockPolicy.position(observedPlayer?.currentPosition ?: 0L,getDuration())
            }
            invalidateResolvedStream(request)
            val fresh=try {
                kotlinx.coroutines.withTimeoutOrNull(STARTUP_TIMEOUT_MS){resolveStreamUrl(request,ctx,forPlayback=true)}
            } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled
            } catch(error:Exception){
                com.music.spotui.data.preferences.PlaybackDiagnostics.record(ctx,"Buffering lookup failed",error.javaClass.simpleName)
                null
            }
            withContext(Dispatchers.Main) {
                if(player!==observedPlayer || currentRequest!=request || playbackVisit!=visit)return@withContext
                if(fresh==null)onStreamFailed?.invoke(request)
                else {
                    // Hand off to a fresh watch; state callbacks must not cancel this resolver.
                    bufferingWatch=null
                    bufferingGate.reset()
                    observedPlayer?.setMediaItem(buildMediaItem(fresh,streamMimeType(fresh)))
                    observedPlayer?.prepare()
                    if(position>0)observedPlayer?.seekTo(position)
                    observedPlayer?.playWhenReady=true
                    updateBufferingWatch()
                }
            }
        }
    }

    private val midPlayErrorListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            preparationAudibleMs = preparationClock.tick("$playbackVisit:$currentRequest",
                isPlaying && !webPlaybackActive(), android.os.SystemClock.elapsedRealtime())
            if(isPlaying)com.music.spotui.audio.PlaybackPresentation.set(com.music.spotui.audio.PlaybackPresentation.Stage.PLAYING)
            boundState?.updatePlayingState(isPlaying)
            if (isPlaying) appCtx?.let { com.music.spotui.data.preferences.savePlaybackActive(it, true) }
        }
        // Track end is detected here, not in the UI: Compose stops when the screen is
        // off or the app is in the background, so the queue used to stall there.
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val query = mediaItem?.mediaId ?: return
            if (query != preloadedNext) return
            // Gapless move onto the preloaded track: it is now the current request.
            preloadedNext = null
            currentRequest = query
            playbackVisit++
            trackEndGate.reset()
            val p = player ?: return
            if (p.currentMediaItemIndex > 0) p.removeMediaItems(0, p.currentMediaItemIndex)
            appCtx?.let { ctx ->
                scope.launch { resolveStreamUrl(query, ctx, forPlayback = true) } // badge
                soulseekLookahead(ctx)
            }
            onPreloadedTrackStarted?.invoke(query)
            updateBufferingWatch()
        }

        // Play/pause from the notification, headset or car goes straight to the player;
        // mirror it so the in-app button doesn't keep showing the old state.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ||
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)) {
                recoveryAllowed = false
                appCtx?.let { com.music.spotui.data.preferences.PlaybackDiagnostics.record(it, "Playback intent stopped",
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) "Noisy output" else "Audio focus", song=diagnosticSong(currentRequest)) }
                onPlaybackRequestChanged?.invoke()
            }
            if (playWhenReady) recoveryAllowed = true
            if (!isCrossfading) boundState?.updatePlayingState(playWhenReady)
            updateBufferingWatch()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            com.music.spotui.audio.PlaybackPresentation.set(when(playbackState) {
                Player.STATE_BUFFERING -> com.music.spotui.audio.PlaybackPresentation.Stage.BUFFERING
                Player.STATE_READY -> if(player?.playWhenReady == true)com.music.spotui.audio.PlaybackPresentation.Stage.PLAYING else com.music.spotui.audio.PlaybackPresentation.Stage.PAUSED
                else -> com.music.spotui.audio.PlaybackPresentation.state.value
            })
            if (playbackState == Player.STATE_READY) { appCtx?.let { com.music.spotui.data.preferences.PlaybackDiagnostics.record(it,"Player ready",song=diagnosticSong(player?.currentMediaItem?.mediaId ?: currentRequest)) }; bufferingRecoveryFor = null; trackEndGate.reset() }
            updateBufferingWatch()
            // Mid-crossfade the outgoing player may end first; the blend advances itself.
            // An emptied playlist also reports ENDED (playSong clears it on every tap);
            // treating that as a finished song skipped the track the user just picked.
            if (playbackState == Player.STATE_ENDED) notifyTrackEndOnce()
        }

        override fun onPlayerError(error: PlaybackException) {
            // A queued-ahead track can fail after the player moved onto it.
            val song = player?.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
                ?: currentRequest.takeIf { it.isNotBlank() } ?: return
            preloadedNext = null
            Log.w(TAG, "mid-play error for $song: ${error.errorCodeName} (code ${error.errorCode})")
            appCtx?.let { ctx -> requestBackupAfterRejectedStream(song, player?.currentMediaItem?.localConfiguration?.uri?.toString(), ctx, "Player rejected returned stream") }
            // Drop the cached URL — it may have expired (Deezer CDN, YouTube signing).
            streamCache.remove(song)
            sourceCache.remove(song)
            val ctx = appCtx ?: run { onStreamFailed?.invoke(song); return }
            com.music.spotui.data.preferences.PlaybackDiagnostics.record(ctx,"Player error",error.errorCodeName,song=diagnosticSong(song))
            // Use the same timed failure policy as initial lookup failures.
            // Do not run a second resolver behind the service's backoff timer.
            bufferingWatch?.cancel(); bufferingWatch = null
            val failureVisit = playbackVisit
            scope.launch(Dispatchers.Main) {
                if (currentRequest == song && playbackVisit == failureVisit) onStreamFailed?.invoke(song)
            }
        }
    }

    private fun requestBackupAfterRejectedStream(song: String, uri: String?, context: Context, reason: String) {
        if (!com.music.spotui.data.preferences.isPlaybackBackupEnabled(context)) return
        val source = sourceCache[song] ?: currentSource.takeIf { song == currentRequest }.orEmpty()
        if (!source.contains("YouTube", ignoreCase = true) || (uri != null && com.music.spotui.audio.StreamRequestHeaders.get(uri) != null)) return
        com.music.spotui.audio.RejectedStreamRecovery.failed(song)
        com.music.spotui.data.preferences.PlaybackDiagnostics.record(context, "Backup recovery armed", reason,song=diagnosticSong(song))
    }

    /** The live ExoPlayer instance (may be null before first play). */
    val exoPlayer: ExoPlayer? get() = player

    /** Make sure the player exists (used by the media-session service). */
    fun ensureCreated(context: Context) = ensurePlayer(context.applicationContext)

    /** Notified right after the ExoPlayer is built so the session can attach to it. */
    @Volatile var onPlayerCreated: ((ExoPlayer) -> Unit)? = null

    fun isPlaying(): Boolean {
        if (webPlaybackActive()) return SpotifyWebPlayer.isPlaying
        return player?.isPlaying ?: false
    }
    fun activeSongId(): Int? = boundState?.songId?.value
    fun isResolvingAudio(): Boolean = playbackResolveJob?.isActive == true || bufferingWatch?.isActive == true

    fun webPlaybackActive(): Boolean {
        if (!webPlayerEnabled) return false
        val ctx = appCtx ?: return false
        // Spotify web playback needs: user hasn't opted out, the WebView actually has
        // Widevine, AND the user is logged into Spotify (sp_dc). Missing any of these
        // → fall back to the YouTube/FLAC engine so playback is never silent.
        return com.music.spotui.data.preferences.isWebPlaybackEnabled(ctx) &&
            SpotifyWebPlayer.canPlay &&
            com.music.spotui.data.api.SpotifySession.spDc(ctx).isNotBlank()
    }

    // ── Session restore (survive app restarts) ──
    // Set at launch from the persisted playback state; the first playSong for
    // this query seeks to the saved position, and play() with an empty player
    // re-resolves the track instead of doing nothing.
    @Volatile private var restoreQuery: String? = null
    @Volatile private var restorePositionMs: Long = 0L

    fun setRestorePoint(query: String, positionMs: Long) {
        if (query.isBlank()) return
        restoreQuery = query
        restorePositionMs = com.music.spotui.audio.PlaybackClockPolicy.position(positionMs, 0L)
    }

    fun play() {
        recoveryAllowed = true
        onPlaybackRequestChanged?.invoke()
        if (webPlaybackActive()) { SpotifyWebPlayer.resume(); return }
        // Fresh launch: nothing loaded yet — resume the restored session track.
        if ((player?.mediaItemCount ?: 0) == 0 || player?.playbackState == Player.STATE_IDLE) {
            val q = restoreQuery ?: boundState?.queue?.value?.firstOrNull { it.id == boundState?.songId?.value }?.url
            val ctx = appCtx
            // A truck may send Play several times while the network reconnects.
            // Keep the existing lookup instead of cancelling and starting it over.
            if (q == currentRequest && playbackResolveJob?.isActive == true) return
            if (q != null && ctx != null) { playSong(q, ctx); return }
        }
        player?.play()
    }

    fun pause() {
        com.music.spotui.audio.PlaybackPresentation.set(com.music.spotui.audio.PlaybackPresentation.Stage.PAUSED)
        recoveryAllowed = false
        onPlaybackRequestChanged?.invoke()
        // Pause must also stop an unresolved Play request, otherwise audio could
        // start later after the user already paused or disconnected the truck.
        if (playbackResolveJob?.isActive == true) {
            playbackResolveJob?.cancel(); playbackResolveJob = null
            playbackVisit++
        }
        bufferingWatch?.cancel()
        appCtx?.let { com.music.spotui.data.preferences.savePlaybackActive(it, false) }
        boundState?.updatePlayingState(false)
        cancelCrossfade()
        if (webPlaybackActive()) { SpotifyWebPlayer.pause(); return }
        player?.let {
            it.playWhenReady = false
            // Remember where we stopped so a relaunch can resume mid-track.
            appCtx?.let { ctx ->
                val pos = it.currentPosition
                if (pos > 0) com.music.spotui.data.preferences.saveLastPosition(ctx, pos)
            }
        }
    }

    fun stop() {
        recoveryAllowed = false
        onPlaybackRequestChanged?.invoke()
        playbackResolveJob?.cancel(); playbackResolveJob = null
        bufferingWatch?.cancel()
        playbackVisit++
        appCtx?.let { com.music.spotui.data.preferences.savePlaybackActive(it, false) }
        boundState?.updatePlayingState(false)
        cancelCrossfade()
        player?.stop()
    }

    /** Queue-only changes discard the resolved next item without touching current audio. */
    fun hasEnded(): Boolean = player?.playbackState == Player.STATE_ENDED && (player?.mediaItemCount ?: 0) > 0

    fun invalidateUpcomingPreparation() {
        preparationJob?.cancel(); preparationJob = null; preparationKey = null; warmedNext = null
        cancelCrossfade()
        player?.let { p -> if (p.mediaItemCount > p.currentMediaItemIndex + 1) p.removeMediaItems(p.currentMediaItemIndex + 1, p.mediaItemCount) }
        preloadedNext = null
    }

    fun seekTo(position: Long) {
        cancelCrossfade()
        if (webPlaybackActive()) { SpotifyWebPlayer.seekTo(position); return }
        player?.seekTo(position)
    }

    fun release() {
        recoveryAllowed = false
        onPlaybackRequestChanged?.invoke()
        playbackResolveJob?.cancel(); playbackResolveJob = null
        currentRequest = ""
        bufferingWatch?.cancel()
        positionWatchJob?.cancel()
        preparationJob?.cancel()
        preparationJob = null
        cancelCrossfade()
        com.music.spotui.audio.PlaybackEqualizer.release(player)
        player?.release()
        player = null
        currentSource = ""
        currentQuality = ""
        boundState?.updatePlayingState(false)
    }

    fun getDuration(): Long {
        if (webPlaybackActive()) return com.music.spotui.audio.PlaybackClockPolicy.duration(SpotifyWebPlayer.durationMs)
        return com.music.spotui.audio.PlaybackClockPolicy.duration(player?.duration ?: 0L)
    }

    fun getCurrentPosition(): Long {
        if (webPlaybackActive()) return com.music.spotui.audio.PlaybackClockPolicy.position(SpotifyWebPlayer.positionMs, getDuration())
        return com.music.spotui.audio.PlaybackClockPolicy.position(player?.currentPosition ?: 0L, getDuration())
    }

    fun isPrepared(): Boolean {
        val playerState = player?.playbackState
        return playerState != null && playerState != ExoPlayer.STATE_IDLE && playerState != ExoPlayer.STATE_ENDED
    }

    // ── Crossfade + DJ-style mixing ──
    // The end of the current track is blended into the start of the next over a user-set
    // window (Settings). A second, transient ExoPlayer plays the incoming track while the
    // primary fades out; volumes follow an equal-power (cos/sin) curve so total loudness
    // stays constant. In DJ mode, the outgoing track is low-passed (treble drops out) and the
    // incoming track high-passed (bass fills in) via per-player [CrossfadeFilterAudioProcessor]s,
    // swept on an S-curve — like a real DJ mixer. When the blend finishes the secondary player
    // is promoted to primary and the media session is re-bound to it via [onPlayerSwapped].
    private const val CF_LPF_START_HZ = 20000f
    private const val CF_LPF_END_HZ = 200f
    private const val CF_HPF_START_HZ = 2000f
    private const val CF_HPF_END_HZ = 20f
    private const val CF_SIGMOID_K = 6f

    @Volatile private var appCtx: Context? = null
    @Volatile private var boundState: CurrentSongState? = null
    @Volatile private var currentPlayerFilter: com.music.spotui.audio.CrossfadeFilterAudioProcessor? = null
    @Volatile private var secondaryPlayer: ExoPlayer? = null
    @Volatile private var secondaryPlayerFilter: com.music.spotui.audio.CrossfadeFilterAudioProcessor? = null
    @Volatile private var isCrossfading = false
    @Volatile private var crossfadeJob: kotlinx.coroutines.Job? = null
    @Volatile private var positionWatchJob: kotlinx.coroutines.Job? = null
    @Volatile private var pendingNextSong: com.music.spotui.data.entity.SongsModel? = null
    @Volatile private var pendingNextSongIdx: Int = 0

    /** Notified (on the main thread) when the active ExoPlayer instance changes after a
     *  crossfade, so the media session can re-bind to the promoted player. */
    @Volatile var onPlayerSwapped: ((ExoPlayer) -> Unit)? = null

    /** Give the player access to the shared queue/now-playing state so it can advance the
     *  app's notion of "current track" itself when a crossfade fires. Called once at startup. */
    fun bindState(state: CurrentSongState) { boundState = state }

    fun isCrossfadeActive(): Boolean = isCrossfading

    private fun sigmoid(t: Float): Float = 1.0f / (1.0f + exp(-CF_SIGMOID_K * (t - 0.5f)))

    private fun expInterpolate(start: Float, end: Float, t: Float): Float {
        if (start <= 0f || end <= 0f) return end
        return exp(ln(start) + (ln(end) - ln(start)) * t).toFloat()
    }

    /** Cancel an in-flight crossfade and tear down the secondary player, restoring the
     *  primary to full volume with its filter disabled. Safe to call when not crossfading. */
    private var crossfadeAttempt = 0L
    private fun cancelCrossfade() {
        crossfadeAttempt++
        if (!isCrossfading && secondaryPlayer == null) return
        crossfadeJob?.cancel()
        crossfadeJob = null
        currentPlayerFilter?.enabled = false
        secondaryPlayerFilter?.enabled = false
        runCatching { com.music.spotui.audio.PlaybackEqualizer.release(secondaryPlayer); secondaryPlayer?.release() }
        secondaryPlayer = null
        secondaryPlayerFilter = null
        player?.volume = 1f
        isCrossfading = false
        pendingNextSong = null
    }

    /** (Re)start the loop that watches playback position and fires a crossfade as the
     *  current track approaches its end. */
    private var posSaveTick = 0
    private val listeningProcess = java.util.UUID.randomUUID().toString()
    private var playbackVisit = 0L
    @Volatile var onRadioQualified: ((Boolean) -> Unit)? = null
    private val listeningClock = com.music.spotui.audio.ListeningClock()
    private val collectionListeningClock = com.music.spotui.audio.ListeningClock()
    @Volatile private var listeningVisit = -1L
    fun recordManualSkip(context: Context) {
        val state=boundState ?: return
        val song=state.queue.value.firstOrNull{it.id==state.songId.value} ?: return
        val heard=if(listeningVisit==playbackVisit)listeningClock.audibleMs else 0L
        val visit="$listeningProcess:$playbackVisit"
        scope.launch { com.music.spotui.data.preferences.RadioSkipMemory.skipped(context.applicationContext,song,heard,visit) }
    }
    @Volatile var recoveryAllowed: Boolean = true
        private set
    @Volatile var onPlaybackRequestChanged: (() -> Unit)? = null
    @Volatile var onRetryRequested: (() -> Unit)? = null
    fun retryCurrent(context: Context) {
        val query=currentRequest.takeIf{it.isNotBlank()} ?: boundState?.queue?.value?.firstOrNull{it.id==boundState?.songId?.value}?.url ?: return
        com.music.spotui.data.preferences.PlaybackDiagnostics.record(context,"Manual retry",song=diagnosticSong(query))
        onRetryRequested?.invoke()
        setRestorePoint(query,getCurrentPosition())
        invalidateResolvedStream(query)
        playSong(query,context)
    }
    private var historySaveKey = ""
    private var historySavedMs = 0L
    private val preparationClock = com.music.spotui.audio.PreparationClock()
    private var preparationAudibleMs = 0L
    private var recoveredVisit = -1L

    /** A new collection can start around an already-qualified song without restarting its stream. */
    private fun recordCollectionListening(ctx: Context, state: CurrentSongState, track: com.music.spotui.data.entity.SongsModel?,
                                          visit: String, audible: Boolean, nowMs: Long) {
        val item = state.collection.value
        val station = state.activeRadioSeed?.takeIf { item?.type == "radio" && item.id == it }
        val qualified = collectionListeningClock.tick("$visit:${item?.key.orEmpty()}",
            audible && item != null && track != null, nowMs)
        if (qualified == null || item == null || track == null) return
        scope.launch {
            try {
                com.music.spotui.data.preferences.HomeFrequency.record(ctx, item.type, item.id, item.name, item.image, item.subtitle)
                if (station != null) {
                    val rotated = com.music.spotui.data.preferences.RadioSessions.qualified(ctx, station, track, "$visit:${item.key}")
                    withContext(Dispatchers.Main) {
                        if (boundState === state && state.activeRadioSeed == station) onRadioQualified?.invoke(rotated)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Collection listening activity could not be saved", error)
            }
        }
    }

    private fun startPositionWatch() {
        positionWatchJob?.cancel()
        positionWatchJob = scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(250)
                val ctx = appCtx ?: continue
                withContext(Dispatchers.Main) {
                    notifyTrackEndOnce()
                    val state = boundState
                    val currentPlayer = player
                    preparationAudibleMs = preparationClock.tick("$playbackVisit:$currentRequest",
                        currentPlayer?.isPlaying == true && !webPlaybackActive(), android.os.SystemClock.elapsedRealtime())
                    if (preparationAudibleMs >= 2_000L && currentPlayer?.isPlaying == true && recoveredVisit != playbackVisit) {
                        recoveredVisit = playbackVisit
                        com.music.spotui.audio.RejectedStreamRecovery.recovered(currentRequest)
                        onPlaybackRecovered?.invoke()
                    }
                    if (state != null && currentPlayer != null && !webPlaybackActive()) {
                        val queue = state.queue.value
                        val index = queue.indexOfFirst { it.id == state.songId.value }
                        val tailKey = "${state.generation}:$currentRequest"
                        if (index >= 0 && index == queue.lastIndex && tailNotified != tailKey &&
                            com.music.spotui.audio.NextTrackPreparationPolicy.shouldPrepare(currentPlayer.duration,
                                currentPlayer.currentPosition, currentPlayer.isPlaying, state.repeat.value, 0)) {
                            onQueueTailApproaching?.let { callback -> tailNotified = tailKey; callback() }
                        }
                    }
                    val track = state?.queue?.value?.firstOrNull { it.id == state.songId.value }
                    if (state != null) recordCollectionListening(ctx, state, track,
                        "$listeningProcess:$playbackVisit:${track?.id}", isPlaying(), android.os.SystemClock.elapsedRealtime())
                    if (state != null && track != null) {
                        val historyKey = "$listeningProcess:$playbackVisit:${track.id}"
                        listeningVisit=playbackVisit
                        val qualified = listeningClock.tick(historyKey, isPlaying(), android.os.SystemClock.elapsedRealtime())
                        val listened = listeningClock.audibleMs
                        if (qualified != null || (historySaveKey == historyKey && listened - historySavedMs >= 15_000L)) {
                            val historyEntry = com.music.spotui.data.preferences.HistoryEntry(System.currentTimeMillis(), track.id, track.title,
                                    track.singer, track.album, track.coverUri, track.url, track.spotifyTrackId, listenedMs = listened,
                                    durationMs = track.durationMs, visitId = historyKey)
                            scope.launch {
                                runCatching { com.music.spotui.data.preferences.addListeningHistory(ctx, historyEntry) }
                                    .onFailure { Log.w(TAG, "Listening progress could not be saved", it) }
                            }
                            historySaveKey = historyKey; historySavedMs = listened
                            if (qualified != null) {
                            scope.launch { com.music.spotui.data.preferences.RadioSkipMemory.listened(ctx,track) }
                            }
                        }
                    }
                }
                // Persist the position every ~3s so a relaunch resumes mid-track.
                if (++posSaveTick % 12 == 0 && !webPlaybackActive()) {
                    player?.let { p ->
                        val pos = withContext(Dispatchers.Main) {
                            if (p.isPlaying) p.currentPosition else -1L
                        }
                        if (pos > 0) com.music.spotui.data.preferences.saveLastPosition(ctx, pos)
                    }
                }
                if (isCrossfading) continue
                val crossfadeMs = com.music.spotui.data.preferences.getCrossfadeMs(ctx)
                managePreload(ctx, crossfadeMs)
                if (crossfadeMs <= 0) continue
                val state = boundState ?: continue
                if (state.repeat.value) continue // repeat-one loops the same track
                val p = player ?: continue
                val playing = withContext(Dispatchers.Main) { p.isPlaying }
                if (!playing) continue
                val dur = withContext(Dispatchers.Main) { p.duration }
                val pos = withContext(Dispatchers.Main) { p.currentPosition }
                if (dur <= 0 || pos < 0) continue
                if (pos >= dur - crossfadeMs) {
                    triggerCrossfade(ctx, crossfadeMs)
                }
            }
        }
    }

    /** Resolve the actual next queue item before the end, without blocking the playback watchdog. */
    private suspend fun managePreload(ctx: Context, crossfadeMs: Int) {
        val state = boundState ?: return
        val p = player ?: return
        val request = currentRequest
        val generation = state.generation
        val next = withContext(Dispatchers.Main) {
            val q = state.queue.value
            val cur = q.indexOfFirst { it.id == state.songId.value }
            q.getOrNull(cur + 1)?.takeIf { cur >= 0 && !state.repeat.value && !webPlaybackActive() }
        }
        val queued = preloadedNext
        if (queued != null && (queued != next?.url || crossfadeMs > 0)) {
            withContext(Dispatchers.Main) {
                if (player === p && p.mediaItemCount > 1) p.removeMediaItems(1, p.mediaItemCount)
            }
            preloadedNext = null
        }
        if (next == null) {
            preparationJob?.cancel(); preparationJob = null; preparationKey = null; warmedNext = null
            return
        }
        val key = "$generation:$request:${next.url}:${crossfadeMs > 0}:${System.identityHashCode(p)}"
        if (preparationKey != key) {
            preparationJob?.cancel(); preparationJob = null; warmedNext = null
            preparationKey = key; preparationBackoff.reset()
        }
        if (preloadedNext == next.url || warmedNext == key || preparationJob?.isActive == true) return
        val eligible = withContext(Dispatchers.Main) {
            p.playbackState == Player.STATE_READY && p.mediaItemCount == 1 &&
                (com.music.spotui.audio.NextTrackPreparationPolicy.shouldPrepareEarly(preparationAudibleMs, p.isPlaying, state.repeat.value) ||
                    com.music.spotui.audio.NextTrackPreparationPolicy.shouldPrepare(p.duration, p.currentPosition,
                        p.isPlaying, state.repeat.value, crossfadeMs))
        }
        if (!eligible) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!preparationBackoff.ready(now)) return
        preparationJob = scope.launch(com.music.spotui.audio.PlaybackDiagnosticSong.context(diagnosticSong(next.url))) {
            com.music.spotui.data.preferences.PlaybackDiagnostics.record(ctx,"Next song preparation started","Queue lookahead after 2 seconds")
            try {
                val url = kotlinx.coroutines.withTimeoutOrNull(55_000L) {
                    resolveStreamUrl(next.url, ctx)
                } ?: run {
                    com.music.spotui.data.preferences.PlaybackDiagnostics.record(ctx,"Next song preparation failed","Will retry in background")
                    return@launch
                }
                val valid = withContext(Dispatchers.Main) {
                    val q = state.queue.value
                    val currentIndex = q.indexOfFirst { it.id == state.songId.value }
                    val actualNext = q.getOrNull(currentIndex + 1)?.url?.takeIf { currentIndex >= 0 }
                    val sameSession = player === p && currentRequest == request && generation == state.generation &&
                        preparationKey == key && p.playbackState == Player.STATE_READY && !state.repeat.value && !webPlaybackActive()
                    if (!com.music.spotui.audio.NextTrackPreparationPolicy.stillNext(next.url, actualNext, sameSession)) return@withContext false
                    if (crossfadeMs <= 0 && p.mediaItemCount == 1) {
                        val mime = streamMimeType(url, sourceCache[next.url].orEmpty())
                        p.addMediaItem(buildMediaItem(url, mime, next.url, next.title, next.singer, next.coverUri))
                        preloadedNext = next.url
                    }
                    warmedNext = key
                    true
                }
                // Only reusable YouTube streams get an intro cached. Single-use/lossless
                // providers are handed directly to the player's existing data source.
                if (valid) com.music.spotui.data.preferences.PlaybackDiagnostics.record(ctx,"Next song prepared","Ready for queue transition")
                if (valid && sourceCache[next.url] == "YouTube") cacheIntro(url, ctx)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.d(TAG, "next-track preparation failed: ${error.message}")
            } finally {
                if (isActive && preparationKey == key) {
                    if (warmedNext == key) preparationBackoff.reset()
                    else preparationBackoff.failed(android.os.SystemClock.elapsedRealtime())
                }
            }
        }
    }

    /** Begin blending the current track into the next queue item. */
    // Player callbacks and the position watchdog can observe the same end event.
    private val trackEndGate = com.music.spotui.audio.TrackEndGate()
    private fun notifyTrackEndOnce() {
        val p = player ?: return
        val callback = onTrackEnded ?: return
        if (!trackEndGate.take(p.playbackState == Player.STATE_ENDED, p.mediaItemCount > 0, isCrossfading)) return
        callback()
    }

    private fun triggerCrossfade(ctx: Context, configuredMs: Int) {
        if (isCrossfading) return
        val state = boundState ?: return
        val q = state.queue.value
        if (q.isEmpty()) return
        val cur = q.indexOfFirst { it.id == state.songId.value }
        if (cur < 0 || cur >= q.size - 1) return // last track ends normally
        val nextSong = q[cur + 1]
        val outgoing = player
        val request = currentRequest
        val generation = state.generation
        val attempt = ++crossfadeAttempt
        fun ownsAttempt() = attempt == crossfadeAttempt
        fun stillCurrent() = ownsAttempt() && player === outgoing && currentRequest == request && state.generation == generation
        isCrossfading = true
        scope.launch {
            try {
                val nextUrl = kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    resolveStreamUrl(nextSong.url, ctx, forPlayback = true)
                }
                withContext(Dispatchers.Main) {
                    if (ownsAttempt() && (nextUrl == null || !stillCurrent())) { cancelCrossfade(); notifyTrackEndOnce() }
                }
                if (nextUrl == null || !stillCurrent()) return@launch
                // Effective duration: never longer than the real time left on the outgoing track.
                val remaining = withContext(Dispatchers.Main) {
                    val p = player ?: return@withContext configuredMs.toLong()
                    val d = p.duration; val ps = p.currentPosition
                    if (d > 0 && ps >= 0) (d - ps) else configuredMs.toLong()
                }
                val effectiveMs = minOf(configuredMs.toLong(), remaining).coerceAtLeast(1000L).toInt()
                val djMode = com.music.spotui.data.preferences.isCrossfadeDjMode(ctx)

                withContext(Dispatchers.Main) {
                    if (!stillCurrent()) return@withContext
                    pendingNextSong = nextSong
                    pendingNextSongIdx = cur + 1
                    setNowPlayingMeta(nextSong.title, nextSong.singer, nextSong.coverUri)
                    val (sp, sf) = createPlayerWithFilter(ctx, handleAudioFocus = false)
                    secondaryPlayer = sp
                    secondaryPlayerFilter = sf
                    sp.setMediaItem(buildMediaItem(nextUrl, streamMimeType(nextUrl)))
                    sp.prepare()
                    sp.volume = 0f
                    sp.playWhenReady = true
                }
                if (stillCurrent()) performCrossfade(effectiveMs, djMode)
            } catch (e: Exception) {
                Log.e(TAG, "crossfade failed", e)
                withContext(Dispatchers.Main) {
                    if (ownsAttempt()) { cancelCrossfade(); notifyTrackEndOnce() }
                }
            }
        }
    }

    private suspend fun performCrossfade(effectiveMs: Int, djMode: Boolean) {
        val steps = 50
        val delayPerStep = (effectiveMs / steps).coerceAtLeast(20)
        if (djMode) {
            currentPlayerFilter?.apply {
                filterType = com.music.spotui.audio.BiquadFilter.FilterType.LOW_PASS
                cutoffFrequencyHz = CF_LPF_START_HZ; enabled = true
            }
            secondaryPlayerFilter?.apply {
                filterType = com.music.spotui.audio.BiquadFilter.FilterType.HIGH_PASS
                cutoffFrequencyHz = CF_HPF_START_HZ; enabled = true
            }
        }
        crossfadeJob?.cancel()
        val job = scope.launch {
            try {
                for (step in 0..steps) {
                    if (!isActive) break
                    val progress = step.toFloat() / steps
                    val angle = (progress * PI / 2).toFloat()
                    withContext(Dispatchers.Main) {
                        player?.volume = cos(angle)
                        secondaryPlayer?.volume = sin(angle)
                        if (djMode) {
                            val fp = sigmoid(progress)
                            currentPlayerFilter?.cutoffFrequencyHz = expInterpolate(CF_LPF_START_HZ, CF_LPF_END_HZ, fp)
                            secondaryPlayerFilter?.cutoffFrequencyHz = expInterpolate(CF_HPF_START_HZ, CF_HPF_END_HZ, fp)
                        }
                    }
                    kotlinx.coroutines.delay(delayPerStep.toLong())
                }
                finalizeCrossfade()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
        }
        crossfadeJob = job
        job.join()
    }

    private suspend fun finalizeCrossfade() {
        withContext(Dispatchers.Main) {
            val incoming = secondaryPlayer ?: run { isCrossfading = false; return@withContext }
            val old = player
            // Promote the incoming (secondary) player to primary.
            currentPlayerFilter?.enabled = false
            secondaryPlayerFilter?.enabled = false
            player = incoming
            currentPlayerFilter = secondaryPlayerFilter
            secondaryPlayer = null
            secondaryPlayerFilter = null
            incoming.volume = 1f
            // The promoted player now owns audio focus / becoming-noisy handling.
            incoming.setAudioAttributes(buildAudioAttributes(), /* handleAudioFocus = */ true)
            incoming.setHandleAudioBecomingNoisy(true)
            // Error recovery and end-of-track detection follow the promoted player.
            incoming.addListener(midPlayErrorListener)
            runCatching { old?.stop(); com.music.spotui.audio.PlaybackEqualizer.release(old); old?.release() }
            isCrossfading = false
            // Now that the new track is the sole audio source, switch the in-app UI
            // (cover art, canvas, title) and update the playback identity for error recovery.
            pendingNextSong?.let { next ->
                currentRequest = next.url
                playbackVisit++
                trackEndGate.reset()
                boundState?.updateSongState(
                    next.coverUri, next.title, next.singer, true,
                    next.id, pendingNextSongIdx, next.album,
                )
                pendingNextSong = null
            }
            // Re-bind the media session to the new player.
            onPlayerSwapped?.invoke(incoming)
        }
        // Watch the newly-promoted track for its own end.
        startPositionWatch()
    }

    // ── Sleep timer ──
    // Pauses playback after a delay. A new call replaces any pending timer;
    // passing 0 (or calling cancelSleepTimer) clears it.
    @Volatile private var sleepJob: kotlinx.coroutines.Job? = null
    @Volatile var sleepTimerEndAt: Long = 0L
        private set

    fun setSleepTimer(durationMillis: Long) {
        sleepJob?.cancel()
        if (durationMillis <= 0L) {
            sleepTimerEndAt = 0L
            return
        }
        sleepTimerEndAt = System.currentTimeMillis() + durationMillis
        sleepJob = scope.launch {
            kotlinx.coroutines.delay(durationMillis)
            withContext(Dispatchers.Main) { pause() }
            sleepTimerEndAt = 0L
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        sleepTimerEndAt = 0L
    }
}
