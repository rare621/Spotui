package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
import com.music.spotui.di.Palette
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.PlaylistViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistScreen(navController: NavController, playlistId: String, playlistName: String = "") {

    val playlistViewModel: PlaylistViewModel = hiltViewModel()
    val songsResp by playlistViewModel.songs.collectAsState()
    val playlistResp by playlistViewModel.playlist.collectAsState()
    val needsSave by playlistViewModel.needsSave.collectAsState()
    val saving by playlistViewModel.saving.collectAsState()
    val canEdit by playlistViewModel.canEdit.collectAsState()
    var editing by remember(playlistId) { mutableStateOf(false) }
    var removeFromPlaylist by remember(playlistId) { mutableStateOf<com.music.spotui.data.entity.SongsModel?>(null) }
    if (editing && canEdit) com.music.spotui.ui.components.PlaylistEditorSheet(playlistId, playlistName, navController, initialRemove = removeFromPlaylist,
        onChanged = { playlistViewModel.refreshPlaylist(playlistId) }, onDismiss = { editing = false; removeFromPlaylist = null; playlistViewModel.refreshPlaylist(playlistId) })
    val context = LocalContext.current

    LaunchedEffect(playlistId) {
        playlistViewModel.loadPlaylist(playlistId)
    }

    val songs = (songsResp as? Response.Success)?.data.orEmpty()
    val originalPlaylist = (playlistResp as? Response.Success)?.data
        ?: AlbumsModel(
            id = playlistId.hashCode() and 0x7fffffff,
            artists = "",
            coverUri = songs.firstOrNull()?.coverUri ?: "",
            name = playlistName,
            time = "",
        )

    val localCover = com.music.spotui.data.api.PlaylistCover.localPath(context, playlistId)
    val playlist = if (localCover != null) originalPlaylist.copy(coverUri = localCover) else originalPlaylist

    var menuSong by remember { mutableStateOf<com.music.spotui.data.entity.SongsModel?>(null) }
    menuSong?.let { sel ->
        com.music.spotui.ui.components.SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
            onRemoveFromPlaylist = if (canEdit) ({ removeFromPlaylist = sel; menuSong = null; editing = true }) else null,
        )
    }

    // 0 = playlist order, 1 = title A-Z, 2 = title Z-A, 3 = artist A-Z
    var sortOrder by remember { mutableStateOf(0) }
    val displaySongs = when (sortOrder) {
        1 -> songs.sortedBy { it.title.lowercase() }
        2 -> songs.sortedByDescending { it.title.lowercase() }
        3 -> songs.sortedBy { it.singer.lowercase() }
        else -> songs
    }

    Surface(
        color = Color(AppBackground.toArgb()),
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        if (songsResp is Response.Loading && playlistResp is Response.Loading) {
            Loader()
            return@Surface
        }

        var dominentColor by remember { mutableStateOf(Color(AppBackground.toArgb())) }
        Palette().extractSecondColorFromCoverUrl(context = context, playlist.coverUri) { color ->
            dominentColor = color
        }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                com.music.spotui.ui.components.CollectionBackBar(dominentColor) { navController.navigateUp() }
            }
        ) { innerPadding ->
            LazyColumn(
                contentPadding = innerPadding,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(AppBackground.toArgb()))
                    .consumeWindowInsets(innerPadding)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Minimum, not fixed: a long playlist description used to
                            // overflow the fixed height and squash the play button.
                            .heightIn(min = 380.dp)
                            .padding(bottom = 8.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(com.music.spotui.ui.theme.artworkHeader(dominentColor), Color(AppBackground.toArgb())),
                                    startY = 0f,
                                ),
                            ),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))

                        com.music.spotui.ui.components.CollectionArtwork(playlist.coverUri, "Collection artwork")
                        Spacer(modifier = Modifier.padding(5.dp))
                        Text(
                            modifier = Modifier.padding(20.dp, 5.dp, 0.dp, 0.dp),
                            text = playlist.name.ifBlank { playlistName },
                            color = com.music.spotui.ui.theme.AppText,
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (playlist.time.isNotBlank()) {
                            Text(
                                modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 0.dp),
                                text = playlist.time,
                                color = com.music.spotui.ui.theme.AppSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (playlist.artists.isNotBlank()) {
                            Text(
                                modifier = Modifier.padding(20.dp, 4.dp, 0.dp, 0.dp),
                                text = "Spotify playlist • ${playlist.artists}",
                                color = com.music.spotui.ui.theme.AppText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (songs.isNotEmpty()) {
                            val sortLabels = listOf("Custom order", "Title A-Z", "Title Z-A", "Artist")
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp, 0.dp, 20.dp, 2.dp),
                            ) {
                                Text(
                                    text = sortLabels[sortOrder],
                                    color = if (sortOrder == 0) Color.Gray else Color(AppPalette.toArgb()),
                                    fontSize = 12.sp,
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { sortOrder = (sortOrder + 1) % 4 },
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = if (sortOrder == 0) Color.Gray else Color(AppPalette.toArgb()),
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) { sortOrder = (sortOrder + 1) % 4 },
                                )
                            }
                        }

                        if(needsSave) {
                            Text("Saved in USpot. Spotify hasn't confirmed it in your library yet.",color=com.music.spotui.ui.theme.AppSecondary,modifier=Modifier.padding(horizontal=20.dp))
                            androidx.compose.material3.TextButton(enabled=!saving,onClick={playlistViewModel.retryLibrarySave()},modifier=Modifier.padding(horizontal=12.dp)){Text(if(saving)"Saving…"else"Save to Spotify library",color=com.music.spotui.ui.theme.AppPalette)}
                        }
                        Row(
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(20.dp, 0.dp)
                        ) {
                            if (canEdit) androidx.compose.material3.TextButton(onClick = { editing = true }) { Text("Edit", color = com.music.spotui.ui.theme.AppText) }
                            Spacer(Modifier.weight(1f))
                            // Download the whole playlist (all tracks) for offline playback.
                            var askDownloadAll by remember { mutableStateOf(false) }
                            val playlistDownloaded = com.music.spotui.ui.components.rememberCollectionDownloaded(songs, context)
                            if (songs.isNotEmpty()) {
                                com.music.spotui.ui.components.CollectionAction(painter = painterResource(if (playlistDownloaded) R.drawable.added else R.drawable.ic_download), label = "Download playlist", active = playlistDownloaded, enabled = songs.isNotEmpty()) {

                                            if (!playlistDownloaded) askDownloadAll = true
                                        
                            }
                                if (askDownloadAll) {
                                    com.music.spotui.ui.components.ConfirmDownloadAllDialog(
                                        songs.size, onDismiss = { askDownloadAll = false },
                                    ) {
                                        com.music.spotui.di.Downloads.downloadAll(songs, context)
                                        android.widget.Toast.makeText(
                                            context, "Downloading ${songs.size} tracks…", android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                // Shuffle-play: start the playlist in random order.
                                com.music.spotui.ui.components.CollectionAction(painter = painterResource(R.drawable.ic_player_shuffle), label = "Shuffle play", active = playlistViewModel.shuffleActive, smart = playlistViewModel.smartShuffleActive, enabled = songs.isNotEmpty()) {

                                            if (playlistViewModel.collectionActive) playlistViewModel.cycleShuffle()
                                        else playlistViewModel.startShuffled(displaySongs)?.let { first ->
                                                com.music.spotui.data.preferences.HomeRecents.rememberPlaylist(context, playlistId, playlist.name, playlist.coverUri)
                                                SongPlayer.playSong(first.url, context)
                                                playlistViewModel.updateSongState(
                                                    first.coverUri,
                                                    first.title,
                                                    first.singer,
                                                    true,
                                                    first.id,
                                                    0,
                                                    playlist.name,
                                                )
                                            }
                                        
                            }
                                Spacer(modifier = Modifier.width(16.dp))
                            }
                            // Always visible: pause when playing, resume when this
                            // list's track is paused, otherwise start from the top.
                            if (songs.isNotEmpty()) {
                                val playing = playlistViewModel.collectionActive && playlistViewModel.currentSongPlayingState.value
                                com.music.spotui.ui.components.CollectionPlayButton(playing = playing, onClick = {
                                            when {
                                                playing -> playlistViewModel.setPlaying(false)
                                                playlistViewModel.collectionActive ->
                                                    playlistViewModel.setPlaying(true)
                                                else -> {
                                                    playlistViewModel.updateQueue(displaySongs)
                                                    com.music.spotui.data.preferences.HomeRecents.rememberPlaylist(context, playlistId, playlist.name, playlist.coverUri)
                                                    SongPlayer.playSong(displaySongs[0].url, context)
                                                    playlistViewModel.updateSongState(
                                                        displaySongs[0].coverUri,
                                                        displaySongs[0].title,
                                                        displaySongs[0].singer,
                                                        true,
                                                        displaySongs[0].id,
                                                        0,
                                                        playlist.name
                                                    )
                                                }
                                            
                                            }
                                })
                            }
                        }
                    }
                }

                // A playlist can hold the same track twice; duplicate keys crash LazyColumn.
                // Repeats get "#n" so every other key stays stable while pages load.
                val rowKeys = run {
                    val seen = HashMap<Int, Int>()
                    displaySongs.map { s -> seen.merge(s.id, 1, Int::plus).let { n -> if (n == 1) "${s.id}" else "${s.id}#$n" } }
                }
                itemsIndexed(displaySongs, key = { index, _ -> rowKeys[index] }) { index, song ->
                    com.music.spotui.ui.components.SwipeToQueue(song) {
                    val currentColor = if (song.id == playlistViewModel.currentSongId.value)
                        Color(AppPalette.toArgb()) else com.music.spotui.ui.theme.AppText

                    Row(
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp, 8.dp)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onLongClick = { menuSong = song },
                                onClick = {
                                    playlistViewModel.updateQueue(displaySongs)
                                    com.music.spotui.data.preferences.HomeRecents.rememberPlaylist(context, playlistId, playlist.name, playlist.coverUri)
                                    SongPlayer.playSong(song.url, context)
                                    playlistViewModel.updateSongState(
                                        song.coverUri,
                                        song.title,
                                        song.singer,
                                        true,
                                        song.id,
                                        index,
                                        playlist.name
                                    )
                                },
                            )
                    ) {
                        GlideImage(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            model = song.coverUri,
                            failure = placeholder(R.drawable.placeholder),
                            contentScale = ContentScale.Crop,
                            contentDescription = ""
                        )
                        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(
                                text = song.title,
                                color = currentColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                text = song.singer,
                                color = com.music.spotui.ui.theme.AppSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                        com.music.spotui.ui.components.SongOverflowButton(song.title) { menuSong = song }
                    }
                    }
                }

                item { Spacer(modifier = Modifier.padding(80.dp)) }
            }
        }
    }
}
