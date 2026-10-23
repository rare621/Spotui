package com.music.spotui.webui

import android.content.Context
import com.metrolist.spotify.Spotify
import com.music.spotui.data.api.SpotifyMutations
import com.music.spotui.di.Downloads
import com.music.spotui.data.recommendation.RadioQueuePolicy
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.*
import com.music.spotui.data.preferences.*
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.filter
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Reuses the installed account and repositories. No provider credentials leave Android. */
internal class WebUiBackend(private val context: Context) {
    private val api = Api(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discoveryJob: Job? = null
    @Synchronized private fun refreshDiscoveries() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            try { withTimeout(45_000L) { api.discoverHomeCollections() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the last usable snapshot offline. */ }
        }
    }
    fun close() { scope.cancel() }
    fun restored() { Api.HomeCache.clear(); collections.clear(); libraryEntries.clear(); createdPlaylists.clear(); songs.clear(); tickets.clear() }
    private val songs = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, SongsModel>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SongsModel>?) = size > 15000
    })
    private val collections = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, HomeItem>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HomeItem>?) = size > 2000
    })
    private val createdPlaylists = ConcurrentHashMap<String, List<SongsModel>>()
    private val coverMutex = kotlinx.coroutines.sync.Mutex()
    private val playlistMutex = kotlinx.coroutines.sync.Mutex()
    private val libraryEntries = ConcurrentHashMap<String, LibraryEntry>()
    private val imageUris = ConcurrentHashMap<String, String>()
    fun imageUri(id: String): String? = imageUris[id]
    private val tickets = ConcurrentHashMap<String, Ticket>()
    data class Ticket(val song: SongsModel, val uri: String, val mime: String?, val created: Long,
                      val collection: String, var started: Boolean = false, var qualified: Boolean = false)
    fun ticket(id: String): Ticket? = tickets[id]?.takeIf { android.os.SystemClock.elapsedRealtime() - it.created < 30 * 60_000L }
    private fun register(song: SongsModel): String {
        val key = song.spotifyTrackId.takeIf { it.isNotBlank() && !it.startsWith("uspot-mix:") } ?: song.id.toString()
        songs[key] = song
        return key
    }
    private fun track(song: SongsModel) = JSONObject().put("key", register(song)).put("type", "song")
        .put("title", song.title).put("subtitle", song.singer).put("image", remoteImage(song.coverUri))
        .put("album", song.album).put("spotifyId", song.spotifyTrackId)
        .put("downloaded", isDownloaded(context, song.id.toString())).put("downloading", Downloads.isDownloading(song.url))
        .put("duration", song.durationMs).put("liked", isSongLiked(context, song.id.toString()))
    private fun remoteImage(uri: String): String {
        if (uri.startsWith("https://")) return uri
        if (uri.isBlank() || !(uri.startsWith("/") || uri.startsWith("content://") || uri.startsWith("file://"))) return ""
        if (imageUris.size >= 4000) return ""
        val id = UUID.nameUUIDFromBytes(uri.toByteArray()).toString()
        imageUris[id] = uri
        return "/image/$id"
    }
    private fun item(item: HomeItem): JSONObject {
        if (item is HomeItem.RecentTrack) return track(item.song)
        val key = when (item) {
            is HomeItem.Playlist -> "playlist:${item.id}"
            is HomeItem.Album -> "album:${item.name}|${item.artists}"
            is HomeItem.Artist -> "artist:${item.id.ifBlank { item.name }}"
            is HomeItem.Radio -> "radio:${item.seed.spotifyTrackId}"
            else -> error("Unsupported item")
        }
        collections[key] = item
        val json = JSONObject().put("key", key).put("title", item.name).put("image", remoteImage(item.imageUrl))
            .put("type", key.substringBefore(':')).put("subtitle", when (item) {
                is HomeItem.Playlist -> "Playlist"
                is HomeItem.Radio -> "USpot Radio"
                is HomeItem.Album -> item.artists.ifBlank { "Album" }
                else -> "Artist"
            })
        if (item is HomeItem.Radio) {
            json.put("mode", RadioTaste.mode(context, item.seed.spotifyTrackId))
                .put("mix", RadioSessions.isMix(item.seed.spotifyTrackId))
                .put("anchors", array(RadioSessions.anchors(context, item.seed.spotifyTrackId).map(::track)))
                .put("shuffled", RadioMemory.queue(context, item.seed.spotifyTrackId)?.shuffled ?: false)
        }
        json.put("pinned",LibraryPins.contains(context,libraryEntries[key]?.let(LibraryPins::key) ?: key))
        if(item is HomeItem.Playlist) json.put("canRecommend", createdPlaylists.containsKey(item.id))
        return json
    }
    private fun array(items: List<JSONObject>) = JSONArray().also { a -> items.forEach { a.put(it) } }
    private suspend fun <T> result(flow: kotlinx.coroutines.flow.Flow<Response<T>>, allPages: Boolean = false): T =
        when (val value = if (allPages) flow.filter { it !is Response.Loading }.last() else flow.first { it !is Response.Loading }) {
        is Response.Success -> value.data
        else -> throw IllegalStateException("Catalog unavailable. Check the Spotify connection on your phone.")
    }
    suspend fun command(body: JSONObject): JSONObject = withTimeout(60_000L) {
        when (body.optString("action")) {
            "home" -> {
                val feed = Api.home ?: HomeSnapshotCache.readFeed(context) ?: result(api.getHomeFeed())
                val discovery = api.cachedHomeDiscoveries()
                refreshDiscoveries()
                com.music.spotui.data.api.ProfileCache.ensure(context)
                val ready = com.music.spotui.data.recommendation.HomePresentationBuilder.build(context, feed, discovery, com.music.spotui.data.api.ProfileCache.name)
                JSONObject().put("shortcuts", array(ready.topTiles.map(::item))).put("sections", JSONArray().also { rows ->
                    ready.sections.take(10).forEach { section -> rows.put(JSONObject().put("title", section.title).put("items", array(section.items.take(12).map(::item)))) }
                })
            }
            "following" -> {
                val feed = api.getFollowingFeed()
                JSONObject().put("songs", array(feed.popularSongs.map(::track)))
                    .put("releases", array(feed.releases.map { item(HomeItem.Album(it.name, it.image, "New release", it.artist)) }))
                    .put("artists", array(feed.artists.map { item(HomeItem.Artist(it.name, it.image, it.id)) }))
            }
            "stats" -> {
                val summary = com.music.spotui.data.recommendation.ListeningStatsPolicy.summary(getListeningHistory(context), System.currentTimeMillis(), body.optInt("days", 30).takeIf { it in setOf(0, 7, 30) } ?: 30)
                JSONObject().put("plays", summary.plays).put("artists", summary.artistCount).put("minutes", summary.recordedMs/60000)
                    .put("songs", array(summary.songs.take(30).map { track(it.song).put("plays", it.plays) }))
                    .put("topArtists", array(summary.artists.take(10).map { JSONObject().put("name", it.name).put("plays", it.plays).put("image", remoteImage(it.artwork)) }))
                    .put("activity", JSONArray().also { a -> summary.activity.forEach { a.put(JSONObject().put("day", it.date.toString()).put("plays", it.plays)) } })
            }
            "library" -> {
                val rows = result(api.getLibrary(), allPages = true).take(500).map { entry ->
                    val mapped = item(if (entry.isPlaylist) HomeItem.Playlist(entry.name, entry.coverUri, entry.subtitle, entry.spotifyId)
                    else HomeItem.Album(entry.name, entry.coverUri, entry.subtitle, entry.artists))
                    libraryEntries[mapped.getString("key")] = entry
                    mapped.put("pinned",LibraryPins.contains(context,LibraryPins.key(entry)))
                }
                JSONObject().put("sort",LibraryPins.sort(context)).put("items", array(rows + RadioSessions.get(context).take(100).map { item(HomeItem.Radio(it)) }))
            }
            "search" -> {
                val query = body.optString("q").trim().take(180)
                require(query.isNotEmpty())
                val data = result(api.searchEverything(query))
                JSONObject().put("songs", array(data.songs.map(::track)))
                    .put("albums", array(data.albums.map { item(HomeItem.Album(it.name, it.coverUri, "Album", it.artists)) }))
                    .put("artists", array(data.artists.map { item(HomeItem.Artist(it.name, it.coverUri, it.id)) }))
                    .put("playlists", array(data.playlists.map { item(HomeItem.Playlist(it.name, it.coverUri, "Spotify playlist", it.spotifyId)) }))
                    .put("radios",array(RadioSessions.get(context).filter{RadioSessions.stationName(it).contains(query,true)||it.singer.contains(query,true)}.map{item(HomeItem.Radio(it))}))
            }
            "collection" -> {
                val key = body.optString("key")
                val selected = collections[key] ?: when (key) {
                    "playlist:${Api.LIKED_SONGS_ID}" -> HomeItem.Playlist("Liked Songs", "", "Playlist", Api.LIKED_SONGS_ID)
                    "playlist:${Api.DOWNLOADS_ID}" -> HomeItem.Playlist("Downloaded", "", "Offline songs on your phone", Api.DOWNLOADS_ID)
                    else -> error("Refresh this page to load the collection again.")
                }
                val loaded = when (selected) {
                    is HomeItem.Playlist -> when (selected.id) {
                        Api.LIKED_SONGS_ID -> result(api.getLikedSongs(), allPages = true)
                        Api.DOWNLOADS_ID -> getDownloadedSongs(context)
                        else -> result(api.getPlaylistSongs(selected.id), allPages = true)
                    }
                    is HomeItem.Album -> result(api.getAlbumSongs(selected.name, selected.artists))
                    is HomeItem.Artist -> result(api.getArtistSongs(selected.name))
                    is HomeItem.Radio -> radioSongs(selected.seed, body.optBoolean("refresh"))
                    else -> emptyList()
                }
                JSONObject().put("item", item(selected)).put("songs", array(loaded.take(15000).map(::track)))
            }
            "radio" -> {
                val song = songs[body.optString("key")] ?: error("Choose a song first.")
                require(song.spotifyTrackId.isNotBlank())
                RadioSessions.remember(context, song)
                val station = HomeItem.Radio(song)
                JSONObject().put("item", item(station)).put("songs", array(radioSongs(song).map(::track)))
            }
            "resolve" -> {
                tickets.entries.removeAll { android.os.SystemClock.elapsedRealtime() - it.value.created >= 30 * 60_000L }
                require(tickets.size < 120) { "Restart Web UI to clear this session." }
                val song = songs[body.optString("key")] ?: error("Reload the songs and try again.")
                if(body.optBoolean("retry"))SongPlayer.invalidateResolvedStream(song.url)
                val resolved = SongPlayer.resolveForBrowser(song, context) ?: error("No playable stream found. Try again or choose another song.")
                val id = UUID.randomUUID().toString()
                tickets[id] = Ticket(song, resolved.first, resolved.second, android.os.SystemClock.elapsedRealtime(), body.optString("collection"))
                JSONObject().put("ticket", id).put("url", "/audio/$id").put("track", track(song))
            }
            "adopt" -> {
                val old = ticket(body.optString("ticket")) ?: error("Playback session expired.")
                val key = body.optString("collection")
                require(collections[key] is HomeItem.Radio)
                require(tickets.size < 120)
                val id = UUID.randomUUID().toString()
                tickets[id] = old.copy(created = android.os.SystemClock.elapsedRealtime(), collection = key, started = false, qualified = false)
                JSONObject().put("ticket", id)
            }
            "started", "listened" -> {
                val ticket = ticket(body.optString("ticket")) ?: error("Playback session expired.")
                synchronized(ticket) {
                    val radio = (collections[ticket.collection] as? HomeItem.Radio)?.seed
                    if (body.optString("action") == "started" && !ticket.started) {
                        ticket.started = true
                        if (radio != null) RadioSessions.saveLastTrack(context, radio.spotifyTrackId, ticket.song)
                        (collections[ticket.collection] as? HomeItem.Playlist)?.let { HomeRecents.rememberPlaylist(context, it.id, it.name, it.imageUrl) }
                    }
                    if (body.optString("action") == "listened" && ticket.started && !ticket.qualified &&
                        android.os.SystemClock.elapsedRealtime() - ticket.created >= 30_000L) {
                        ticket.qualified = true
                        val s = ticket.song
                        addListeningHistory(context, HistoryEntry(System.currentTimeMillis(), s.id, s.title, s.singer,
                            s.album, s.coverUri, s.url, s.spotifyTrackId, listenedMs = 30_000L, durationMs = s.durationMs, visitId = "web:${body.optString("ticket")}"))
                        when (val collection = collections[ticket.collection]) {
                            is HomeItem.Playlist -> HomeFrequency.record(context, "playlist", collection.id, collection.name, collection.imageUrl, collection.subtitle)
                            is HomeItem.Album -> HomeFrequency.record(context, "album", "${collection.name}|${collection.artists}", collection.name, collection.imageUrl, collection.artists)
                            is HomeItem.Radio -> HomeFrequency.record(context, "radio", collection.seed.spotifyTrackId, collection.name, collection.imageUrl, "USpot Radio")
                            else -> Unit
                        }
                        if (radio != null) RadioSessions.qualified(context, radio.spotifyTrackId, s, "web:${body.optString("ticket")}")
                    }
                }
                JSONObject().put("ok", true)
            }
            "ping" -> JSONObject().put("ok", true)
            else -> extra(body)
        }
    }
    private suspend fun radioSongs(seed: SongsModel, refresh: Boolean = false): List<SongsModel> {
        val saved = RadioMemory.queue(context, seed.spotifyTrackId)
        val mode = RadioTaste.mode(context, seed.spotifyTrackId)
        val retained = saved?.songs?.filterIndexed { index, song -> index == 0 || RadioTaste.allowed(context, song) }.orEmpty()
        if (!refresh && saved?.mode == mode && retained.size >= 6) return retained.take(11)
        val resume = retained.firstOrNull() ?: RadioSessions.lastTrack(context, seed.spotifyTrackId)
        val fresh = api.getRadioRecommendations(seed.spotifyTrackId, RadioSessions.recommendationSeed(context, seed.spotifyTrackId))
        val anchor = RadioSessions.anchors(context, seed.spotifyTrackId).firstOrNull()?.let { RadioSessions.resolvedAnchor(context, it) }
        return RadioQueuePolicy.resumed(anchor, resume, if (!refresh && saved?.mode == mode) retained.drop(1) + fresh else fresh)
    }
    private fun song(body: JSONObject): SongsModel = songs[body.optString("key")] ?: error("Reload the song and try again.")
    private fun station(body: JSONObject): HomeItem.Radio = collections[body.optString("key")] as? HomeItem.Radio ?: error("Open a station first.")
    private suspend fun <T> mutation(action: suspend () -> Result<T>): T {
        val result = SpotifyMutations.run(context, action)
        return result.getOrElse { throw WebUiActionFailure(SpotifyMutations.describe(it).lineSequence().first()) }
    }
    private suspend fun extra(body: JSONObject): JSONObject = when (body.optString("action")) {
        "pin" -> {
            val key=body.getString("key")
            require(collections[key] != null)
            val pinKey=libraryEntries[key]?.let(LibraryPins::key) ?: key
            LibraryPins.toggle(context,pinKey)
            JSONObject().put("pinned",LibraryPins.contains(context,pinKey))
        }
        "librarySort" -> {
            val value=body.getString("value");require(value in setOf("Recent","Name","Creator"))
            LibraryPins.setSort(context,value);JSONObject().put("ok",true)
        }
        "restoreHome" -> {
            val target=collections[body.optString("key")] ?: songs[body.optString("key")]?.let{HomeItem.RecentTrack(it)} ?: error("Reload Home")
            HomeHiddenItems.restore(context,target);JSONObject().put("ok",true)
        }
        "downloadManager" -> JSONObject().put("active",array(Downloads.downloadingSnapshot().map{(song,pct)->track(song).put("progress",pct)}))
            .put("failed",array(FailedDownloads.entries(context).map{track(it.song).put("reason",it.reason)}))
            .put("bytes",getDownloadedEntries(context).sumOf{java.io.File(it.second).length()})
            .put("completed",getDownloadedSongs(context).size)
        "retryDownload" -> {Downloads.downloadSong(song(body),context);JSONObject().put("ok",true)}
        "dismissDownload" -> {FailedDownloads.dismiss(context,song(body).id);JSONObject().put("ok",true)}
        "cancelDownload" -> {Downloads.cancelSong(song(body).url);JSONObject().put("ok",true)}
        "tuneRadio", "refreshRadio" -> {
            val radio = station(body)
            if (body.optString("action") == "tuneRadio") {
                val mode = body.getString("mode")
                require(mode in setOf("close", "balanced", "explore"))
                RadioTaste.setMode(context, radio.seed.spotifyTrackId, mode)
            }
            JSONObject().put("item", item(radio)).put("songs", array(radioSongs(radio.seed, true).map(::track)))
        }
        "saveRadioQueue" -> {
            val radio = station(body)
            val keys = body.getJSONArray("songs")
            require(keys.length() <= 11)
            val queue = (0 until keys.length()).mapNotNull { songs[keys.getString(it)] }
            val manual = body.optJSONArray("manual")
            val manualIds = if (manual == null) emptySet() else (0 until manual.length()).mapNotNull { songs[manual.optString(it)]?.id }.toSet()
            queue.firstOrNull()?.let { RadioMemory.saveQueue(context, radio.seed.spotifyTrackId, queue, it.id, body.optBoolean("shuffled"), manualQueued = manualIds) }
            JSONObject().put("ok", true)
        }
        "createRadio" -> {
            val keys = body.getJSONArray("songs")
            val mix = body.optBoolean("mix")
            require(keys.length() in 1..(if(mix)5 else 1))
            val chosen = (0 until keys.length()).map { songs[keys.getString(it)] ?: error("Choose these songs again.") }.distinctBy { it.id }
            var marker = RadioSessions.create(context, chosen, body.optString("name"), mix)
            if (mix) {
                val cover = com.music.spotui.data.api.PlaylistCover.generate(context, marker.spotifyTrackId.replace(':','_'), chosen)
                if (cover != null) { RadioSessions.updateArtwork(context, marker.spotifyTrackId, cover); marker = marker.copy(coverUri=cover) }
            }
            JSONObject().put("item", item(HomeItem.Radio(marker))).put("songs", array(radioSongs(marker).map(::track)))
        }
        "songState" -> JSONObject().put("track", track(song(body)))
        "like" -> {
            val song = song(body); require(song.spotifyTrackId.matches(Regex("[A-Za-z0-9]{22}")))
            val saved = body.getBoolean("saved")
            mutation { if(saved) Spotify.addToLibrary(listOf("spotify:track:${song.spotifyTrackId}")) else Spotify.removeFromLibrary(listOf("spotify:track:${song.spotifyTrackId}")) }
            if(saved) addLikedSongId(context, song.id.toString()) else removeLikedSongId(context, song.id.toString())
            JSONObject().put("track", track(song))
        }
        "download" -> {
            val keys = body.getJSONArray("songs"); require(keys.length() in 1..15000)
            val selected = (0 until keys.length()).mapNotNull { songs[keys.optString(it)] }.distinctBy { it.id }
            Downloads.downloadAll(selected, context)
            JSONObject().put("ok", true)
        }
        "removeDownload" -> {
            val selected = song(body)
            Downloads.cancelSong(selected.url)
            removeDownload(context,selected.id.toString())
            JSONObject().put("ok",true)
        }
        "exclude" -> {
            val song = song(body)
            if(body.optBoolean("artist")) RadioTaste.block(context, song) else RadioTaste.dislike(context, song)
            JSONObject().put("ok", true)
        }
        "hideHome" -> {
            val entry = collections[body.optString("key")] ?: songs[body.optString("key")]?.let { HomeItem.RecentTrack(it) } ?: error("Reload Home.")
            HomeHiddenItems.hide(context, entry); JSONObject().put("ok", true)
        }
        "createPlaylist" -> {
            val name = body.optString("name").trim().take(100); require(name.isNotEmpty())
            val creationSession = com.music.spotui.data.preferences.CreatedPlaylists.session(context)
            val created = mutation { Spotify.createPlaylist(name) }
            com.music.spotui.data.preferences.CreatedPlaylists.record(context,creationSession,created.id,created.name,pending=true)
            val entry = LibraryEntry(created.id, created.name, "Playlist", "", true)
            val mapped = item(HomeItem.Playlist(created.name, "", "Playlist", created.id))
            libraryEntries[mapped.getString("key")] = entry
            createdPlaylists[created.id] = emptyList()
            mapped.put("canRecommend",true)
            Api.HomeCache.library = (Api.HomeCache.library.orEmpty() + entry).distinctBy { it.spotifyId }
            cacheLibraryEntries(context, Api.HomeCache.library.orEmpty())
            val saved = com.music.spotui.data.api.SpotifyMutations.run(context) { Spotify.addToLibrary(listOf("spotify:playlist:${created.id}")) }
            if(saved.isSuccess)com.music.spotui.data.preferences.CreatedPlaylists.markSaved(context,created.id)
            JSONObject().put("item", mapped).put("warning",if(saved.isSuccess)""else"Playlist created and kept in USpot. Saving to Spotify is pending; retry saving it from your phone.")
        }
        "addToPlaylist" -> {
            val song = song(body)
            val selected = collections[body.optString("playlist")] as? HomeItem.Playlist ?: error("Select a playlist.")
            require(selected.id !in setOf(Api.LIKED_SONGS_ID, Api.DOWNLOADS_ID) && song.spotifyTrackId.isNotBlank())
            mutation { Spotify.addTracksToPlaylist(selected.id, listOf("spotify:track:${song.spotifyTrackId}")) }
            if(createdPlaylists.containsKey(selected.id)) {
                playlistMutex.lock()
                try { createdPlaylists[selected.id] = (createdPlaylists[selected.id].orEmpty()+song).distinctBy { it.id } }
                finally { playlistMutex.unlock() }
                // The Spotify add is already confirmed. Artwork must never turn that
                // success into a timeout or encourage the user to add a duplicate.
                scope.launch {
                    coverMutex.lock()
                    try {
                        val tracks = createdPlaylists[selected.id].orEmpty()
                        val path = com.music.spotui.data.api.PlaylistCover.generate(context,selected.id,tracks)
                        if(path != null) {
                            collections[body.optString("playlist")] = selected.copy(imageUrl=path)
                            com.music.spotui.data.api.PlaylistCover.upload(selected.id,path)
                        }
                    } catch(cancelled: CancellationException) { throw cancelled }
                    catch(_: Exception) { /* The added song remains saved even if artwork is unavailable. */ }
                    finally { coverMutex.unlock() }
                }
            }
            JSONObject().put("ok", true)
        }

        "removeLibrary" -> {
            val entry = libraryEntries[body.optString("key")] ?: error("Reload your library first.")
            require(entry.spotifyId !in setOf(Api.LIKED_SONGS_ID, Api.DOWNLOADS_ID))
            mutation { Spotify.removeFromLibrary(listOf("spotify:${if(entry.isPlaylist)"playlist" else "album"}:${entry.spotifyId}")) }
            if(entry.isPlaylist)com.music.spotui.data.preferences.CreatedPlaylists.remove(context,entry.spotifyId)
            Api.HomeCache.library = Api.HomeCache.library.orEmpty().filterNot { it.spotifyId == entry.spotifyId }
            cacheLibraryEntries(context, Api.HomeCache.library.orEmpty())
            libraryEntries.remove(body.optString("key"))
            JSONObject().put("ok", true)
        }
        "playlistSuggestions" -> {
            val history = com.music.spotui.data.recommendation.ListeningStatsPolicy.summary(getListeningHistory(context),System.currentTimeMillis(),30).songs.take(3).map { it.song }
            val original = result(api.getSongs()).take(20)
            val seeds = (history+original.take(2)).map { it.spotifyTrackId }.filter { it.isNotBlank() }.distinct().take(5)
            val suggested = if(seeds.isEmpty()) original else api.getRecommendations(seeds)
            JSONObject().put("songs",array((suggested+original).filter { RadioTaste.allowed(context,it) }.distinctBy { it.id }.take(20).map(::track)))
        }
        "artistPage" -> {
            val selected = collections[body.optString("key")] as? HomeItem.Artist ?: error("Choose an artist.")
            val artist = result(api.getArtistOverview(selected.name, selected.id))
            val followed = api.getFollowedArtists().any { it.id == artist.id }
            JSONObject().put("item", item(selected)).put("id", artist.id).put("bio", artist.biography.orEmpty())
                .put("listeners", artist.monthlyListeners ?: 0).put("followed", followed)
                .put("songs", array(artist.topTracks.map { track(it.song).put("plays", it.playcount ?: 0) }))
                .put("releases", array(artist.popularReleases.map { item(HomeItem.Album(it.name,it.coverUri,"Album",it.artists)) }))
                .put("appearsOn", array(artist.appearsOn.map { item(HomeItem.Album(it.name,it.coverUri,"Album",it.artists)) }))
                .put("related", array(artist.relatedArtists.map { item(HomeItem.Artist(it.name,it.coverUri,it.id)) }))
        }
        "followArtist" -> {
            val selected = collections[body.optString("key")] as? HomeItem.Artist ?: error("Choose an artist.")
            val id = selected.id.ifBlank { result(api.getArtistOverview(selected.name)).id }
            require(id.isNotBlank())
            val saved = body.getBoolean("saved")
            mutation { if(saved) Spotify.addToLibrary(listOf("spotify:artist:$id")) else Spotify.removeFromLibrary(listOf("spotify:artist:$id")) }
            if(saved)addFollowedArtist(context,id,selected.name) else removeFollowedArtist(context,id)
            JSONObject().put("ok",true)
        }
        "smartSuggestions" -> {
            val ids = body.getJSONArray("songs")
            require(ids.length() in 1..3)
            val seeds = (0 until ids.length()).mapNotNull { songs[ids.optString(it)]?.spotifyTrackId }.filter { it.isNotBlank() }
            JSONObject().put("songs",array(api.getRecommendations(seeds).filter { RadioTaste.allowed(context,it) }.distinctBy { it.id }.take(30).map(::track)))
        }
        "local" -> JSONObject().put("songs", array(getLocalSongs(context).map(::track)))
        "history" -> JSONObject().put("songs", array(getListeningHistory(context).take(2000).map { h ->
            track(SongsModel(h.songId,h.title,h.album,h.singer,h.image,h.url,h.spotifyTrackId,durationMs=h.durationMs)).put("at",h.ts)
        }))
        "removeHistory" -> {
            val song = song(body)
            getListeningHistory(context).firstOrNull { it.ts == body.optLong("at") && it.songId == song.id }?.let { removeListeningHistory(context,it) }
            JSONObject().put("ok",true)
        }
        "clearHistory" -> { clearListeningHistory(context); JSONObject().put("ok",true) }
        "lyrics" -> {
            val song = song(body)
            com.music.spotui.data.api.LyricsApi.registerTracks(listOf(song))
            val lyrics = com.music.spotui.data.api.LyricsApi.fetch(song.title,song.singer,song.album,song.durationMs/1000)
            JSONObject().put("synced",lyrics?.synced ?: false).put("lines",array(lyrics?.lines.orEmpty().map { JSONObject().put("at",it.timeMs).put("text",it.text) }))
        }
        "settings" -> {
            when(body.optString("setting")) {
                "smartEnabled" -> SmartDownloads.setEnabled(context, body.getBoolean("value"))
                "smartCount" -> SmartDownloads.setCount(context, body.getInt("value"))
                "smartWifi" -> SmartDownloads.setWifi(context, body.getBoolean("value"))
                "personalization" -> HomePersonalization.setEnabled(context, body.getBoolean("value"))
                "strength" -> HomePersonalization.setStrength(context, body.getInt("value"))
                "" -> Unit
                else -> error("Unsupported setting")
            }
            JSONObject().put("smartEnabled", SmartDownloads.enabled(context)).put("smartCount", SmartDownloads.count(context))
                .put("smartWifi", SmartDownloads.wifiOnly(context)).put("personalization", HomePersonalization.enabled(context))
                .put("strength", HomePersonalization.strength(context)).put("name", com.music.spotui.data.api.ProfileCache.name)
                .put("version", com.music.spotui.BuildConfig.VERSION_NAME).put("build", com.music.spotui.BuildConfig.VERSION_CODE)
                .put("managedCacheLimitBytes", com.music.spotui.cache.CacheBudget.TOTAL_BYTES)
                .put("managedCacheBytes", com.music.spotui.cache.CacheStorage.usage(context).total)
        }
        else -> error("Unknown action")
    }
}

internal class WebUiActionFailure(message: String) : Exception(message)
