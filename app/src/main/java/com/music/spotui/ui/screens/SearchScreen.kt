package com.music.spotui.ui.screens

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.music.spotui.ui.components.SongOptionsSheet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SearchResults
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.categoryRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.SearchViewModel


@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun SearchScreen(navController: NavController) {
    val searchViewModel : SearchViewModel = hiltViewModel()
    val results by searchViewModel.results.collectAsState()

    // Results are live search hits (or empty); never gate the search UI on them.
    val searchResults = (results as? Response.Success)?.data ?: SearchResults()

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        SumUpSearchScreen(navController = navController, searchResults, searchViewModel)
    }
}


@RequiresApi(Build.VERSION_CODES.S)
@OptIn(ExperimentalFoundationApi::class, ExperimentalGlideComposeApi::class)
@Composable
fun SumUpSearchScreen(
    navController: NavController,
    results: SearchResults,
    searchViewModel: SearchViewModel,
) {
    val context = LocalContext.current
    val loadingMore by searchViewModel.loadingMore.collectAsState()
    val pageError by searchViewModel.pageError.collectAsState()

    var text by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf("")
    }
    // Recents are the *items opened from results* (songs/artists/albums), not the
    // typed queries, and only appear once the user taps into the search bar.
    var searchFocused by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var recents by remember {
        mutableStateOf(com.music.spotui.data.preferences.getRecentItems(context))
    }
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    menuSong?.let { sel ->
        SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
        )
    }

    val recordRecent: (com.music.spotui.data.preferences.RecentItem) -> Unit = { item ->
        com.music.spotui.data.preferences.addRecentItem(context, item)
        recents = com.music.spotui.data.preferences.getRecentItems(context)
    }
    // Live Spotify search results for the current query.
    var selectedFilter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("All") }
    val filteredResults = if(selectedFilter=="USpot Radios")SearchResults() else com.music.spotui.data.recommendation.SearchFilterPolicy.filter(results, selectedFilter)
    val searchedList = filteredResults.songs
    // One relevance-mixed list (songs, artists, albums interleaved) instead of
    // separate type sections — matches how most music apps present search.
    val mixed = remember(results, selectedFilter) {
        val original = mixSearchResults(filteredResults)
        if(selectedFilter == "All" && original.isNotEmpty()) listOf(original.first()) + original.drop(1).sortedBy { when(it) { is SearchRow.Song->0; is SearchRow.Artist->1; is SearchRow.Album->2; is SearchRow.Playlist->3 } } else original
    }
    var radioMode by remember { mutableStateOf<Boolean?>(null) }
    radioMode?.let { mode -> com.music.spotui.ui.components.RadioCreatorDialog(mix=mode,onDismiss={radioMode=null},onCreated={id->radioMode=null;navController.navigate("radio/"+android.net.Uri.encode(id))}) }
    val radioMatches = com.music.spotui.data.preferences.RadioSessions.get(context).filter { seed ->
        text.isNotBlank() && (com.music.spotui.data.preferences.RadioSessions.stationName(seed).contains(text,true) || seed.singer.contains(text,true))
    }


    val searchListState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(
        state = searchListState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = com.music.spotui.ui.navigation.LocalBottomOverlayInset.current + 24.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
            .statusBarsPadding()

    ){
        item{
            SearchTopBar()
        }
        stickyHeader {
            Column(Modifier.fillMaxWidth().background(com.music.spotui.ui.theme.AppBackground)) {
                SearchStickyBar(text, onFocusChange = { searchFocused = it },onBack={if(text.isBlank())navController.navigateUp()else{text="";searchViewModel.search("")}}) {
                    text = it
                    searchViewModel.search(it)
                }
                if (text.isNotBlank()) Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Songs", "Playlists", "Albums", "Artists", "USpot Radios").forEach { label ->
                        com.music.spotui.ui.components.ThemeFilterPill(label, selectedFilter == label) { selectedFilter = label }
                    }
                }
            }
        }

        if (text.isBlank()) {
            if (recents.isNotEmpty()) {
                // ── Recent searches: the items the user opened (Spotify-style),
                // shown only once the search bar is focused ──
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp, 16.dp, 16.dp, 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Recent searches",
                            color = com.music.spotui.ui.theme.AppText,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Clear",
                            color = com.music.spotui.ui.theme.AppSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                val previous=recents.toList()
                                com.music.spotui.data.preferences.clearRecentItems(context)
                                recents = emptyList()
                                com.music.spotui.ui.components.ActionFeedback.show("Recent searches cleared",undo={previous.asReversed().forEach{com.music.spotui.data.preferences.addRecentItem(context,it)};recents=com.music.spotui.data.preferences.getRecentItems(context)})
                            },
                        )
                    }
                }
                items(recents.size) { i ->
                    val item = recents[i]
                    RecentItemRow(
                        item = item,
                        onClick = {
                            when (item.type) {
                                "song" -> {
                                    val songUrl = item.songUrl.ifBlank {
                                        SongPlayer.buildSpotifyPlayQuery(item.spotifyTrackId, item.name, item.singer)
                                    }.let { savedUrl ->
                                        if (
                                            item.spotifyTrackId.isNotBlank() &&
                                            !savedUrl.startsWith("spotify:track:")
                                        ) {
                                            SongPlayer.buildSpotifyPlayQuery(item.spotifyTrackId, item.name, item.singer)
                                        } else {
                                            savedUrl
                                        }
                                    }
                                    val song = SongsModel(
                                        item.songId, item.name, item.songAlbum, item.singer,
                                        item.image, songUrl, item.spotifyTrackId,
                                        explicit = item.explicit,
                                        durationMs = item.durationMs,
                                    )
                                    searchViewModel.startSong(song)
                                    SongPlayer.playSong(song.url, context)
                                    searchViewModel.updateSongState(
                                        song.coverUri, song.title, song.singer, true, song.id, 0, song.album)
                                }
                                "artist" -> navController.navigate(artistRoute(item.name, item.key.takeIf { it != item.name }.orEmpty()))
                                "album" -> navController.navigate(albumRoute(item.name, item.singer))
                                "show" -> navController.navigate(showRoute(item.key, item.name))
                                "playlist" -> navController.navigate(com.music.spotui.ui.navigation.playlistRoute(item.key, item.name))
                            }
                        },
                        onRemove = {
                            com.music.spotui.data.preferences.removeRecentItem(context, item)
                            recents = com.music.spotui.data.preferences.getRecentItems(context)
                            com.music.spotui.ui.components.ActionFeedback.show("Removed from recent searches",undo={com.music.spotui.data.preferences.addRecentItem(context,item);recents=com.music.spotui.data.preferences.getRecentItems(context)})
                        },
                    )
                }
            } else {
                // ── Spotify-style "Browse all" category grid ──
                item {
                    // Real Spotify opens a genre *catalogue* (a page of curated
                    // playlists) rather than running a keyword song search.
                    BrowseAllSection { genre, title ->
                        navController.navigate(categoryRoute(genre, title))
                    }
                }
            }
        } else {
            if(selectedFilter!="USpot Radios") when (val response = searchViewModel.results.value) {
                is Response.Loading -> item { com.music.spotui.ui.components.LoadingRows() }
                is Response.Error -> item { com.music.spotui.ui.components.RetryPanel("Couldn’t search right now. Check your connection.") { searchViewModel.search(text) } }
                is Response.Success -> Unit
            }
            if (mixed.isEmpty() && radioMatches.isEmpty() && filteredResults.shows.isEmpty() && filteredResults.episodes.isEmpty() && searchViewModel.results.value is Response.Success) {
                item { com.music.spotui.ui.components.EmptyState("No matches yet", "Try another song, artist, or playlist name, or choose a different filter.") }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.TextButton(onClick={radioMode=false}) {Text("Create Radio",color=com.music.spotui.ui.theme.AppPalette)}
                    androidx.compose.material3.TextButton(onClick={radioMode=true}) {Text("Create Radio Mix",color=com.music.spotui.ui.theme.AppPalette)}
                }
            }
            if(selectedFilter in listOf("All","USpot Radios") && radioMatches.isNotEmpty()) {
                item { SearchSectionHeader("Your USpot Radios") }
                items(radioMatches.size) { index ->
                    val seed=radioMatches[index]
                    Row(Modifier.fillMaxWidth().clickable {navController.navigate("radio/"+android.net.Uri.encode(seed.spotifyTrackId.ifBlank{seed.id.toString()}))}.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                        GlideImage(model=seed.coverUri,contentDescription=null,modifier=Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),contentScale=ContentScale.Crop)
                        Column(Modifier.weight(1f).padding(start=12.dp)) {
                            Text(com.music.spotui.data.preferences.RadioSessions.stationName(seed),color=com.music.spotui.ui.theme.AppText,fontWeight=FontWeight.Bold)
                            Text("USpot Radio",color=com.music.spotui.ui.theme.AppSecondary,fontSize=12.sp)
                        }
                    }
                }
            }
            items(if(selectedFilter=="USpot Radios")0 else mixed.size) { i ->
                val kind = when(mixed[i]){is SearchRow.Song->"Songs";is SearchRow.Artist->"Artists";is SearchRow.Album->"Albums";is SearchRow.Playlist->"Spotify playlists"}
                val previous = mixed.getOrNull(i-1)?.javaClass
                if(i==0) SearchSectionHeader(if(selectedFilter=="All")"Top result"else kind)
                else if(i==1 || previous != mixed[i].javaClass)SearchSectionHeader(kind)
                Column(Modifier.fillMaxWidth().then(if(i==0 && selectedFilter=="All")Modifier.padding(horizontal=12.dp,vertical=8.dp).clip(RoundedCornerShape(18.dp)).background(com.music.spotui.ui.theme.AppSurface)else Modifier)) {
                when (val row = mixed[i]) {
                    is SearchRow.Song -> com.music.spotui.ui.components.SwipeToQueue(row.song) {
                        SearchSongRow(row.song, searchedList, searchViewModel, onPlayed = {
                            recordRecent(row.song.toRecentItem())
                        }, onLongClick = { menuSong = row.song }, featured = i==0 && selectedFilter=="All")
                    }
                    is SearchRow.Artist -> SearchArtistRow(row.artist) {
                        recordRecent(com.music.spotui.data.preferences.RecentItem(
                            type = "artist",
                            key = row.artist.id.ifBlank { row.artist.name },
                            name = row.artist.name,
                            image = row.artist.coverUri,
                        ))
                        navController.navigate(artistRoute(row.artist.name, row.artist.id))
                    }
                    is SearchRow.Playlist -> SearchPlaylistRow(row.playlist) {
                        recordRecent(com.music.spotui.data.preferences.RecentItem(
                            type = "playlist", key = row.playlist.spotifyId,
                            name = row.playlist.name, image = row.playlist.coverUri,
                        ))
                        navController.navigate(com.music.spotui.ui.navigation.playlistRoute(row.playlist.spotifyId, row.playlist.name))
                    }
                    is SearchRow.Album -> SearchAlbumRow(row.album) {
                        recordRecent(com.music.spotui.data.preferences.RecentItem(
                            type = "album",
                            key = row.album.name,
                            name = row.album.name,
                            singer = row.album.artists,
                            image = row.album.coverUri,
                        ))
                        navController.navigate(albumRoute(row.album.name, row.album.artists))
                    }
                }
            }
            }
            if (selectedFilter!="USpot Radios" && (results.nextOffset != null || pageError.isNotBlank())) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        if (pageError.isNotBlank()) Text(pageError, color = com.music.spotui.ui.theme.AppSecondary)
                        Text(if (loadingMore) "Loading more…" else "Load more results",
                            color = Color(0xFF1DB954),
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !loadingMore) { searchViewModel.loadMore() }.padding(vertical = 16.dp))
                    }
                }
            }
            // ── Podcasts: shows (→ detail) then individual episodes (→ play) ──
            if (filteredResults.shows.isNotEmpty()) {
                item { SearchSectionHeader("Podcasts") }
                items(filteredResults.shows.size) { i ->
                    val show = filteredResults.shows[i]
                    SearchShowRow(show) {
                        recordRecent(com.music.spotui.data.preferences.RecentItem(
                            type = "show",
                            key = show.id,
                            name = show.name,
                            singer = show.publisher,
                            image = show.coverUri,
                        ))
                        navController.navigate(showRoute(show.id, show.name))
                    }
                }
            }
            if (filteredResults.episodes.isNotEmpty()) {
                item { SearchSectionHeader("Episodes") }
                items(filteredResults.episodes.size) { i ->
                    val ep = filteredResults.episodes[i]
                    SearchSongRow(ep, filteredResults.episodes, searchViewModel, onPlayed = {
                        recordRecent(ep.toRecentItem())
                    }, onLongClick = { menuSong = ep })
                }
            }
        }

    }
}

/** A single row in the search results: a track, an artist, or an album. */
sealed class SearchRow {
    data class Song(val song: SongsModel) : SearchRow()
    data class Artist(val artist: com.music.spotui.data.entity.ArtistsModel) : SearchRow()
    data class Album(val album: com.music.spotui.data.entity.AlbumsModel) : SearchRow()
    data class Playlist(val playlist: com.music.spotui.data.entity.LibraryEntry) : SearchRow()
}

/**
 * Interleaves the three result types into one list, weighted toward songs
 * (2 songs per artist+album cycle) so the list reads as mixed rather than
 * grouped, while songs — the most common search intent — stay prominent.
 */
private fun mixSearchResults(results: SearchResults): List<SearchRow> {
    val songs = results.songs.iterator()
    val artists = results.artists.iterator()
    val albums = results.albums.iterator()
    val playlists = results.playlists.iterator()
    val out = ArrayList<SearchRow>()
    while (songs.hasNext() || artists.hasNext() || albums.hasNext() || playlists.hasNext()) {
        repeat(2) { if (songs.hasNext()) out += SearchRow.Song(songs.next()) }
        if (artists.hasNext()) out += SearchRow.Artist(artists.next())
        if (albums.hasNext()) out += SearchRow.Album(albums.next())
        if (playlists.hasNext()) out += SearchRow.Playlist(playlists.next())
    }
    return out
}

/** Maps a tapped search-result song to a persisted recent item. */
private fun SongsModel.toRecentItem() = com.music.spotui.data.preferences.RecentItem(
    type = "song",
    key = spotifyTrackId.ifBlank { url },
    name = title,
    singer = singer,
    image = coverUri,
    songId = id,
    songAlbum = album,
    songUrl = url,
    spotifyTrackId = spotifyTrackId,
    explicit = explicit,
    durationMs = durationMs,
)

/** A recent item row (song/artist/album/show the user opened), with remove (x). */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun RecentItemRow(
    item: com.music.spotui.data.preferences.RecentItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(16.dp, 8.dp),
    ) {
        GlideImage(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(if (item.type == "artist") 100.dp else 6.dp)),
            model = item.image,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 8.dp),
        ) {
            Text(text = item.name, color = com.music.spotui.ui.theme.AppText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            val subtitle = when (item.type) {
                "song" -> "Song • ${item.singer}"
                "artist" -> "Artist"
                "album" -> "Album • ${item.singer}"
                "show" -> "Podcast" + (if (item.singer.isNotBlank()) " • ${item.singer}" else "")
                "playlist" -> "Playlist"
                else -> ""
            }
            Text(text = subtitle, color = com.music.spotui.ui.theme.AppSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Icon(
            imageVector = Icons.Default.Close,
            tint = Color(0xFFB3B3B3),
            modifier = Modifier
                .size(20.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onRemove() },
            contentDescription = "Remove",
        )
    }
}

@RequiresApi(Build.VERSION_CODES.S)
@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun SearchSongRow(
    song: SongsModel,
    songList: List<SongsModel>,
    searchViewModel: SearchViewModel,
    onPlayed: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    featured: Boolean = false,
) {
    val context = LocalContext.current
    var isLiked by com.music.spotui.ui.components.rememberSongLiked(context, song.id.toString())
    val likeState = searchViewModel.likeState.value
    val currentPlayingIndicatorColor =
        if (song.id == searchViewModel.currentSongId.value) Color(AppPalette.toArgb()) else com.music.spotui.ui.theme.AppText

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onLongClick,
                onClick = {
                    onPlayed()
                    // Play the selected song without creating or saving a Radio.
                    searchViewModel.startSong(song)
                    SongPlayer.playSong(song.url, context)
                    searchViewModel.updateSongState(
                        song.coverUri,
                        song.title,
                        song.singer,
                        true,
                        song.id,
                        0,
                        song.album,
                    )
                },
            ),
    ) {
        Row(
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        ) {
            GlideImage(
                modifier = Modifier
                    .padding(0.dp, 0.dp, 10.dp, 0.dp)
                    .size(if(featured)72.dp else 48.dp)
                    .clip(RoundedCornerShape(6.dp)),
                model = song.coverUri,
                contentScale = ContentScale.Crop,
                failure = placeholder(R.drawable.placeholder),
                loading = placeholder(R.drawable.placeholder),
                contentDescription = "",
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = song.title, color = currentPlayingIndicatorColor, fontSize = if(featured)18.sp else 14.sp, fontWeight = FontWeight.SemiBold, maxLines = if(featured)2 else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(text = "Song • ${song.singer}", color = com.music.spotui.ui.theme.AppSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }

        if(song.id == searchViewModel.currentSongId.value)com.music.spotui.ui.components.PlayingBars(searchViewModel.currentSongPlayingState.value)
        if (onLongClick != null) com.music.spotui.ui.components.SongOverflowButton(song.title) { onLongClick() }
        Icon(
            modifier = Modifier
                .size(48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (isLiked) removeLikedSongId(context, song.id.toString())
                    else addLikedSongId(context, song.id.toString())
                    isLiked = isSongLiked(context, song.id.toString())
                    com.music.spotui.data.api.SpotifySync.setTrackSaved(context, song.spotifyTrackId, isLiked)
                    searchViewModel.updateLikeState(!searchViewModel.likeState.value)
                }.padding(14.dp),
            painter = if (isLiked) painterResource(id = R.drawable.added) else painterResource(id = R.drawable.ic_add),
            tint = if (isLiked) com.music.spotui.ui.theme.AppPalette else com.music.spotui.ui.theme.AppSecondary,
            contentDescription = if(isLiked)"Unlike ${song.title}"else"Like ${song.title}",
        )
    }
}

@Composable
fun SearchSectionHeader(title: String) {
    Text(
        text = title,
        color = com.music.spotui.ui.theme.AppText,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(16.dp, 18.dp, 16.dp, 4.dp),
    )
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchShowRow(show: com.music.spotui.data.entity.PodcastModel, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        GlideImage(
            modifier = Modifier
                .padding(0.dp, 0.dp, 10.dp, 0.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
            model = show.coverUri,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column {
            Text(text = show.name, color = com.music.spotui.ui.theme.AppText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                text = "Podcast" + (if (show.publisher.isNotBlank()) " • ${show.publisher}" else ""),
                color = com.music.spotui.ui.theme.AppSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchArtistRow(artist: com.music.spotui.data.entity.ArtistsModel, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        GlideImage(
            modifier = Modifier
                .padding(0.dp, 0.dp, 10.dp, 0.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(100.dp)),
            model = artist.coverUri,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column {
            Text(text = artist.name, color = com.music.spotui.ui.theme.AppText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = "Artist", color = com.music.spotui.ui.theme.AppSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchAlbumRow(album: com.music.spotui.data.entity.AlbumsModel, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        GlideImage(
            modifier = Modifier
                .padding(0.dp, 0.dp, 10.dp, 0.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
            model = album.coverUri,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column {
            Text(text = album.name, color = com.music.spotui.ui.theme.AppText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = "Album • ${album.artists}", color = com.music.spotui.ui.theme.AppSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** Spotify's "Browse all" categories: name + tile colour + the search query it runs. */
private val browseCategories: List<Triple<String, Color, String>> = listOf(
    Triple("Music", Color(0xFFDC148C), "Top hits"),
    Triple("Podcasts", Color(0xFF1E3264), "Podcast"),
    Triple("Made For You", Color(0xFF8768A8), "Discover weekly"),
    Triple("New Releases", Color(0xFFE8115B), "New releases"),
    Triple("Pop", Color(0xFF8D67AB), "Pop"),
    Triple("Hip-Hop", Color(0xFF477D95), "Hip hop"),
    Triple("Rock", Color(0xFFE61E32), "Rock"),
    Triple("Latin", Color(0xFFE1118C), "Latin"),
    Triple("Country", Color(0xFFD84000), "Country"),
    Triple("R&B", Color(0xFFBA5D07), "R&B"),
    Triple("K-Pop", Color(0xFF148A08), "K-pop"),
    Triple("Indie", Color(0xFF608108), "Indie"),
    Triple("Dance/Electronic", Color(0xFF056952), "Electronic dance"),
    Triple("Metal", Color(0xFF777777), "Metal"),
    Triple("Chill", Color(0xFF1E3264), "Chill"),
    Triple("Charts", Color(0xFF8C1932), "Top charts"),
    Triple("Workout", Color(0xFF777777), "Workout"),
    Triple("Jazz", Color(0xFF503750), "Jazz"),
)

@Composable
fun BrowseAllSection(onCategoryClick: (genre: String, title: String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Browse all",
            color = com.music.spotui.ui.theme.AppText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 8.dp),
        )
        browseCategories.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp, 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems.forEach { (name, color, query) ->
                    BrowseCategoryTile(name, color, query, Modifier.weight(1f)) { onCategoryClick(query, name) }
                }
                // Keep a half-width spacer if the last row has a single tile.
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun BrowseCategoryTile(
    name: String,
    color: Color,
    query: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    // Cover of the category's top playlist, shown tilted in the corner like
    // Spotify web's browse tiles. Empty until resolved (cached per session).
    val cover by androidx.compose.runtime.produceState(initialValue = "", key1 = query) {
        value = com.music.spotui.data.api.BrowseTileImages.coverFor(context, query)
    }
    Box(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        if (cover.isNotBlank()) {
            GlideImage(
                model = cover,
                contentScale = ContentScale.Crop,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(64.dp)
                    .offset(x = 16.dp, y = 10.dp)
                    .rotate(25f)
                    .clip(RoundedCornerShape(4.dp)),
            )
        }
        Text(
            text = name,
            color = com.music.spotui.ui.theme.AppText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
fun SearchTopBar() {
    Row(horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(text = "Search", color = com.music.spotui.ui.theme.AppText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        //Icon(imageVector = Icons.Default.Person, contentDescription = "", tint = com.music.spotui.ui.theme.AppText)
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun SearchStickyBar(
    text: String,
    onFocusChange: (Boolean) -> Unit = {},
    onBack: () -> Unit = {},
    onTextChange: (String) -> Unit,
) {

    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .clip(RoundedCornerShape(10.dp))
            .height(55.dp)
            .background(com.music.spotui.ui.theme.AppRaised)
            .padding(10.dp, 0.dp)
    ){
        androidx.compose.material3.IconButton(onClick=onBack) { Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,contentDescription="Back",tint=com.music.spotui.ui.theme.AppText) }
        Icon(
            painterResource(id = R.drawable.ic_search_big),
            tint = com.music.spotui.ui.theme.AppSecondary,
            contentDescription = "Search")

        TextField(
            enabled = true,
            modifier = Modifier.weight(1f).onFocusChanged { onFocusChange(it.isFocused) },
            value = text,
            textStyle = TextStyle.Default.copy(fontSize = 16.sp, color = com.music.spotui.ui.theme.AppText, fontWeight = FontWeight(500)),
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = com.music.spotui.ui.theme.AppPalette

            ),
            trailingIcon = { if(text.isNotEmpty()) androidx.compose.material3.IconButton(onClick={onTextChange("")}) { Text("×",color=com.music.spotui.ui.theme.AppText,fontSize=24.sp) } },
            singleLine = true,
            onValueChange = onTextChange,
            placeholder = {
                Text(
                     textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    text = "What do you want to listen to?"

                )
            }
        )
    }
}


@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun SearchPlaylistRow(playlist: com.music.spotui.data.entity.LibraryEntry, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp, 8.dp)) {
        GlideImage(model = playlist.coverUri, contentDescription = playlist.name,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder), loading = placeholder(R.drawable.placeholder))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(playlist.name, color = com.music.spotui.ui.theme.AppText, fontSize = 14.sp,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("Spotify playlist", color = com.music.spotui.ui.theme.AppSecondary, fontSize = 12.sp,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}
