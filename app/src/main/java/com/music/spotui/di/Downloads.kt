package com.music.spotui.di

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Offline downloads: resolves a track's best source and saves it under filesDir/downloads. */
object Downloads {
    private const val TAG = "Downloads"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Tracks which song queries are mid-download so the UI can show a spinner and
    // we don't kick off the same download twice.
    private val downloading = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    @Volatile var onDownloadsChanged: (() -> Unit)? = null

    // Per-query download progress, 0..100. Present only while a download is active.
    private val downloadProgress = java.util.concurrent.ConcurrentHashMap<String, Int>()
    // The actual SongsModel of each in-progress download, so the Downloads screen can
    // render it (with a progress bar) before the file exists / it's added to prefs.
    private val downloadingSongs =
        java.util.concurrent.ConcurrentHashMap<String, com.music.spotui.data.entity.SongsModel>()

    fun isDownloading(query: String): Boolean = downloading.contains(query)

    /** Current download progress (0..100) for a query, or -1 if unknown/not downloading. */
    fun downloadProgress(query: String): Int = downloadProgress[query] ?: -1

    /** Snapshot of the currently-downloading tracks paired with their percent (0..100). */
    fun downloadingSnapshot(): List<Pair<com.music.spotui.data.entity.SongsModel, Int>> =
        downloadingSongs.entries.map { (q, song) -> song to (downloadProgress[q] ?: 0) }

    // Last download failure reason, surfaced to the user as a Toast for diagnosis.
    @Volatile var lastDownloadError: String? = null

    // googlevideo stream URLs 403 without a browser User-Agent and need redirects
    // followed (http↔https) — ExoPlayer does both, so a raw URLConnection must too.
    private fun openDownloadConn(url: String): java.net.HttpURLConnection =
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
            val headers = com.music.spotui.audio.StreamRequestHeaders.get(url)
                ?: if (url.contains("googlevideo.com")) com.metrolist.innertube.models.YouTubeClient.forStreamUrl(url).mediaHeaders() else emptyMap()
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }

    /**
     * Download [url] to [tmpFile] using HTTP **Range** requests in chunks, reporting
     * progress (0..100) for [query]. A single full-file GET of a googlevideo stream gets
     * reset partway through (`SocketException: Connection reset`) — the server expects the
     * audio fetched in byte ranges, which is how ExoPlayer/NewPipe get it. Each chunk is a
     * short connection (retried a few times on reset); writing is append-continuous so a
     * retried chunk resumes from the current byte position. Returns true iff the whole
     * file was written. Falls back gracefully if the server ignores Range (HTTP 200).
     */
    private fun httpDownloadRanged(url: String, tmpFile: java.io.File, query: String): Boolean {
        val chunk = 8L * 1024 * 1024 // 8 MB
        var total = -1L
        var position = 0L
        downloadProgress[query] = 0
        try {
            java.io.BufferedOutputStream(tmpFile.outputStream()).use { output ->
                outer@ while (true) {
                    val end = if (total > 0) minOf(position + chunk - 1, total - 1) else position + chunk - 1
                    var attempt = 0
                    var fullBody = false
                    while (true) {
                        attempt++
                        val conn = openDownloadConn(url)
                        conn.setRequestProperty("Range", "bytes=$position-$end")
                        try {
                            val code = conn.responseCode
                            if (code !in 200..299) {
                                lastDownloadError = "Stream returned HTTP $code"
                                return false
                            }
                            if (total < 0) {
                                total = conn.getHeaderField("Content-Range")
                                    ?.substringAfter('/')?.toLongOrNull()
                                    ?: conn.contentLengthLong
                            }
                            fullBody = code == 200 // server ignored Range → whole file in one body
                            conn.inputStream.use { input ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val r = input.read(buf)
                                    if (r < 0) break
                                    output.write(buf, 0, r)
                                    position += r
                                    if (total > 0) {
                                        val pct = ((position * 100) / total).toInt().coerceIn(0, 100)
                                        if (downloadProgress[query] != pct) {
                                            downloadProgress[query] = pct
                                            onDownloadsChanged?.invoke()
                                        }
                                    }
                                }
                            }
                            break // this chunk completed
                        } catch (e: Exception) {
                            Log.w(TAG, "chunk @${position} failed (attempt $attempt): ${e.message}")
                            if (attempt >= 4) {
                                lastDownloadError = e.message ?: "Connection reset"
                                return false
                            }
                            // retry the remainder of this chunk from the current position
                        } finally {
                            conn.disconnect()
                        }
                    }
                    if (fullBody) { total = position; break@outer }
                    if (total in 1..position) break@outer
                    if (total < 0) break@outer // couldn't determine size; assume done
                }
            }
            downloadProgress[query] = 100
            return total <= 0 || position >= total
        } catch (e: Exception) {
            lastDownloadError = e.message ?: "Download error"
            return false
        }
    }

    /** Download every track in a list (album/playlist). Each song dedupes and
     *  reports its own progress via the existing per-song machinery. */
    fun downloadAll(songs: List<com.music.spotui.data.entity.SongsModel>, context: Context) {
        songs.forEach { downloadSong(it, context) }
    }

    private val jobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * Stop every pending download. Tracks still waiting for a slot stop at once; the
     * few already transferring finish (blocking reads don't see cancellation).
     */
    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        onDownloadsChanged?.invoke()
    }

    // A whole playlist at once floods the sources with lookups and gets rate limited.
    private val downloadSlots = Semaphore(3)

    /** True once every track in [songs] is downloaded (for the album's "downloaded" state). */
    fun allDownloaded(
        songs: List<com.music.spotui.data.entity.SongsModel>,
        context: Context,
    ): Boolean {
        if (songs.isEmpty()) return false
        val appContext = context.applicationContext
        return songs.all {
            com.music.spotui.data.preferences.isDownloaded(appContext, it.id.toString())
        }
    }

    /**
     * Resolve the track's stream and save the audio to local storage for offline
     * playback. Runs on the IO scope; invokes [onComplete] (main thread) with whether
     * it succeeded. No-op if it's already downloaded or downloading.
     */
    fun downloadSong(
        song: com.music.spotui.data.entity.SongsModel,
        context: Context,
        automatic: Boolean = false,
        onComplete: (Boolean) -> Unit = {},
    ) {
        val appContext = context.applicationContext
        if (!automatic) com.music.spotui.data.preferences.SmartDownloads.markManual(appContext, song.id.toString())
        val query = song.url
        if (query.isBlank() ||
            com.music.spotui.data.preferences.isDownloaded(appContext, song.id.toString()) ||
            !downloading.add(query)
        ) return
        downloadingSongs[query] = song
        downloadProgress[query] = 0
        onDownloadsChanged?.invoke()
        lastDownloadError = null
        com.music.spotui.data.preferences.FailedDownloads.dismiss(appContext,song.id)
        jobs[query] = scope.launch(com.music.spotui.audio.PlaybackDiagnosticSong.context(
            com.music.spotui.audio.DiagnosticSongLabel.create(song.title,song.singer))) {
            var ok=false
            var cancelled=false
            try {
                com.music.spotui.data.preferences.PlaybackDiagnostics.record(appContext,"Download started")
                ok=downloadSlots.withPermit { downloadToFile(song,appContext) }
            } catch(e:kotlinx.coroutines.CancellationException) { cancelled=true;throw e
            } catch(e:Exception) { lastDownloadError=e.message
            } finally {
                if (!cancelled) com.music.spotui.data.preferences.PlaybackDiagnostics.record(appContext,if(ok)"Download ready"else"Download failed")
                jobs.remove(query);downloading.remove(query);downloadProgress.remove(query);downloadingSongs.remove(query)
                if(!ok && !cancelled)com.music.spotui.data.preferences.FailedDownloads.record(appContext,song)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if(!ok && !cancelled && !automatic)com.music.spotui.ui.components.ActionFeedback.show("Download needs attention. Open Downloads to retry.")
                    onDownloadsChanged?.invoke();onComplete(ok)
                }
            }
        }
    }

    fun cancelSong(query: String) { jobs[query]?.cancel() }

    private suspend fun downloadToFile(
        song: com.music.spotui.data.entity.SongsModel,
        appContext: Context,
    ): Boolean {
        SongPlayer.registerLossless(listOf(song.url to song.spotifyTrackId))
        SongPlayer.registerDuration(listOf(song.url to song.durationMs))
        SongPlayer.registerExplicit(listOf(song.url to song.explicit))
        SongPlayer.registerMetadata(listOf(song.url to SongPlayer.TrackMatchMetadata(song.title, song.singer, song.album)))
        val dlQuality = com.music.spotui.data.preferences.getDownloadQuality(appContext)
        // Deezer: use immediately if it yields FLAC (HiFi). If it only yields MP3
        // (free account), HOLD it and try the real FLAC sources first — otherwise a
        // free Deezer MP3 would pre-empt lossless.
        var heldDeezer: com.music.spotui.deezer.DeezerSource.Resolved? = null
        if (SongPlayer.deezerEnabled && com.music.spotui.data.preferences.isDeezerEnabled(appContext)) {
            val raw = kotlinx.coroutines.withTimeoutOrNull(30_000) { resolveDeezerRaw(song, appContext) }
            if (raw != null) {
                if (raw.isFlac) {
                    if (downloadDeezerRaw(song, appContext, raw)) return true
                } else {
                    heldDeezer = raw
                }
            }
        }
        // Lossless FLAC: SpotiFLAC gated (if verified) + Tidal/community. Saves .flac.
        if (SongPlayer.losslessStreaming && song.spotifyTrackId.isNotBlank()) {
            val r = kotlinx.coroutines.withTimeoutOrNull(45_000) {
                com.music.spotui.lossless.LosslessSource.resolve(
                    appContext, song.spotifyTrackId,
                    com.music.spotui.lossless.LosslessSource.TrackInfo(
                        song.title, song.singer.substringBefore(",").trim(), song.durationMs / 1000,
                    ),
                    preferHiRes = SongPlayer.losslessHiRes,
                )
            }
            if (r is com.music.spotui.lossless.LosslessSource.Result.Success) {
                val dir = java.io.File(appContext.filesDir, "downloads").apply { mkdirs() }
                val outFile = java.io.File(dir, "${song.id}.flac")
                val tmpFile = java.io.File(dir, "${song.id}.flacpart")
                if (httpDownloadRanged(r.track.url, tmpFile, song.url) && tmpFile.renameTo(outFile)) {
                    com.music.spotui.data.preferences.addDownload(appContext, song, outFile.absolutePath)
                    Log.d(TAG, "lossless downloaded (${r.track.provider} ${r.track.quality}-bit): ${song.title}")
                    return true
                }
                runCatching { tmpFile.delete() }
            }
        }
        // Deezer MP3 (held above) before a YouTube m4a, except MP3 128 at High,
        // which YouTube's Opus beats (#66); that one stays as the last resort.
        val ytFirst = heldDeezer?.lowBitrate == true && SongPlayer.youtubeEnabled &&
            dlQuality.audioQuality == com.metrolist.music.constants.AudioQuality.HIGH
        if (!ytFirst) heldDeezer?.let { if (downloadDeezerRaw(song, appContext, it)) return true }
        if (!SongPlayer.youtubeEnabled) {
            lastDownloadError = "Track not available for download"
            return false
        }

        val query = song.url
        // Resolve a fresh network stream URL (bypass any local-file short-circuit),
        // walking the ranked video candidates like playback does.
        suspend fun save(playback: com.metrolist.music.utils.YTPlayerUtils.PlaybackData): Boolean {
            val dir = java.io.File(appContext.filesDir, "downloads").apply { mkdirs() }
            val extension = if (playback.format.mimeType.startsWith("audio/webm")) "webm" else "m4a"
            val outFile = java.io.File(dir, "${song.id}.$extension")
            val tmpFile = java.io.File(dir, "${song.id}.part")
            try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!httpDownloadRanged(playback.streamUrl, tmpFile, song.url)) return false
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!tmpFile.renameTo(outFile)) {
                    lastDownloadError = "Couldn't save file"
                    return false
                }
                com.music.spotui.data.preferences.addDownload(appContext, song, outFile.absolutePath)
                lastDownloadError = null
                return true
            } finally { tmpFile.delete() }
        }
        val playback = SongPlayer.resolveYtPlayback(query, dlQuality.audioQuality, appContext)
        if (playback != null && save(playback)) return true
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        // A URL can resolve but fail during the actual transfer. Retry through the independent backup too.
        // If lookup itself already used the backup, don't launch the same extraction twice.
        val alreadyBackup = playback == null || com.music.spotui.audio.StreamRequestHeaders.get(playback.streamUrl) != null
        if (!alreadyBackup && com.music.spotui.data.preferences.isPlaybackBackupEnabled(appContext)) {
            val backup = SongPlayer.resolveYtPlayback(query, dlQuality.audioQuality, appContext, backupOnly = true)
            if (backup != null && save(backup)) return true
        }
        if (ytFirst && downloadDeezerRaw(song, appContext, heldDeezer!!)) return true
        lastDownloadError = lastDownloadError ?: "Couldn't resolve a stream"
        return false
    }

    /**
     * Download a Deezer track and decrypt it to a real audio file. Resolves the
     * stream (ISRC → Deezer, account-tier quality), streams the Blowfish-encrypted
     * CDN bytes and decrypts every 3rd 2048-byte block on the way to `<id>.flac` /
     * `<id>.mp3`. Returns false on any miss so the caller can fall back.
     */
    private suspend fun resolveDeezerRaw(
        song: com.music.spotui.data.entity.SongsModel,
        appContext: Context,
    ): com.music.spotui.deezer.DeezerSource.Resolved? =
        com.music.spotui.deezer.DeezerSource.resolveRaw(
            appContext,
            spotifyId = song.spotifyTrackId.takeIf { it.isNotBlank() },
            isrc = null,
            searchQuery = listOf(song.title, song.singer).filter { it.isNotBlank() }.joinToString(" "),
            expectedDurationSec = if (song.durationMs > 0) song.durationMs / 1000 else 0,
        )

    private fun downloadDeezerRaw(
        song: com.music.spotui.data.entity.SongsModel,
        appContext: Context,
        raw: com.music.spotui.deezer.DeezerSource.Resolved,
    ): Boolean {
        val ext = if (raw.isFlac) "flac" else "mp3"
        val dir = java.io.File(appContext.filesDir, "downloads").apply { mkdirs() }
        val outFile = java.io.File(dir, "${song.id}.$ext")
        val tmpFile = java.io.File(dir, "${song.id}.dzpart")
        if (!deezerDownloadDecrypted(raw.url, raw.encrypted, raw.trackId, tmpFile, song.url)) {
            runCatching { tmpFile.delete() }
            return false
        }
        if (!tmpFile.renameTo(outFile)) {
            lastDownloadError = "Couldn't save file"
            runCatching { tmpFile.delete() }
            return false
        }
        com.music.spotui.data.preferences.addDownload(appContext, song, outFile.absolutePath)
        Log.d(TAG, "Deezer downloaded (${raw.qualityLabel}): ${song.title}")
        return true
    }

    /**
     * Stream [url] and, if [encrypted], Blowfish-decrypt every 3rd 2048-byte block
     * (Deezer's stripe cipher) while writing to [tmpFile], reporting progress for
     * [query]. Produces a plain, fully-decrypted audio file.
     */
    private fun deezerDownloadDecrypted(
        url: String,
        encrypted: Boolean,
        trackId: String,
        tmpFile: java.io.File,
        query: String,
    ): Boolean {
        downloadProgress[query] = 0
        val conn = openDownloadConn(url)
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                lastDownloadError = "Deezer CDN HTTP $code"
                return false
            }
            val total = conn.contentLengthLong
            val cipher = if (encrypted) {
                com.music.spotui.deezer.DeezerCrypto.cipher(
                    com.music.spotui.deezer.DeezerCrypto.trackKey(trackId),
                )
            } else {
                null
            }
            java.io.BufferedOutputStream(tmpFile.outputStream()).use { output ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(2048)
                    var counter = 0
                    var position = 0L
                    while (true) {
                        var read = 0
                        while (read < 2048) {
                            val r = input.read(buf, read, 2048 - read)
                            if (r < 0) break
                            read += r
                        }
                        if (read == 0) break
                        val out = if (encrypted && read == 2048 && counter % 3 == 0) {
                            cipher!!.doFinal(buf)
                        } else {
                            buf
                        }
                        output.write(out, 0, read)
                        counter++
                        position += read
                        if (total > 0) {
                            val pct = ((position * 100) / total).toInt().coerceIn(0, 100)
                            if (downloadProgress[query] != pct) {
                                downloadProgress[query] = pct
                                onDownloadsChanged?.invoke()
                            }
                        }
                        if (read < 2048) break // final partial chunk
                    }
                }
            }
            downloadProgress[query] = 100
            true
        } catch (e: Exception) {
            lastDownloadError = e.message ?: "Deezer download error"
            false
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
