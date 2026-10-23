package com.music.spotui.ui.screens

import com.music.spotui.ui.components.motionClick
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.Response
import com.music.spotui.data.api.SpotifySession
import com.music.spotui.data.entity.AccountModel
import com.music.spotui.R
import com.music.spotui.ui.components.playbackGlow
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.preferences.isLibraryGridView
import com.music.spotui.data.preferences.setLibraryGridView
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.Snackbar
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(navController: NavController) {

    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val entries by libraryViewModel.entries.collectAsState()
    val account by libraryViewModel.account.collectAsState()
    var showAccount by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Spotify-style layout toggle: rows or a 3-column grid, persisted across runs.
    var gridView by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(isLibraryGridView(context)) }
    var searchActive by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var searchQuery by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val pinRevision by com.music.spotui.data.preferences.LibraryPins.revision.collectAsState()
    val sort = "Recent"
    var libraryType by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("All") }
    var removal by remember { mutableStateOf<LibraryEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    val busy by libraryViewModel.busy.collectAsState()
    val actionError by libraryViewModel.actionError.collectAsState()
    if (actionError.isNotBlank() && !creating) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { libraryViewModel.clearActionError() },
            containerColor = com.music.spotui.ui.theme.AppSurface, titleContentColor = com.music.spotui.ui.theme.AppText, textContentColor = com.music.spotui.ui.theme.AppSecondary,
            title = { Text("Spotify change failed") }, text = { Text(actionError) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://open.spotify.com/collection/playlists")))
                libraryViewModel.clearActionError()
            }) { Text("Open Spotify", color = Color(0xFF1ED760)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { libraryViewModel.clearActionError() }) { Text("Close", color = com.music.spotui.ui.theme.AppText) } })
    }
    val message by libraryViewModel.message.collectAsState()
    androidx.compose.runtime.LaunchedEffect(message) {
        if (message.isNotBlank()) {
            com.music.spotui.ui.components.ActionFeedback.show(message)
            libraryViewModel.clearMessage()
        }
    }
    removal?.let { entry ->
        androidx.compose.material3.AlertDialog(
            containerColor = com.music.spotui.ui.theme.AppSurface, titleContentColor = com.music.spotui.ui.theme.AppText, textContentColor = com.music.spotui.ui.theme.AppSecondary,
            onDismissRequest = { removal = null },
            title = { Text(if (entry.isPlaylist) "Remove playlist from library?" else "Remove saved album?") },
            text = { Text(if (entry.isPlaylist)
                "Unfollow ${entry.name} in your Spotify account. This keeps the playlist and its songs, including playlists you own. It cannot be undone automatically; you can follow it again."
                else "Unsave ${entry.name} in your Spotify account. It cannot be undone automatically; you can save it again.") },
            confirmButton = { androidx.compose.material3.TextButton(enabled = !busy, onClick = { libraryViewModel.remove(entry); removal = null }) { Text("Remove", color = Color(0xFF1DB954)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { removal = null }) { Text("Cancel", color = com.music.spotui.ui.theme.AppText) } }
        )
    }
    if (creating) CreatePlaylistSheet(libraryViewModel, navController) { creating = false; libraryViewModel.finishCreation() }

    if (showAccount) {
        AccountSheet(
            account = (account as? Response.Success)?.data ?: AccountModel(),
            navController = navController,
            onDismiss = { showAccount = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
            .statusBarsPadding()
    ) {
        // Header: title + account avatar. Built by hand (rather than a fixed-height
        // TopAppBar) so the title isn't clipped under the status bar.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp, 12.dp, 16.dp, 8.dp)
        ) {
            Text(
                text = "Your Library",
                fontWeight = FontWeight.Bold,
                color = com.music.spotui.ui.theme.AppText,
                fontSize = 26.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search library",
                tint = if (searchActive) Color(AppPalette.toArgb()) else com.music.spotui.ui.theme.AppText,
                modifier = Modifier
                    .size(48.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        searchActive = !searchActive
                        if (!searchActive) searchQuery = ""
                    }.padding(13.dp),
            )
            Icon(
                painter = painterResource(R.drawable.ic_add), contentDescription = "Create playlist", tint = com.music.spotui.ui.theme.AppText,
                modifier = Modifier.size(48.dp).motionClick(enabled = !busy) { creating = true }.padding(12.dp)
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .motionClick { showAccount = true },
                contentAlignment = Alignment.Center
            ) {
                val avatar = (account as? Response.Success)?.data?.imageUrl.orEmpty()
                if (avatar.isNotBlank()) {
                    AccountAvatar(avatar, 34.dp)
                } else {
                    Icon(Icons.Default.Person, contentDescription = "Account", tint = com.music.spotui.ui.theme.AppText, modifier = Modifier.size(20.dp))
                }
            }
        }

        // Grid/list switch, like the icon at the top-right of Spotify's library.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp, 0.dp, 16.dp, 4.dp)
        ) {
            Row(Modifier.weight(1f).horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Playlists", "Albums", "Radios").forEach { label ->
                    com.music.spotui.ui.components.ThemeFilterPill(label, libraryType == label) { libraryType = label }
                }
            }
            Icon(
                painter = painterResource(if (gridView) R.drawable.ic_view_list else R.drawable.ic_view_grid),
                contentDescription = if (gridView) "Show as list" else "Show as grid",
                tint = com.music.spotui.ui.theme.AppText,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        gridView = !gridView
                        setLibraryGridView(context, gridView)
                    }
            )
        }

        AnimatedVisibility(visible = searchActive) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp, 0.dp, 16.dp, 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(com.music.spotui.ui.theme.AppRaised)
                    .padding(horizontal = 12.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(color = com.music.spotui.ui.theme.AppText, fontSize = 15.sp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp, 10.dp),
                    decorationBox = { inner ->
                        Box {
                            if (searchQuery.isEmpty()) Text("Filter your library…", color = Color(0xFF888888), fontSize = 15.sp)
                            inner()
                        }
                    },
                )
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = Color(0xFF888888),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { searchQuery = "" },
                    )
                }
            }
        }

        val followedArtists by libraryViewModel.followedArtists.collectAsState()
        com.music.spotui.ui.components.PageMotion(libraryType to gridView, Modifier.weight(1f)) { (shownType, shownGrid) ->
        if(shownType=="Radios")com.music.spotui.ui.components.SavedRadiosLibrary(navController,searchQuery)
        else when (entries) {
            is Response.Loading -> LibrarySkeleton(PaddingValues(0.dp))
            is Response.Success -> {
                val allEntries = com.music.spotui.data.preferences.LibraryPins.arrange(context,(entries as Response.Success).data,sort).also { pinRevision }.filter { entry ->
                    when (shownType) { "Playlists" -> entry.isPlaylist; "Albums" -> !entry.isPlaylist; else -> true }
                }
                val filteredEntries = if (searchQuery.isBlank()) allEntries
                    else allEntries.filter {
                        it.name.contains(searchQuery, ignoreCase = true) ||
                        it.subtitle.contains(searchQuery, ignoreCase = true)
                    }
                val visibleArtists = if (shownType == "All") followedArtists else emptyList()
                val filteredArtists = if (searchQuery.isBlank()) visibleArtists
                    else visibleArtists.filter { it.name.contains(searchQuery, ignoreCase = true) }
                if (shownGrid) LibraryGridScreen(PaddingValues(0.dp), filteredEntries, filteredArtists, navController, onRemove = { removal = it }, showUtilities = shownType == "All" && searchQuery.isBlank())
                else SumUpLibraryScreen(PaddingValues(0.dp), filteredEntries, filteredArtists, navController, onRemove = { removal = it }, showUtilities = shownType == "All" && searchQuery.isBlank())
            }
            else -> com.music.spotui.ui.components.RetryPanel("Check your connection and try loading your library again.") { libraryViewModel.load() }
        }
        }
    }
}

@Composable
private fun LibraryQuickAccess(navController: NavController, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(modifier = modifier.fillMaxWidth(), color = com.music.spotui.ui.theme.AppSurface, shape = RoundedCornerShape(16.dp)) {
        Column {
            listOf(Triple("Listening history", "Your recent plays", Routes.History.route), Triple("Local files", "Music on this device", Routes.LocalFiles.route)).forEachIndexed { index, (title, subtitle, route) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).motionClick { navController.navigate(route) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(com.music.spotui.ui.theme.AppRaised.copy(alpha = .6f)), contentAlignment = Alignment.Center) {
                        if (index == 0) Icon(Icons.Default.DateRange, null, tint = com.music.spotui.ui.theme.AppPalette, modifier = Modifier.size(21.dp))
                        else Icon(painterResource(R.drawable.ic_library_big), null, tint = com.music.spotui.ui.theme.AppPalette, modifier = Modifier.size(21.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(title, color = com.music.spotui.ui.theme.AppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(subtitle, color = com.music.spotui.ui.theme.AppSecondary, fontSize = 12.sp)
                    }
                    Text("›", color = com.music.spotui.ui.theme.AppSecondary, fontSize = 22.sp)
                }
                if (index == 0) HorizontalDivider(Modifier.padding(start = 66.dp, end = 14.dp), color = com.music.spotui.ui.theme.AppRaised.copy(alpha = .5f))
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun AccountAvatar(url: String, size: androidx.compose.ui.unit.Dp) {
    GlideImage(
        modifier = Modifier.size(size).clip(CircleShape),
        model = url,
        contentScale = ContentScale.Crop,
        contentDescription = ""
    )
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SumUpLibraryScreen(
    padding: PaddingValues,
    entries: List<LibraryEntry>,
    followedArtists: List<com.music.spotui.data.entity.ArtistsModel>,
    navController: NavController,
    onRemove: (LibraryEntry) -> Unit = {},
    showUtilities: Boolean = true
) {
    LazyColumn(
        contentPadding = PaddingValues(top = 10.dp, bottom = com.music.spotui.ui.navigation.LocalBottomOverlayInset.current + 24.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(com.music.spotui.ui.theme.AppBackground)
    ) {
        if (showUtilities) item(key = "library-tools") { LibraryQuickAccess(navController, Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
        if (entries.isEmpty() && followedArtists.isEmpty()) item(key = "library-empty") {
            com.music.spotui.ui.components.EmptyState("Nothing here yet", "Your saved music will appear here. Find something you love to get started.", "Search music") { navController.navigate(Routes.Search.route) }
        }
        items(com.music.spotui.data.recommendation.LibraryIdentityPolicy.unique(entries), key = { com.music.spotui.data.recommendation.LibraryIdentityPolicy.key(it) }) { entry ->
            val player: com.music.spotui.ui.viewmodel.PlayerViewModel = hiltViewModel()
            val active = libraryPlaybackActive(entry, player.activeCollection.value, player.currentSongId.value)
            LibrarySwipe(entry, onRemove) {
            Row(
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp, 6.dp).playbackGlow(active).clip(RoundedCornerShape(16.dp)).background(com.music.spotui.ui.theme.AppSurface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { openLibraryEntry(entry, navController) }.padding(10.dp)
            ) {
                com.music.spotui.ui.components.LibraryCollectionArtwork(entry.coverUri, entry.name, Modifier.size(52.dp))
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(text = entry.name, color = com.music.spotui.ui.theme.AppText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text = entry.subtitle, color = com.music.spotui.ui.theme.AppSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (active) com.music.spotui.ui.components.PlayingBars(player.currentSongPlayingState.value)
                com.music.spotui.ui.components.PinButton(com.music.spotui.data.preferences.LibraryPins.key(entry))
            }
        }
        // ── Artists the user follows on Spotify ──
        }
        if (followedArtists.isNotEmpty()) {
            item {
                Text(
                    text = "Artists you follow",
                    color = com.music.spotui.ui.theme.AppText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 4.dp),
                )
            }
            items(followedArtists.distinctBy { it.id.ifBlank { it.name } }, key = { "artist:${it.id.ifBlank { it.name }}" }) { artist ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp, 6.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { navController.navigate(artistRoute(artist.name, artist.id)) }
                ) {
                    GlideImage(
                        modifier = Modifier
                            .size(55.dp)
                            .clip(CircleShape),
                        model = artist.coverUri,
                        contentScale = ContentScale.Crop,
                        contentDescription = ""
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(text = artist.name, color = com.music.spotui.ui.theme.AppText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(text = "Artist", color = com.music.spotui.ui.theme.AppSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

private fun libraryPlaybackActive(entry: LibraryEntry, collection: com.music.spotui.data.entity.PlaybackCollection?, currentId: Int): Boolean {
    if (entry.spotifyId == Api.HomeCache.DOWNLOADS_ID) return collection?.key == "local:downloads"
    if (entry.spotifyId == Api.HomeCache.LIKED_SONGS_ID) return collection?.key == "playlist:liked-songs"
    val item = if (entry.isPlaylist) com.music.spotui.data.entity.HomeItem.Playlist(entry.name, entry.coverUri, entry.subtitle, entry.spotifyId)
        else com.music.spotui.data.entity.HomeItem.Album(entry.name, entry.coverUri, entry.subtitle, entry.artists)
    return com.music.spotui.data.recommendation.PlaybackHighlightPolicy.active(item, collection, currentId)
}

private fun openLibraryEntry(entry: LibraryEntry, navController: NavController) {
    if (entry.spotifyId == Api.HomeCache.LIKED_SONGS_ID) navController.navigate(Routes.Liked.route)
    else if (entry.spotifyId == Api.HomeCache.DOWNLOADS_ID) navController.navigate(Routes.Downloads.route)
    else if (entry.isPlaylist) navController.navigate(playlistRoute(entry.spotifyId, entry.name))
    else navController.navigate(albumRoute(entry.name, entry.artists))
}

/**
 * Grid layout of the same library content: 3 columns of square covers with the
 * title/subtitle underneath, like Spotify's "Grid" library view. Followed
 * artists render as circles under their own full-width header.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun LibraryGridScreen(
    padding: PaddingValues,
    entries: List<LibraryEntry>,
    followedArtists: List<com.music.spotui.data.entity.ArtistsModel>,
    navController: NavController,
    onRemove: (LibraryEntry) -> Unit = {},
    showUtilities: Boolean = true
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(com.music.spotui.ui.theme.AppBackground),
        contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, com.music.spotui.ui.navigation.LocalBottomOverlayInset.current + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (showUtilities) item(key = "library-tools", span = { GridItemSpan(maxLineSpan) }) { LibraryQuickAccess(navController) }
        if (entries.isEmpty() && followedArtists.isEmpty()) item(key = "library-empty", span = { GridItemSpan(maxLineSpan) }) {
            com.music.spotui.ui.components.EmptyState("Nothing here yet", "Your saved music will appear here. Find something you love to get started.", "Search music") { navController.navigate(Routes.Search.route) }
        }
        items(com.music.spotui.data.recommendation.LibraryIdentityPolicy.unique(entries), key = { com.music.spotui.data.recommendation.LibraryIdentityPolicy.key(it) }) { entry ->
            val player: com.music.spotui.ui.viewmodel.PlayerViewModel = hiltViewModel()
            val active = libraryPlaybackActive(entry, player.activeCollection.value, player.currentSongId.value)
            LibrarySwipe(entry, onRemove) {
            Column(
                modifier = Modifier.playbackGlow(active).clip(RoundedCornerShape(16.dp)).background(com.music.spotui.ui.theme.AppSurface).padding(10.dp).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { openLibraryEntry(entry, navController) }
            ) {
                com.music.spotui.ui.components.LibraryCollectionArtwork(entry.coverUri, entry.name, Modifier.fillMaxWidth().aspectRatio(1f))
                Spacer(modifier = Modifier.height(6.dp))
                com.music.spotui.ui.components.PinButton(com.music.spotui.data.preferences.LibraryPins.key(entry))
                Text(text = entry.name, color = com.music.spotui.ui.theme.AppText, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = entry.subtitle, color = com.music.spotui.ui.theme.AppSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        }
        if (followedArtists.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Artists you follow",
                    color = com.music.spotui.ui.theme.AppText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(followedArtists.distinctBy { it.id.ifBlank { it.name } }, key = { "artist:${it.id.ifBlank { it.name }}" }) { artist ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { navController.navigate(artistRoute(artist.name, artist.id)) }
                ) {
                    GlideImage(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(CircleShape),
                        model = artist.coverUri,
                        contentScale = ContentScale.Crop,
                        contentDescription = ""
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = artist.name, color = com.music.spotui.ui.theme.AppText, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(text = "Artist", color = com.music.spotui.ui.theme.AppSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun LibrarySkeleton(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(com.music.spotui.ui.theme.AppBackground)
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        repeat(8) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp, 6.dp)
            ) {
                Box(modifier = Modifier.size(55.dp).clip(RoundedCornerShape(4.dp)).background(com.music.spotui.ui.theme.AppBackground))
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Box(modifier = Modifier.height(14.dp).width(160.dp).clip(RoundedCornerShape(3.dp)).background(com.music.spotui.ui.theme.AppBackground))
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(modifier = Modifier.height(11.dp).width(90.dp).clip(RoundedCornerShape(3.dp)).background(com.music.spotui.ui.theme.AppBackground))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSheet(
    account: AccountModel,
    navController: NavController,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = com.music.spotui.ui.theme.AppSurface,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp, 16.dp, 16.dp)
            ) {
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(com.music.spotui.ui.theme.AppRaised),
                    contentAlignment = Alignment.Center
                ) {
                    if (account.imageUrl.isNotBlank()) AccountAvatar(account.imageUrl, 56.dp)
                    else Icon(Icons.Default.Person, contentDescription = null, tint = com.music.spotui.ui.theme.AppText, modifier = Modifier.size(30.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(account.name.ifBlank { "Spotify user" }, color = com.music.spotui.ui.theme.AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (account.email.isNotBlank()) Text(account.email, color = com.music.spotui.ui.theme.AppSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (account.plan.isNotBlank()) Text("Spotify ${account.plan}", color = Color(0xFF1DB954), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            HorizontalDivider(color = com.music.spotui.ui.theme.AppRaised)

            AccountRow("Settings") { onDismiss(); navController.navigate(Routes.Settings.route) }
            AccountRow("Listening history") {
                onDismiss()
                navController.navigate(Routes.History.route)
            }
            HorizontalDivider(color = com.music.spotui.ui.theme.AppRaised)
            AccountRow("Log out", tint = Color(0xFFE57373)) {
                SpotifySession.setSpDc(context, "")
                Api.HomeCache.clear()
                onDismiss()
                navController.navigate(Routes.Login.route) {
                    popUpTo(0) { inclusive = true }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AccountRow(label: String, tint: Color = com.music.spotui.ui.theme.AppText, onClick: () -> Unit) {
    Text(
        text = label,
        color = tint,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(20.dp, 16.dp)
    )
}
