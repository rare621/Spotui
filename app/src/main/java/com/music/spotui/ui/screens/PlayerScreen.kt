package com.music.spotui.ui.screens

import androidx.compose.ui.draw.drawBehind
import com.music.spotui.ui.components.menuReveal
import com.music.spotui.ui.components.motionClick
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.alternativeStreamKey
import com.music.spotui.data.preferences.clearAlternativeStream
import com.music.spotui.data.preferences.getAlternativeStream
import com.music.spotui.data.preferences.getLikedSongIds
import com.music.spotui.data.preferences.getSongsByIds
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.data.preferences.setLocalAlternativeStream
import com.music.spotui.data.preferences.setYouTubeAlternativeStream
import com.music.spotui.di.Palette
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Snackbar
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalGlideComposeApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(navController: NavController) {
    val playerViewModel : PlayerViewModel = hiltViewModel()
    val songTitle = playerViewModel.currentSongTitle.value
    val songSinger = playerViewModel.currentSongSinger.value
    val songCoverUri = playerViewModel.currentSongCoverUri.value
    val songPlayingState = playerViewModel.currentSongPlayingState.value
    val songId = playerViewModel.currentSongId.value
    val context = LocalContext.current
    val isLiked = com.music.spotui.ui.components.rememberSongLiked(context, songId.toString())
    var showQueue by remember { mutableStateOf(false) }
    if (showQueue) QueueSheet(navController, playerViewModel) { showQueue = false }
    var showMenu by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showSavedIn by remember { mutableStateOf(false) }

    BackHandler(enabled = showLyrics) {
        showLyrics = false
    }

    if (showMenu) {
        PlayerOptionsSheet(
            navController = navController,
            playerViewModel = playerViewModel,
            context = context,
            isLiked = isLiked,
            onDismiss = { showMenu = false },
            onOpenQueue = { showQueue = true }
        )
    }

    if (showSavedIn) {
        playerViewModel.queue.value.firstOrNull { it.id == songId }?.let { track ->
            com.music.spotui.ui.components.SavedInSheet(
                song = track,
                context = context,
                onDismiss = { showSavedIn = false },
                onLikedChanged = { isLiked.value = it },
            )
        } ?: run { showSavedIn = false }
    }




    //playerViewModel.updateSongState(songCoverUri, songTitle, songSinger, songPlayingState)



    var dominentColor by remember {
        mutableStateOf(Color(AppBackground.toArgb()))
    }
    Palette().extractSecondColorFromCoverUrl(context = context, songCoverUri){ color ->
        dominentColor = color
    }

    val songsResponse by playerViewModel.songs.collectAsState()
    val shuffle = playerViewModel.shuffleState.value
    val repeat = playerViewModel.repeatState.value

    val songs = if (songsResponse is Response.Success){
        (songsResponse as Response.Success).data
    } else {
        emptyList<SongsModel>()
    }

    // The queue is whatever list the user actually started playing (album tracks,
    // search results, liked songs) — stored when the song was tapped. Falling back
    // to the global top-tracks feed used to crash / be empty (it's rate-limited).
    val queueSongs = playerViewModel.queue.value

    // ── Now-playing swipe pager ──
    // Index of the playing track in the queue (fallback to 0 so the pager is valid
    // even before the queue/current id line up).
    val currentIndex = queueSongs.indexOfFirst { it.id == playerViewModel.currentSongId.value }
        .let { if (it >= 0) it else 0 }
    val artworkPagerState = rememberPagerState(
        initialPage = currentIndex,
        pageCount = { queueSongs.size.coerceAtLeast(1) },
    )
    // External track changes (auto-advance, prev/next buttons, queue edits) → snap the
    // pager to the new track. Guard on settled state so we don't fight an in-progress swipe.
    LaunchedEffect(currentIndex, queueSongs.size) {
        if (currentIndex in 0 until queueSongs.size &&
            artworkPagerState.currentPage != currentIndex &&
            !artworkPagerState.isScrollInProgress
        ) {
            artworkPagerState.scrollToPage(currentIndex)
        }
    }
    // User settled the pager on a different page → play that track. Compare against the
    // live current id (not currentIndex captured above) to avoid a replay feedback loop.
    LaunchedEffect(artworkPagerState, queueSongs) {
        snapshotFlow { artworkPagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                queueSongs.getOrNull(page)?.let { target ->
                    if (target.id != playerViewModel.currentSongId.value) {
                        playerViewModel.playSongAt(queueSongs, page, context)
                    }
                }
            }
    }

    // SongPlayer owns next-track preparation after audible playback starts.
    // Composable queue changes must not launch detached duplicate lookups.

    // Load the current track's Spotify Canvas (full-screen looping video background).
    LaunchedEffect(playerViewModel.currentSongId.value, queueSongs) {
        val track = queueSongs.firstOrNull { it.id == playerViewModel.currentSongId.value }
        playerViewModel.loadCanvas(track?.spotifyTrackId.orEmpty())
    }




    androidx.compose.runtime.DisposableEffect(playerViewModel.canvasUrl.value) {
        com.music.spotui.ui.theme.AppearanceSettings.artworkScreen.value = playerViewModel.canvasUrl.value != null
        onDispose { com.music.spotui.ui.theme.AppearanceSettings.artworkScreen.value = false }
    }

    // End of track (repeat / next / radio) is handled by PlaybackService, so it also
    // happens with the screen off.


    val canvasUrl = playerViewModel.canvasUrl.value
    val animatedArtworkColor = androidx.compose.animation.animateColorAsState(
        targetValue = dominentColor,
        animationSpec = androidx.compose.animation.core.tween(550), label = "Artwork atmosphere"
    )
    val playerBackground = com.music.spotui.ui.theme.AppBackground
    val useDarkArtwork = com.music.spotui.ui.theme.AppearanceSettings.effectiveDark
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                // Read the animated color during drawing rather than recomposing the video each frame.
                drawRect(Brush.verticalGradient(
                    colors = if (canvasUrl != null) listOf(animatedArtworkColor.value, Color.Black)
                        else listOf(if (useDarkArtwork) animatedArtworkColor.value else androidx.compose.ui.graphics.lerp(playerBackground, animatedArtworkColor.value, 0.16f), playerBackground)
                ))
            }
    ) {
        if (canvasUrl != null) {
            // Spotify Canvas: the looping video fills the whole now-playing screen
            // edge-to-edge behind the controls (the "immersive" treatment), with a
            // scrim on top so the title, slider and buttons stay readable.
            GlideImage(
                model = songCoverUri,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                contentDescription = null,
            )
            CanvasVideo(
                url = canvasUrl,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.50f),
                                Color.Black.copy(alpha = 0.28f),
                                Color.Black.copy(alpha = 0.48f),
                                Color.Black.copy(alpha = 0.80f),
                            )
                        )
                    )
            )
        }
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        item {
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillParentMaxHeight()
                .fillMaxWidth().padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PlayerTopBar(
                navController,
                onMenuClick = { showMenu = true },
                contextName = playerViewModel.activeCollection.value?.name.orEmpty(),
            )
            //Spacer(modifier = Modifier.padding(16.dp))
            // Swipe the artwork left/right to skip to the next/previous track. Using a
            // HorizontalPager makes the artwork follow the finger and snap, syncing the
            // change with the track (Spotify's now-playing gesture) instead of an abrupt
            // swipe-then-switch. When the queue is empty fall back to a static image.
            // When a Canvas is playing it fills the screen behind this column, so the
            // artwork is hidden (alpha 0) rather than removed — the pager stays in
            // the layout so the swipe-to-skip gesture keeps working over the video.
            // The artwork is the FLEXIBLE part of the screen (weight), capped at its
            // old 385dp size. On short/scaled displays the fixed-size version pushed
            // the slider and playback buttons off the bottom of the screen; now the
            // artwork shrinks instead and the controls always fit.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (queueSongs.isEmpty()) {
                    GlideImage(
                        modifier = Modifier
                            .sizeIn(maxWidth = 385.dp, maxHeight = 385.dp)
                            .aspectRatio(1f)
                            .padding(20.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .alpha(if (canvasUrl != null) 0f else 1f),
                        model = songCoverUri,
                        contentScale = ContentScale.Crop,
                        contentDescription = "")
                } else {
                    HorizontalPager(
                        state = artworkPagerState,
                        modifier = Modifier
                            .sizeIn(maxWidth = 385.dp, maxHeight = 385.dp)
                            .aspectRatio(1f),
                    ) { page ->
                        GlideImage(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .alpha(if (canvasUrl != null) 0f else 1f),
                            model = queueSongs.getOrNull(page)?.coverUri ?: songCoverUri,
                            contentScale = ContentScale.Crop,
                            contentDescription = "")
                    }
                }
            }
            //Spacer(modifier = Modifier.padding(30.dp))

            Column(
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .height(320.dp)
                    .padding(0.dp, 0.dp, 0.dp, 16.dp)
            ){
                PlayerInfo(
                    songTitle, songSinger, songId, context, isLiked,
                    onArtistClick = {
                        val track = queueSongs.firstOrNull { it.id == songId }
                        playerViewModel.goToArtist(track?.spotifyTrackId.orEmpty(), songSinger) { route ->
                            navController.navigate(route)
                        }
                    },
                    spotifyTrackId = queueSongs.firstOrNull { it.id == songId }?.spotifyTrackId.orEmpty(),
                    onShowSavedIn = { showSavedIn = true },
                    radioActive = playerViewModel.activeCollection.value?.type == "radio",
                    onStartRadio = { queueSongs.firstOrNull { it.id == songId }?.let { playerViewModel.startRadioFromSong(it, context) } },
                )

                PlayerSeekbar(songId, songPlayingState, playerViewModel)

                Spacer(modifier = Modifier.padding(5.dp))
                PlayerFull(songPlayingState, playerViewModel, context, isLiked, shuffle, repeat, queueSongs)
            }

            // Spotify-style bottom row: current audio device (Connect) on the left,
            // share + queue on the right.
            PlayerConnectRow(
                navController = navController,
                context = context,
                currentTrack = queueSongs.firstOrNull { it.id == playerViewModel.currentSongId.value },
                onOpenQueue = { showQueue = true },
            )

            //PlayerEndInfo()
        }
        }
        item {
            InlineLyrics(
                title = songTitle,
                artist = songSinger,
                album = playerViewModel.currentSongAlbum.value,
                accentColor = dominentColor,
                onExpand = { showLyrics = true },
            )
        }
        }

        AnimatedVisibility(
            visible = showLyrics,
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 250, easing = EaseOut),
                initialOffsetY = { it },
            ),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 200, easing = EaseOut),
                targetOffsetY = { it },
            ),
        ) {
            LyricsScreen(
                title = songTitle,
                artist = songSinger,
                album = playerViewModel.currentSongAlbum.value,
                accentColor = dominentColor,
                onClose = { showLyrics = false }
            )
        }
    }
}









@Composable
fun PlayerTopBar(
    navController: NavController,
    onMenuClick: () -> Unit,
    contextName: String = "",
) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Icon(modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                       navController.navigateUp()
            },
            painter = painterResource(id = R.drawable.ic_down),
            tint = com.music.spotui.ui.theme.PlayerText,
            contentDescription = "down")

        // Spotify shows the source context here (album/playlist), not a generic label.
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "PLAYING FROM",
                color = com.music.spotui.ui.theme.PlayerSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = contextName.ifBlank { "Now Playing" },
                color = com.music.spotui.ui.theme.PlayerText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                tint = com.music.spotui.ui.theme.PlayerText,
                modifier = Modifier
                    .size(23.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onMenuClick() },
                contentDescription = "")
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayerInfo(
    songTitle: String,
    songSinger: String,
    songId: Int,
    context: Context,
    isLiked: MutableState<Boolean>,
    onArtistClick: (() -> Unit)? = null,
    spotifyTrackId: String = "",
    onShowSavedIn: (() -> Unit)? = null,
    onStartRadio: (() -> Unit)? = null,
    radioActive: Boolean = false,
) {

    val playbackStage by com.music.spotui.audio.PlaybackPresentation.state.collectAsState()
    val playbackLabel = com.music.spotui.audio.PlaybackPresentation.label(playbackStage)
    var sourceInfo by remember(songId) { mutableStateOf(SongPlayer.currentSource to SongPlayer.currentQuality) }
    LaunchedEffect(songId, playbackStage) {
        while (true) {
            sourceInfo = SongPlayer.currentSource to SongPlayer.currentQuality
            delay(500L)
        }
    }
    val (source, quality) = sourceInfo

    var snackbarMessage by remember {
        mutableStateOf("")
    }
    var snackbarVisible by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(snackbarVisible) {
        delay(1500)
        snackbarVisible = false
    }


    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(25.dp, 10.dp)
    ) {

        if (snackbarVisible){
            Snackbar(showMessage = snackbarMessage)
        }
        else{
        Row(
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
//                        GlideImage(
//                            modifier = Modifier.size(60.dp),
//                            model = albumSongs[song].coverUri,
//                            contentScale = ContentScale.Crop,
//                            contentDescription = ""
//                        )
            Column {
                Text(
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    text = songTitle,
                    color = com.music.spotui.ui.theme.PlayerText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = songSinger,
                    color = com.music.spotui.ui.theme.PlayerSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = if (onArtistClick != null) Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onArtistClick() } else Modifier,
                )
                if (source.isNotBlank() || playbackLabel.isNotBlank()) {
                    // One rule app-wide: green = lossless (as on the FLAC badges in Local
                    // files), grey = everything else, whatever the source. Source-based
                    // colours made a downloaded or local FLAC show up red.
                    val lossless = source.startsWith("Lossless") || listOf("FLAC", "WAV", "ALAC", "AIFF", "APE")
                        .any { quality.uppercase().contains(it) }
                    val badgeColor = if (lossless || source == "Spotify") Color(0xFF1ED760) else Color(0xFF9C9C9C)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 3.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(badgeColor)
                        )
                        Text(
                            // Don't advertise the fallback engine — just "Streamed".
                            // Append the stream quality (codec/bitrate or FLAC depth)
                            // so the user can see what they're actually hearing.
                            text = playbackLabel.ifBlank {
                                (if (source == "YouTube") "Streamed" else source) +
                                    (if (quality.isNotBlank()) " • $quality" else "")
                            },
                            color = badgeColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                }
            }
        }

        if (onStartRadio != null) {
            val radioTint by androidx.compose.animation.animateColorAsState(
                targetValue = if (radioActive) Color(0xFF1ED760) else com.music.spotui.ui.theme.PlayerText,
                label = "Radio activation"
            )
            val radioSize by androidx.compose.animation.core.animateDpAsState(
                targetValue = if (radioActive) 30.dp else 26.dp,
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.45f),
                label = "Radio activation pulse"
            )
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.Radio,
                contentDescription = if (radioActive) "Radio active" else "Start song radio", tint = radioTint,
                modifier = Modifier.padding(end = 16.dp).size(radioSize).clickable(
                    enabled = !radioActive, interactionSource = remember { MutableInteractionSource() }, indication = null
                ) { onStartRadio() }
            )
        }
        Icon(
            modifier = Modifier
                .size(26.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (isLiked.value && onShowSavedIn != null) {
                        // Already saved — second tap opens the Spotify-style
                        // "Saved in" sheet (Liked Songs + playlists) instead of
                        // silently unliking.
                        onShowSavedIn()
                        return@clickable
                    }
                    if (isLiked.value) {
                        removeLikedSongId(context, songId.toString())
                        snackbarMessage = "Removed from Liked Songs"
                    } else {
                        addLikedSongId(context, songId.toString())
                        snackbarMessage = "Added to Liked Songs"
                    }
                    snackbarVisible = true
                    isLiked.value = isSongLiked(context, songId.toString())
                    // Mirror the like to the real Spotify account.
                    com.music.spotui.data.api.SpotifySync.setTrackSaved(context, spotifyTrackId, isLiked.value)
                },
            painter = if (isLiked.value){
                painterResource(id = R.drawable.added)
            }
            else{
                painterResource(id = R.drawable.ic_add)
            }
            ,
            tint = if (isLiked.value){
                Color(AppPalette.toArgb())
            }
            else{
                com.music.spotui.ui.theme.PlayerText
            },
            contentDescription = ""
        )
    }
    }



}

@OptIn(ExperimentalMaterial3Api::class)

/** Timer ticks only invalidate the seek controls, never the video/artwork screen. */
@Composable
private fun PlayerSeekbar(songId: Int, songPlayingState: Boolean, playerViewModel: PlayerViewModel) {
    var position by remember(songId) { mutableStateOf(SongPlayer.getCurrentPosition().coerceAtLeast(0)) }
    var duration by remember(songId) { mutableStateOf(SongPlayer.getDuration()) }
    LaunchedEffect(songId) {
        while (true) {
            position = SongPlayer.getCurrentPosition().coerceAtLeast(0)
            duration = SongPlayer.getDuration()
            delay(300L)
        }
    }
    val songProgressText = playerViewModel.formatDuration(position)
    val songDurationText = if (duration > 0) playerViewModel.formatDuration(duration) else "—:—"
    // Smooth scrubbing: while dragging, the thumb follows the finger
    // locally (no seek per delta — that fired a web seek on every pixel
    // and fought the polled position, making it jerky). We seek ONCE on
    // release.
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val liveFraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    CustomSlider(
        value = if (isDragging) dragValue else liveFraction,
        onValueChange = { newValue ->
            isDragging = true
            dragValue = newValue
        },
        onValueChangeFinished = {
            SongPlayer.seekTo((dragValue * SongPlayer.getDuration()).toLong())
            isDragging = false
            if (!songPlayingState) {
                SongPlayer.play()
                playerViewModel.updateSongState(
        playerViewModel.currentSongCoverUri.value,
        playerViewModel.currentSongTitle.value,
        playerViewModel.currentSongSinger.value,
        true,
        playerViewModel.currentSongId.value,
        playerViewModel.currentSongIndex.value,
        playerViewModel.currentSongAlbum.value
                )
            }
        },
        valueRange = 0f..1f,
        steps = 0,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 20.dp, 16.dp, 0.dp),
        colors = SliderDefaults.colors(
            thumbColor = Color(0xFF1ED760),
            activeTrackColor = Color(0xFF1ED760),
            inactiveTrackColor = com.music.spotui.ui.theme.PlayerSecondary.copy(alpha = 0.35f)
        )
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(25.dp, 0.dp)
        ,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            // While scrubbing, show the dragged time so the label tracks the finger.
            text = if (isDragging)
                playerViewModel.formatDuration((dragValue * SongPlayer.getDuration()).toLong())
            else songProgressText,
            color = com.music.spotui.ui.theme.PlayerSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = songDurationText,
            color = com.music.spotui.ui.theme.PlayerSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomSlider(
    modifier: Modifier = Modifier,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    colors: SliderColors = SliderDefaults.colors(),
) {
    Box(modifier = modifier.height(10.dp)) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            colors = colors,
            thumb = {
                SliderDefaults.Thumb( //androidx.compose.material3.SliderDefaults
                    interactionSource = remember { MutableInteractionSource() },
                    modifier = Modifier.align(Alignment.Center),
                    colors = colors,
                    thumbSize = DpSize(9.dp, 9.dp)
                )
            }
        )
    }
}

@Composable
fun PlayerEndInfo() {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)){
        Icon(
            modifier = Modifier
                .size(22.dp),
            painter = painterResource(id = R.drawable.ic_devices),
            tint = com.music.spotui.ui.theme.PlayerText,
            contentDescription = "Audio output")
        Icon(
            modifier = Modifier
                .size(16.dp),
            painter = painterResource(id = R.drawable.ic_share),
            tint = com.music.spotui.ui.theme.PlayerText,
            contentDescription = "Share song")
    }
}

@Composable
fun PlayerFull(
    songPlayingState: Boolean,
    playerViewModel: PlayerViewModel,
    context: Context,
    isLiked: MutableState<Boolean>,
    shuffle: Boolean,
    repeat: Boolean,
    queueSongs: List<SongsModel>
) {




    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        Box(
            modifier = Modifier.size(48.dp).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { playerViewModel.cycleShuffle() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                modifier = Modifier.size(25.dp),
                tint = if (shuffle) Color(0xFF1ED760) else com.music.spotui.ui.theme.PlayerText,
                painter = painterResource(id = R.drawable.ic_player_shuffle),
                contentDescription = if (playerViewModel.smartShuffleState.value) "Smart Shuffle on" else if (shuffle) "Shuffle on" else "Shuffle off"
            )
            // Separate badge with space around the glyph, inside the same 48dp touch target.
            if (playerViewModel.smartShuffleState.value) Text(
                "◆", color = Color(0xFF1ED760), fontSize = 9.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 1.dp)
            )
        }
        Icon(
            modifier = Modifier
                .size(48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    // The queue itself is already in shuffled order when shuffle
                    // is on (reordered once at toggle) — never re-shuffle per tap.
                    playerViewModel.playPreviousSong(queueSongs, context)
                }.padding(7.dp)
            ,
            tint = com.music.spotui.ui.theme.PlayerText,
            painter = painterResource(id = R.drawable.ic_player_back),
            contentDescription = "Previous song")
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                // requiredSize forces an exact 64×64 square even if the parent Column
                // constrains height — .size() alone let it get squished into an ellipse.
                .requiredSize(64.dp)
                .clip(CircleShape)
                .background(Color(0xFF1ED760))
                .motionClick {
                    if (songPlayingState) {
                        SongPlayer.pause()
                        playerViewModel.updateSongState(
                            playerViewModel.currentSongCoverUri.value,
                            playerViewModel.currentSongTitle.value,
                            playerViewModel.currentSongSinger.value,
                            false,
                            playerViewModel.currentSongId.value,
                            playerViewModel.currentSongIndex.value,
                            playerViewModel.currentSongAlbum.value
                        )
                    } else {
                        SongPlayer.play()
                        playerViewModel.updateSongState(
                            playerViewModel.currentSongCoverUri.value,
                            playerViewModel.currentSongTitle.value,
                            playerViewModel.currentSongSinger.value,
                            true,
                            playerViewModel.currentSongId.value,
                            playerViewModel.currentSongIndex.value,
                            playerViewModel.currentSongAlbum.value
                        )
                    }
                }
            ,
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.Crossfade(targetState = songPlayingState, animationSpec = androidx.compose.animation.core.tween(160), label = "Play pause") { playing ->
                Icon(
                    modifier = Modifier.size(30.dp),
                    tint = Color.Black,
                    painter = painterResource(if (playing) R.drawable.ic_playing else R.drawable.play_svgrepo_com),
                    contentDescription = if (playing) "Pause" else "Play"
                )
            }
        }

        Icon(
            modifier = Modifier
                .size(48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {

                    playerViewModel.playNextSongs(queueSongs, context)
                }.padding(7.dp)
            ,
            tint = com.music.spotui.ui.theme.PlayerText,
            painter = painterResource(id = R.drawable.ic_player_skip),
            contentDescription = "player skip")
        Icon(
            modifier = Modifier
                .size(48.dp)
                .padding(12.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (repeat) {
                        playerViewModel.updateRepeatState(false)
                    } else {
                        playerViewModel.updateRepeatState(true)
                    }


                }
            ,
            tint = if (repeat){
                Color(AppPalette.toArgb())
            }
            else{
                com.music.spotui.ui.theme.PlayerText
            },
            painter = painterResource(id = R.drawable.ic_repeat),
            contentDescription = "repeat")
    }
}

@Composable
fun PlayerConnectRow(
    navController: NavController,
    context: Context,
    currentTrack: SongsModel?,
    onOpenQueue: () -> Unit = { navController.navigate(Routes.Queue.route) },
) {
    val (audioOutputs, outputs) = com.music.spotui.ui.components.rememberAudioOutputs(context)
    val selected = outputs.firstOrNull { it.selected }
    val routeName = selected?.name ?: "Phone speaker"
    var showOutputs by remember { mutableStateOf(false) }
    if (showOutputs) com.music.spotui.ui.components.AudioOutputSheet(audioOutputs, outputs, currentTrack?.title.orEmpty(), currentTrack?.singer.orEmpty()) { showOutputs = false }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 25.dp, vertical = 4.dp),
    ) {
        // Device / Spotify Connect indicator (green, like the official app).
        Row(modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null
        ) { showOutputs = true }, verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = R.drawable.ic_devices),
                tint = Color(0xFF1ED760),
                modifier = Modifier.size(18.dp),
                contentDescription = "Device",
            )
            Text(
                text = routeName,
                color = Color(0xFF1ED760),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .widthIn(max = 170.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = {
                val link = currentTrack?.spotifyTrackId?.takeIf { it.isNotBlank() }?.let { "https://open.spotify.com/track/$it" }
                    ?: "${currentTrack?.title.orEmpty()} ${currentTrack?.singer.orEmpty()}".trim()
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link) }
                context.startActivity(Intent.createChooser(send, "Share"))
            }) { Icon(painter=painterResource(R.drawable.ic_share),contentDescription="Share",tint=com.music.spotui.ui.theme.PlayerText,modifier=Modifier.size(22.dp)) }
            androidx.compose.material3.IconButton(onClick = onOpenQueue) {
                Icon(imageVector=Icons.AutoMirrored.Filled.List,contentDescription="Queue",tint=com.music.spotui.ui.theme.PlayerText,modifier=Modifier.size(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun PlayerOptionsSheet(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    context: Context,
    isLiked: MutableState<Boolean>,
    onDismiss: () -> Unit,
    onOpenQueue: () -> Unit = { navController.navigate(Routes.Queue.route) },
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dismiss = com.music.spotui.ui.components.rememberSheetDismiss(sheetState, onDismiss)
    var showSleep by remember { mutableStateOf(false) }
    var showSavedIn by remember { mutableStateOf(false) }
    var showAlternativeStream by remember { mutableStateOf(false) }

    val title = playerViewModel.currentSongTitle.value
    val singer = playerViewModel.currentSongSinger.value
    val cover = playerViewModel.currentSongCoverUri.value
    val album = playerViewModel.currentSongAlbum.value
    val songId = playerViewModel.currentSongId.value
    // The full track model (spotify id, real album, stream url) — the state above
    // only carries display strings, and `album` is the *context* name (playlist…).
    val currentSong = playerViewModel.queue.value.firstOrNull { it.id == songId }
    var downloaded by remember(songId) { mutableStateOf(com.music.spotui.data.preferences.isDownloaded(context, songId.toString())) }
    var downloadingNow by remember(songId) { mutableStateOf(currentSong != null && com.music.spotui.di.Downloads.isDownloading(currentSong.url)) }
    val alternativeKey = currentSong?.let { alternativeStreamKey(it) }.orEmpty()
    var currentAlternative by remember(songId, alternativeKey) {
        mutableStateOf(alternativeKey.takeIf { it.isNotBlank() }?.let { getAlternativeStream(context, it) })
    }
    val localFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val song = currentSong ?: return@rememberLauncherForActivityResult
        val picked = uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                picked,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        setLocalAlternativeStream(context, alternativeKey, picked, picked.lastPathSegment.orEmpty())
        SongPlayer.invalidateResolvedStream(song.url)
        currentAlternative = getAlternativeStream(context, alternativeKey)
        Toast.makeText(context, "Alternative stream set to local file", Toast.LENGTH_SHORT).show()
    }

    if (showSavedIn && currentSong != null) {
        com.music.spotui.ui.components.SavedInSheet(
            song = currentSong,
            context = context,
            onDismiss = { showSavedIn = false; dismiss() },
            onLikedChanged = { isLiked.value = it },
        )
        return
    }

    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
        containerColor = com.music.spotui.ui.theme.AppSurface,
        contentColor = com.music.spotui.ui.theme.AppText,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .menuReveal()
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding().verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(bottom = 12.dp)
        ) {
            if (showAlternativeStream) {
                AlternativeStreamEditor(
                    currentAlternative = currentAlternative,
                    enabled = currentSong != null,
                    onBack = { showAlternativeStream = false },
                    onUseYouTube = { text ->
                        val song = currentSong ?: return@AlternativeStreamEditor
                        val videoId = SongPlayer.videoIdFromYouTubeLink(text)
                        if (videoId == null) {
                            Toast.makeText(context, "Paste a YouTube video link or video ID", Toast.LENGTH_SHORT).show()
                        } else {
                            setYouTubeAlternativeStream(context, alternativeKey, videoId)
                            SongPlayer.invalidateResolvedStream(song.url)
                            currentAlternative = getAlternativeStream(context, alternativeKey)
                            Toast.makeText(context, "Alternative stream set to YouTube", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onPickLocal = {
                        localFileLauncher.launch(arrayOf("audio/*"))
                    },
                    onClear = {
                        val song = currentSong ?: return@AlternativeStreamEditor
                        clearAlternativeStream(context, alternativeKey)
                        SongPlayer.invalidateResolvedStream(song.url)
                        currentAlternative = null
                        Toast.makeText(context, "Alternative stream cleared", Toast.LENGTH_SHORT).show()
                    },
                )
            } else if (!showSleep) {
                // ── Now-playing header ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    GlideImage(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(14.dp)),
                        model = cover,
                        contentScale = ContentScale.Crop,
                        contentDescription = ""
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(title, color = com.music.spotui.ui.theme.AppText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(singer, color = com.music.spotui.ui.theme.AppSecondary, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = com.music.spotui.ui.theme.AppRaised)

                PlayerMenuRow(
                    icon = Icons.Default.PlayArrow,
                    label = if (playerViewModel.activeCollection.value?.type == "radio") "Radio active" else "Start Radio",
                    enabled = currentSong != null && playerViewModel.activeCollection.value?.type != "radio",
                ) {
                    currentSong?.let { playerViewModel.startRadioFromSong(it, context) }
                    dismiss()
                }
                PlayerMenuRow(
                    icon = Icons.Default.Share,
                    label = "Share"
                ) {
                    // Share the real Spotify track link when we know the id.
                    val shareText = currentSong?.spotifyTrackId?.takeIf { it.isNotBlank() }
                        ?.let { "https://open.spotify.com/track/$it" }
                        ?: "Listening to $title by $singer"
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(send, "Share"))
                    dismiss()
                }
                PlayerMenuRow(
                    icon = null,
                    iconTint = if (downloaded) Color(AppPalette.toArgb()) else com.music.spotui.ui.theme.AppText,
                    label = when {
                        downloaded -> "Downloaded — remove"
                        downloadingNow -> "Downloading…"
                        else -> "Download"
                    },
                    enabled = currentSong != null && !downloadingNow,
                ) {
                    val song = currentSong ?: return@PlayerMenuRow
                    if (downloaded) {
                        com.music.spotui.data.preferences.removeDownload(context, song.id.toString())
                        downloaded = false
                    } else {
                        downloadingNow = true
                        com.music.spotui.di.Downloads.downloadSong(song, context) { ok ->
                            downloadingNow = false
                            downloaded = ok
                        }
                    }
                }
                PlayerMenuRow(
                    icon = Icons.Default.PlayArrow,
                    iconTint = if (currentAlternative != null) Color(AppPalette.toArgb()) else com.music.spotui.ui.theme.AppText,
                    label = if (currentAlternative == null) "Alternative stream" else "Alternative stream set",
                    enabled = currentSong != null,
                    trailingArrow = true,
                ) {
                    showAlternativeStream = true
                }
                PlayerMenuRow(
                    icon = null,
                    iconTint = if (isLiked.value) Color(AppPalette.toArgb()) else com.music.spotui.ui.theme.AppText,
                    label = if (isLiked.value) "Remove from Liked Songs" else "Add to Liked Songs"
                ) {
                    if (isLiked.value) {
                        removeLikedSongId(context, songId.toString())
                    } else {
                        addLikedSongId(context, songId.toString())
                    }
                    isLiked.value = isSongLiked(context, songId.toString())
                    // Mirror the like to the real Spotify account.
                    com.music.spotui.data.api.SpotifySync.setTrackSaved(
                        context, currentSong?.spotifyTrackId.orEmpty(), isLiked.value)
                    dismiss()
                }
                PlayerMenuRow(
                    icon = Icons.Default.Add,
                    label = "Add to playlist",
                    enabled = currentSong != null,
                    trailingArrow = true,
                ) {
                    showSavedIn = true
                }
                PlayerMenuRow(
                    icon = Icons.AutoMirrored.Filled.List,
                    label = "View queue"
                ) {
                    dismiss()
                    onOpenQueue()
                }
                // Use the track's REAL album (currentSongAlbum is the playing
                // context — a playlist name would resolve to garbage).
                val realAlbum = currentSong?.album?.ifBlank { null } ?: album
                PlayerMenuRow(
                    icon = Icons.Default.PlayArrow,
                    label = "Go to album",
                    enabled = realAlbum.isNotBlank()
                ) {
                    dismiss()
                    navController.navigate(albumRoute(realAlbum, singer))
                }
                PlayerMenuRow(
                    icon = Icons.Default.Person,
                    label = "Go to artist",
                    enabled = singer.isNotBlank()
                ) {
                    dismiss()
                    playerViewModel.goToArtist(currentSong?.spotifyTrackId.orEmpty(), singer) { route ->
                        navController.navigate(route)
                    }
                }
                PlayerMenuRow(
                    icon = Icons.Default.Notifications,
                    label = "Sleep timer",
                    trailingArrow = true
                ) { showSleep = true }
            } else {
                Text(
                    "Sleep timer",
                    color = com.music.spotui.ui.theme.AppText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )
                androidx.compose.material3.HorizontalDivider(color = com.music.spotui.ui.theme.AppRaised)
                val options = listOf(
                    "Off" to 0L,
                    "5 minutes" to 5L,
                    "15 minutes" to 15L,
                    "30 minutes" to 30L,
                    "45 minutes" to 45L,
                    "1 hour" to 60L
                )
                options.forEach { (label, minutes) ->
                    PlayerMenuRow(icon = Icons.Default.Notifications, label = label) {
                        SongPlayer.setSleepTimer(minutes * 60_000L)
                        dismiss()
                    }
                }
            }
        }
    }
}

@Composable
fun AlternativeStreamEditor(
    currentAlternative: com.music.spotui.data.preferences.AlternativeStream?,
    enabled: Boolean,
    onBack: () -> Unit,
    onUseYouTube: (String) -> Unit,
    onPickLocal: () -> Unit,
    onClear: () -> Unit,
) {
    var youtubeText by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                tint = com.music.spotui.ui.theme.AppText,
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onBack() },
                contentDescription = null,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Alternative stream",
                color = com.music.spotui.ui.theme.AppText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = when {
                currentAlternative == null -> "No alternative stream set"
                currentAlternative.isYouTube -> "Current: YouTube video ${currentAlternative.value}"
                currentAlternative.isLocal -> "Current: local file ${currentAlternative.label.ifBlank { currentAlternative.value }}"
                else -> "Current alternative stream"
            },
            color = if (currentAlternative == null) com.music.spotui.ui.theme.AppSecondary else Color(AppPalette.toArgb()),
            fontSize = 13.sp,
            maxLines = 2,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = youtubeText,
            onValueChange = { youtubeText = it },
            enabled = enabled,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = com.music.spotui.ui.theme.AppText, fontSize = 14.sp),
            label = { Text("YouTube link or video ID", color = com.music.spotui.ui.theme.AppSecondary) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(enabled = enabled && youtubeText.isNotBlank(), onClick = { onUseYouTube(youtubeText) }) {
                Text("Use YouTube", color = if (enabled && youtubeText.isNotBlank()) AppPalette else Color.Gray)
            }
        }
        PlayerMenuRow(
            icon = Icons.Default.Add,
            label = "Use local audio file",
            enabled = enabled,
        ) {
            onPickLocal()
        }
        PlayerMenuRow(
            icon = Icons.Default.CheckCircle,
            label = "Clear alternative stream",
            enabled = enabled && currentAlternative != null,
            iconTint = Color(0xFFE57373),
        ) {
            onClear()
        }
    }
}

@Composable
fun PlayerMenuRow(
    icon: ImageVector?,
    label: String,
    iconTint: Color = com.music.spotui.ui.theme.AppText,
    enabled: Boolean = true,
    trailingArrow: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clip(RoundedCornerShape(14.dp))
            .motionClick(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp, vertical = 13.dp)
    ) {
        if (icon != null) {
        Icon(
            imageVector = icon,
            tint = if (enabled) iconTint else Color.Gray,
            modifier = Modifier.size(22.dp),
            contentDescription = null
        )
        Spacer(modifier = Modifier.width(20.dp))
        }
        Text(
            text = label,
            color = if (enabled) com.music.spotui.ui.theme.AppText else Color.Gray,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        if (trailingArrow) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                tint = Color.Gray,
                modifier = Modifier.size(20.dp),
                contentDescription = null
            )
        }
    }
}
/**
 * Plays a Spotify Canvas clip: a short, muted, looping video filling the
 * now-playing background. Uses a dedicated ExoPlayer (separate from the audio
 * engine) released when the composable leaves. Falls back to nothing if the URL fails.
 *
 * Loops are served from the media cache: without it every loop re-downloaded the
 * clip, and since ExoPlayer ignores the activity lifecycle it kept doing so in the
 * background (#87, gigabytes per hour). It also pauses while the app is not visible.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun CanvasVideo(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exo = remember(url) {
        val cached = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(SongPlayer.mediaCache(context.applicationContext))
            .setUpstreamDataSourceFactory(androidx.media3.datasource.DefaultHttpDataSource.Factory())
        androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cached))
            .build().apply {
                setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
                repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                volume = 0f
                playWhenReady = true
                prepare()
            }
    }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(url, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            exo.playWhenReady = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            exo.release()
        }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        // factory runs once; a new track's clip gets a new player, so rebind it here.
        update = { if (it.player !== exo) it.player = exo },
        onRelease = { it.player = null },
        factory = { ctx ->
            // TextureView participates in the same composition as the page, including its exit.
            (android.view.LayoutInflater.from(ctx).inflate(R.layout.uspot_canvas_video, null, false) as androidx.media3.ui.PlayerView).apply {
                player = exo
                (videoSurfaceView as? android.view.TextureView)?.isOpaque = false
                // Strip ALL chrome: no controller, no buffering spinner, and no
                // artwork/placeholder icon (that "play icon" overlay) — just video.
                useController = false
                controllerAutoShow = false
                setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_NEVER)
                setArtworkDisplayMode(androidx.media3.ui.PlayerView.ARTWORK_DISPLAY_MODE_OFF)
                setUseArtwork(false)
                setDefaultArtwork(null)
                hideController()
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                // Until a clip's first frame, show the screen behind, not a black shutter.
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setKeepContentOnPlayerReset(true)
            }
        },
    )
}
