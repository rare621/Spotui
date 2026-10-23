package com.music.spotui.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
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
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.preferences.RadioSessions
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.playbackGlow
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.theme.GridBackground
import com.music.spotui.ui.viewmodel.HomeViewModel
import java.time.LocalTime


@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeScreen(navController: NavController){

    val context = LocalContext.current
    var historyRevision by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val listeningRevision by com.music.spotui.data.preferences.ListeningHistorySignals.revision.collectAsState()
    val personalizationRevision by com.music.spotui.data.preferences.HomePersonalization.revision.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val imported = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.music.spotui.data.api.SpotifyHistorySync.refresh(context)
        }
        if (imported) historyRevision++
    }
    val homeViewModel : HomeViewModel = hiltViewModel()
    val home by homeViewModel.home.collectAsState()
    val discovered by homeViewModel.discovery.collectAsState()
    val albums by homeViewModel.albums.collectAsState()
    val artists by homeViewModel.artists.collectAsState()
    val presentation by homeViewModel.presentation.collectAsState()
    val renderRevision by homeViewModel.renderRevision.collectAsState()
    val backupRevision by com.music.spotui.ui.viewmodel.BackupSignals.revision.collectAsState()
    val accountName = com.music.spotui.data.api.ProfileCache.name
    val baseFeed = remember(home, albums, artists) {
        val real = (home as? Response.Success)?.data
        val albumItems = (albums as? Response.Success)?.data.orEmpty()
        val artistItems = (artists as? Response.Success)?.data.orEmpty()
        when {
            real != null && real.sections.isNotEmpty() -> real
            home is Response.Loading -> null
            albumItems.isNotEmpty() || artistItems.isNotEmpty() -> HomeFeedModel(sections = listOf(
                HomeSection("", albumItems.take(8).map { HomeItem.Album(it.name, it.coverUri, it.artists, it.artists) }),
                HomeSection("Albums", albumItems.map { HomeItem.Album(it.name, it.coverUri, it.artists, it.artists) }),
                HomeSection("Best of Artists", artistItems.map { HomeItem.Artist(it.name, it.coverUri, it.id) }),
                HomeSection("Discover", albumItems.takeLast(3).map { HomeItem.Album(it.name, it.coverUri, it.artists, it.artists) })
            ))
            else -> null
        }
    }
    // This effect stays mounted while Following/Stats are displayed. Tab selection is not a refresh key.
    androidx.compose.runtime.LaunchedEffect(baseFeed, discovered, listeningRevision, personalizationRevision,
        renderRevision, backupRevision, historyRevision, accountName) {
        homeViewModel.prepareHome(context, baseFeed ?: HomeFeedModel(), discovered,
            listOf(listeningRevision, personalizationRevision, renderRevision, backupRevision, historyRevision.toLong()), accountName)
    }
    var followingSelected by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var statsSelected by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val musicListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val homeSavedState = remember(navController) { navController.getBackStackEntry(Routes.Home.route).savedStateHandle }
    val resetHome by homeSavedState.getStateFlow(com.music.spotui.ui.navigation.HOME_RESET_REQUEST, false).collectAsState()
    androidx.compose.runtime.LaunchedEffect(resetHome) {
        if (resetHome) {
            followingSelected = false
            statsSelected = false
            musicListState.scrollToItem(0)
            homeSavedState[com.music.spotui.ui.navigation.HOME_RESET_REQUEST] = false
        }
    }
    val following by homeViewModel.following.collectAsState()
    androidx.compose.runtime.LaunchedEffect(followingSelected) { if (followingSelected) homeViewModel.loadFollowing() }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
            .statusBarsPadding()
    ) {
        Column(Modifier.fillMaxSize().background(com.music.spotui.ui.theme.AppBackground)) {
        HomeHeaderRow(navController, followingSelected, statsSelected, { statsSelected = true; followingSelected = false }) { selected ->
            followingSelected = selected
            statsSelected = false

        }
        com.music.spotui.ui.components.PageMotion(if (statsSelected) "stats" else if (followingSelected) "following" else "music", Modifier.weight(1f)) { shownTab ->
        when {
            shownTab == "stats" -> StatsContent(navController)
            shownTab == "following" -> FollowingContent(navController, following) { homeViewModel.loadFollowing(refresh = true) }
            presentation?.let { it.topTiles.isNotEmpty() || it.sections.isNotEmpty() } == true -> HomeFeedContent(navController, presentation!!, musicListState) { item -> homeViewModel.hideHomeItem(context, item) }
            home is Response.Loading || baseFeed != null -> Loader()
            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    com.music.spotui.ui.components.RetryPanel("Check your connection and try loading your music again.") { homeViewModel.retryHome() }
                }
            }
        }
        }
        }
    }
}

private fun onHomeItemClick(navController: NavController, item: HomeItem) {
    when (item) {
        is HomeItem.Album -> navController.navigate(albumRoute(item.name, item.artists.ifBlank { item.subtitle }))
        is HomeItem.Artist -> navController.navigate(artistRoute(item.name, item.id))
        // Load the real playlist content by its Spotify id (daily mixes, etc).
        is HomeItem.Radio -> { /* Handled by the existing grid/card click handlers. */ }
        is HomeItem.RecentTrack -> { /* Played by the card's onLocal handler. */ }
        is HomeItem.Playlist ->
            if (item.id == "liked-songs") navController.navigate(Routes.Liked.route)
            else if (item.id == "downloaded-offline" || item.id == "downloads") navController.navigate(Routes.Downloads.route)
            else if (item.id.isNotBlank()) navController.navigate(playlistRoute(item.id, item.name))
            else navController.navigate(albumRoute(item.name))
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeFeedContent(navController: NavController, presentation: com.music.spotui.data.recommendation.HomePresentation,
                    listState: androidx.compose.foundation.lazy.LazyListState,
                    onRemove: (HomeItem) -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val player: com.music.spotui.ui.viewmodel.PlayerViewModel = hiltViewModel()
    var openingRadio by remember { mutableStateOf(false) }
    var removeItem by remember { androidx.compose.runtime.mutableStateOf<HomeItem?>(null) }
    removeItem?.let { item ->
        androidx.compose.material3.AlertDialog(
            containerColor = com.music.spotui.ui.theme.AppSurface, titleContentColor = com.music.spotui.ui.theme.AppText, textContentColor = com.music.spotui.ui.theme.AppSecondary,
            onDismissRequest = { removeItem = null },
            title = { Text("Remove from Home?") },
            text = { Text("Remove ${item.name} from Home history on this device. It stays in Spotify and your library.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                onRemove(item)
                com.music.spotui.ui.components.ActionFeedback.show("Removed from Home",undo={com.music.spotui.data.preferences.HomeHiddenItems.restore(context,item);com.music.spotui.data.preferences.ListeningHistorySignals.revision.value++})
                removeItem = null
            }) { Text("Remove", color = Color(0xFF1DB954)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { removeItem = null }) { Text("Cancel") } }
        )
    }
    val topTiles = presentation.topTiles
    val compactRows = presentation.sections
    fun playLocal(item: HomeItem) {
        if (item is HomeItem.RecentTrack) {
            if (player.currentSongId.value != item.song.id) {
                player.updateQueue(listOf(item.song))
                player.playSongAt(listOf(item.song), 0, context)
            }
            navController.navigate(Routes.Player.route)
            return
        }
        if (item is HomeItem.Radio && !openingRadio) {
            openingRadio = true
            scope.launch {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { RadioSessions.remember(context, item.seed) }
                    navController.navigate("radio/" + android.net.Uri.encode(item.seed.spotifyTrackId.ifBlank { item.seed.id.toString() }))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("HomeScreen", "Couldn't open saved radio", error)
                    android.widget.Toast.makeText(context, "Couldn't open Radio. Try again.", android.widget.Toast.LENGTH_SHORT).show()
                } finally { openingRadio = false }
            }
        }
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = com.music.spotui.ui.navigation.LocalBottomOverlayInset.current + 24.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        run {
            item {
                HomeShortcutGrid(navController, topTiles,
                    onLocal = { playLocal(it) }, onRemove = { removeItem = it }, hasLocalActivity = presentation.hasLocalActivity)
            }
        }
        items(compactRows.size, key = { compactRows[it].title }) { i ->
            HomeFeedSection(navController, compactRows[i], onLocal = { playLocal(it) }, onRemove = { removeItem = it })
        }

    }
}

/** Spotify-style top row: profile avatar on the left (opens Settings, like the
 *  official app's profile drawer), then the filter pills — one single row. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HomeHeaderRow(navController: NavController, followingSelected: Boolean, statsSelected: Boolean, onStats: () -> Unit, onSelection: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.music.spotui.data.api.ProfileCache.ensure(context)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp),
    ) {
        val avatarUrl = com.music.spotui.data.api.ProfileCache.imageUrl
        val initial = com.music.spotui.data.api.ProfileCache.name
            ?.trim()?.firstOrNull()?.uppercase() ?: "•"
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(start = 16.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0xFFE8622C))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { navController.navigate(Routes.Settings.route) },
        ) {
            if (avatarUrl != null) {
                GlideImage(
                    model = avatarUrl,
                    contentScale = ContentScale.Crop,
                    contentDescription = "Profile",
                    modifier = Modifier.size(34.dp),
                )
            } else {
                Text(
                    text = initial,
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        // Scrolls sideways if the pills don't fit, like Spotify's.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(start = 12.dp, end = 8.dp),
        ) {
            // Home is the music view, so "Music" is the selected (green) pill.
            HomePill("Music", selected = !followingSelected && !statsSelected) { onSelection(false) }
            HomePill("Following", selected = followingSelected) { onSelection(true) }
            HomePill("Stats", selected = statsSelected) { onStats() }
            var showMore by remember { mutableStateOf(false) }
            Box {
                HomePill("More") { showMore = true }
                androidx.compose.material3.DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Local files") }, onClick = { showMore = false; navController.navigate(Routes.LocalFiles.route) })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Listening history") }, onClick = { showMore = false; navController.navigate(Routes.History.route) })
                }
            }
        }



    }
}

@Composable
private fun HomePill(label: String, selected: Boolean = false, onClick: () -> Unit = {}) {
    com.music.spotui.ui.components.ThemeFilterPill(label, selected, onClick)
}

@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
private fun HomeShortcutGrid(navController: NavController, items: List<HomeItem>, onLocal: (HomeItem) -> Unit = {}, onRemove: (HomeItem) -> Unit = {}, hasLocalActivity: Boolean = false) {
    val player: com.music.spotui.ui.viewmodel.PlayerViewModel = hiltViewModel()
    Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("Your rotation", color = com.music.spotui.ui.theme.AppText, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            com.music.spotui.ui.components.RadioCreationMenu { stationId ->
                navController.navigate("radio/"+android.net.Uri.encode(stationId))
            }
        }
        Text(if (hasLocalActivity) "Favorites and recent listens" else "From your Spotify home", color = com.music.spotui.ui.theme.AppSecondary,
            fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 14.dp))
        val pairs = items.take(8).chunked(2)
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(pairs.size) { index ->
                Column(Modifier.width(224.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    pairs[index].forEach { item ->
                        val active = com.music.spotui.data.recommendation.PlaybackHighlightPolicy.active(item, player.activeCollection.value, player.currentSongId.value)
                        Row(Modifier.fillMaxWidth().height(72.dp).playbackGlow(active).clip(RoundedCornerShape(16.dp))
                            .background(com.music.spotui.ui.theme.AppSurface)
                            .combinedClickable(
                                onClick = { if (item is HomeItem.Radio || item is HomeItem.RecentTrack) onLocal(item) else onHomeItemClick(navController, item) },
                                onLongClick = { onRemove(item) }), verticalAlignment = Alignment.CenterVertically) {
                            GlideImage(model = item.imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.padding(8.dp).size(56.dp).clip(RoundedCornerShape(8.dp)),
                                loading = placeholder(R.drawable.placeholder), failure = placeholder(R.drawable.placeholder))
                            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(item.name, color = com.music.spotui.ui.theme.AppText, fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(when (item) { is HomeItem.Radio -> "USpot Radio"; is HomeItem.Album -> "Album"; else -> "Playlist" },
                                    color = com.music.spotui.ui.theme.AppSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                            }
                            if (active) com.music.spotui.ui.components.PlayingBars(player.currentSongPlayingState.value)
                            Spacer(Modifier.width(10.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeFeedSection(navController: NavController, section: HomeSection, onLocal: (HomeItem) -> Unit = {}, onRemove: (HomeItem) -> Unit = {}) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(section.title) { mutableStateOf(false) }
    val visibleItems = if (expanded) section.items else section.items.take(com.music.spotui.data.recommendation.HomeLayoutPolicy.CARDS_PER_ROW)
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(section.title, color = com.music.spotui.ui.theme.AppText, fontSize = 21.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (section.items.size > com.music.spotui.data.recommendation.HomeLayoutPolicy.CARDS_PER_ROW) {
            androidx.compose.material3.TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Less" else "See all", color = com.music.spotui.ui.theme.AppSecondary, fontSize = 12.sp)
            }
        }
    }
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp, 8.dp)) {
        items(visibleItems.size, key = { com.music.spotui.data.preferences.HomeHiddenItems.key(visibleItems[it]) }) { i ->
            HomeFeedCard(visibleItems[i], onRemove = { onRemove(visibleItems[i]) }) {
                val item = visibleItems[i]
                if (item is HomeItem.Radio || item is HomeItem.RecentTrack) onLocal(item) else onHomeItemClick(navController, item)
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
private fun HomeFeedCard(item: HomeItem, onRemove: () -> Unit = {}, onClick: () -> Unit) {
    val player: com.music.spotui.ui.viewmodel.PlayerViewModel = hiltViewModel()
    val active = com.music.spotui.data.recommendation.PlaybackHighlightPolicy.active(item, player.activeCollection.value, player.currentSongId.value)
    val isArtist = item is HomeItem.Artist
    val subtitle = when (item) {
        is HomeItem.Album -> item.subtitle
        is HomeItem.Playlist -> item.subtitle
        is HomeItem.Artist -> "Artist"
        is HomeItem.Radio -> "USpot Radio"
        is HomeItem.RecentTrack -> item.song.singer
    }
    Column(
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
        modifier = Modifier
            .width(142.dp)
            .padding(6.dp)
            .combinedClickable(onClick = onClick, onLongClick = onRemove),
    ) {
        Box(Modifier.size(130.dp).playbackGlow(active, if (isArtist) CircleShape else RoundedCornerShape(16.dp))) {
        GlideImage(
            modifier = Modifier
                .size(130.dp)
                .clip(if (isArtist) CircleShape else RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop,
            model = item.imageUrl,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
            if (active) com.music.spotui.ui.components.PlaybackBadge(player.currentSongPlayingState.value,
                Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.name,
            color = com.music.spotui.ui.theme.AppText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )
        Text(
            text = subtitle,
            color = com.music.spotui.ui.theme.AppSecondary,
            fontSize = 11.sp,
            maxLines = 2,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun SumUpHomeScreen(navController : NavController, albums: List<AlbumsModel>, artists: List<ArtistsModel>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(Color(AppBackground.toArgb()))
    ){

        GreetingSection()
        //ChipSection(chip = listOf(" All ", "Music", "Podcasts"))

        if (albums.isNotEmpty()) {
            HomePlaylistGrid(navController, albums)
            HomeAlbums(album = albums, navController)
        }
        //HomeRecentlyPlayed(navController, albums = listOf("karan aujla", "diljit", "fudfu", "frref", "frrf"))
        if (artists.isNotEmpty()) {
            HomeArtists(artists = artists, navController)
        }
        if (albums.isNotEmpty()) {
            ImageCard(navController, albums)
        }
    }
}



@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun GreetingSection(name : String = "User") {
    val currentHour = LocalTime.now().hour
    val greeting = when {
        currentHour < 12 -> "Good Morning"
        currentHour < 17 -> "Good Afternoon"
        else -> "Good Evening"
    }
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.Center) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineSmall,
                color = com.music.spotui.ui.theme.AppText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
                )
            Text(
                text = "Have a Nice Day",
                style = MaterialTheme.typography.bodyMedium,
                color = com.music.spotui.ui.theme.AppText,
                fontSize = 13.sp
                )
        }
//        Icon(imageVector = Icons.Outlined.Person, contentDescription = "Profile", tint = com.music.spotui.ui.theme.AppText)
    }
}

//@Composable
//fun ChipSection(
//    chip : List<String>
//) {
//    var selectedChip by remember {
//        mutableStateOf(0)
//    }
//    LazyRow{
//        items(chip.size){
//            Box(contentAlignment = Alignment.Center,
//                modifier = Modifier
//                    .padding(15.dp, 0.dp, 0.dp, 0.dp)
//                    .clickable {
//                        selectedChip = it
//                    }
//                    .clip(RoundedCornerShape(50.dp))
//                    .background(
//                        if (selectedChip == it) Color.Green
//                        else Color.Gray
//                    )
//                    .padding(10.dp, 5.dp)
//
//            ){
//                Text(text = chip[it], color = com.music.spotui.ui.theme.AppText)
//            }
//        }
//    }
//}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun HomePlaylistGrid(navController: NavController, albums: List<AlbumsModel>) {
    // Use up to 8 albums, but don't assume there are at least 8 (rate-limited /
    // small feeds can return fewer) — that previously caused IndexOutOfBounds.
    val gridAlbums = albums.take(8)

    val chunkedAlbums = gridAlbums.chunked(2)
    Log.d("giveme", chunkedAlbums.toString())
    Column(
        modifier = Modifier
            .padding(0.dp, 10.dp)
    ){
        repeat(chunkedAlbums.size){
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .padding(15.dp, 5.dp, 7.dp, 0.dp)
                    .fillMaxWidth()
            )
            {
                repeat(chunkedAlbums[it].size){ album ->
                    Row(
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(2.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(GridBackground.toArgb()))
                            .width(180.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                val albumModel = chunkedAlbums[it][album]
                                Log.d("check", albumModel.name)
                                navController.navigate(albumRoute(albumModel.name, albumModel.artists))
                            }
                    ) {
                        GlideImage(modifier = Modifier
                            .size(55.dp),
                            contentScale = ContentScale.Crop,
                            model = chunkedAlbums[it][album].coverUri,
                            loading = placeholder(R.drawable.placeholder),
                            failure = placeholder(R.drawable.placeholder),
                            contentDescription = "Profile")
                        Text(modifier = Modifier.padding(5.dp),
                            text = chunkedAlbums[it][album].name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = com.music.spotui.ui.theme.AppText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                    }
                }

            }
        }
    }
}


@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun HomeAlbums(
    album : List<AlbumsModel>,
    navController: NavController
) {
    val reversedAlbum = album.reversed().dropLast(1)
    Text(modifier = Modifier
        .padding(20.dp, 10.dp, 0.dp, 0.dp),
        text = "Albums",
        color = com.music.spotui.ui.theme.AppText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold)
        LazyRow(modifier = Modifier.padding(6.dp)){
            items(reversedAlbum.size){ album ->
                Box(modifier = Modifier
                    .padding(10.dp)
                    .width(150.dp)
                    .height(195.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        navController.navigate(albumRoute(reversedAlbum[album].name, reversedAlbum[album].artists))
                    }
            ){
                Column(
                    horizontalAlignment = Alignment.Start,
                    ) {

                    GlideImage(modifier = Modifier
                        .size(150.dp),
                        contentScale = ContentScale.Crop,
                        model = reversedAlbum[album].coverUri,
                        loading = placeholder(R.drawable.placeholder),
                        failure = placeholder(R.drawable.placeholder),
                        contentDescription = "Albums")
                    Text(
                        fontSize = 13.sp,
                        text = reversedAlbum[album].name,
                        textAlign = TextAlign.Center,
                        color = com.music.spotui.ui.theme.AppText,
                        fontWeight = FontWeight.Bold)
                    Text(
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        text = reversedAlbum[album].artists,
                        color = com.music.spotui.ui.theme.AppSecondary)
                }

            }
        }
    }
}

@Composable
fun HomeRecentlyPlayed(
    navController: NavController,
    albums : List<String>
) {
    Text(modifier = Modifier
        .padding(20.dp, 10.dp, 0.dp, 0.dp),
        text = "Recently Played",
        color = com.music.spotui.ui.theme.AppText,
        fontSize = 23.sp,
        fontWeight = FontWeight.Bold)
    LazyRow(modifier = Modifier.padding(6.dp)){
        items(albums.size){
            Box(modifier = Modifier
                .padding(10.dp)
                .width(130.dp)
                .height(140.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    navController.navigate(Routes.Player.route)
                }
            ){
                Column(horizontalAlignment = Alignment.Start) {
                    Image(modifier = Modifier
                        .size(120.dp)
                        .background(Color.Green),
                        contentScale = ContentScale.Crop,
                        painter = painterResource(id = R.drawable.album),
                        contentDescription = "Albums")
                    Text(modifier = Modifier.padding(2.dp),
                        text = "Album name",
                        color = com.music.spotui.ui.theme.AppText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp)
                }

            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun HomeArtists(
    artists : List<ArtistsModel>,
    navController: NavController
) {
    Text(modifier = Modifier
        .padding(20.dp, 10.dp, 0.dp, 0.dp),
        text = "Best of Artists",
        color = com.music.spotui.ui.theme.AppText,
        fontSize = 23.sp,
        fontWeight = FontWeight.Bold)
    LazyRow(modifier = Modifier.padding(6.dp)){
        items(artists.size){artist ->
            Box(modifier = Modifier
                .padding(10.dp)
                .width(150.dp)
                .height(200.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    Log.d("check", artists[artist].name)
                    navController.navigate(artistRoute(artists[artist].name, artists[artist].id))
                }
            ){
                Column(horizontalAlignment = Alignment.Start) {



                    GlideImage(modifier = Modifier
                        .size(150.dp),
                        contentScale = ContentScale.Crop,
                        model = artists[artist].coverUri,
                        loading = placeholder(R.drawable.placeholder),
                        failure = placeholder(R.drawable.placeholder),
                        contentDescription = "Albums")
                    Text(modifier = Modifier.padding(2.dp),
                        text = "This is ${artists[artist].name}",
                        color = com.music.spotui.ui.theme.AppSecondary,
                        fontSize = 11.sp)
                }

            }
        }
    }
}


@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ImageCard(
    navController: NavController,
    allAlbums: List<AlbumsModel>,
    modifier: Modifier = Modifier
) {

    val albums = allAlbums.takeLast(3)
    Text(modifier = Modifier
        .padding(20.dp, 10.dp, 0.dp, 0.dp),
        text = "Discover",
        color = com.music.spotui.ui.theme.AppText,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold)
    Column(
        modifier = Modifier.padding(0.dp, 10.dp, 0.dp, 50.dp)
    ) {
        repeat(albums.size) { album ->
            Card(
                shape = RoundedCornerShape(15.dp),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 5.dp
                ),
                modifier = Modifier
                    .padding(15.dp)
                    .fillMaxWidth()
                    .height(380.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                       navController.navigate(albumRoute(albums[album].name, albums[album].artists))
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    GlideImage(
                        modifier = Modifier.fillMaxSize(),
                        model = albums[album].coverUri,
                        contentDescription = "artists",
                        loading = placeholder(R.drawable.placeholder),
                        failure = placeholder(R.drawable.placeholder),
                        contentScale = ContentScale.Crop
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color(AppBackground.toArgb())
                                    ),
                                    startY = 150f
                                )
                            )
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(0.dp, 0.dp, 0.dp, 30.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Text(
                            text = "Album : ${albums[album].name}",
                            style = TextStyle(color = com.music.spotui.ui.theme.AppText, fontSize = 20.sp),
                            textAlign = TextAlign.Center
                        )

                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(100.dp))
    }
}





















