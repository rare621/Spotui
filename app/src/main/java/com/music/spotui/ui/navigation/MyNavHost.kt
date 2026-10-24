package com.music.spotui.ui.navigation

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.music.spotui.data.api.SpotifySession
import com.music.spotui.ui.screens.SpotifyLoginScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.music.spotui.ui.screens.AlbumScreen
import com.music.spotui.ui.screens.ArtistReleasesScreen
import com.music.spotui.ui.screens.ArtistScreen
import com.music.spotui.ui.screens.CategoryScreen
import com.music.spotui.ui.screens.DeezerLoginScreen
import com.music.spotui.ui.screens.DeezerIntroScreen
import com.music.spotui.ui.screens.MusicSourceScreen
import com.music.spotui.ui.screens.YouTubeLoginScreen
import com.music.spotui.ui.screens.LocalFilesScreen
import com.music.spotui.ui.screens.SpotiflacVerifyScreen
import com.music.spotui.ui.screens.DownloadsScreen
import com.music.spotui.ui.screens.HistoryScreen
import com.music.spotui.ui.screens.HomeScreen
import com.music.spotui.ui.screens.LibraryScreen
import com.music.spotui.ui.screens.LikedSongsScreen
import com.music.spotui.ui.screens.PlayerScreen
import com.music.spotui.ui.screens.PlaylistScreen
import com.music.spotui.ui.screens.ShowScreen
import com.music.spotui.ui.screens.QueueScreen
import com.music.spotui.ui.screens.SearchScreen
import com.music.spotui.ui.screens.SettingsScreen
import com.music.spotui.ui.viewmodel.PlayerViewModel

@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun MyNavHost(
    navHostController: NavHostController,
    bottomBarState: MutableState<Boolean>,
    bottomBarPlayerState: MutableState<Boolean>
) {

    val playerViewModel : PlayerViewModel = hiltViewModel()
    val playerState = playerViewModel.currentSongTitle



//    val context = LocalContext.current
//    var player : ExoPlayer? = null
//    player = ExoPlayer.Builder(context).build()

    val context = LocalContext.current
    val startDestination = androidx.compose.runtime.remember {
        if (!com.music.spotui.setup.SetupProgress.completed(context)) Routes.Setup.route else Routes.Home.route
    }

    // Restore the last session: put the track back into the mini player (paused)
    // and arm the player to resume from the saved position on the first play tap.
    LaunchedEffect(Unit) {
        if (playerViewModel.currentSongTitle.value.isBlank()) {
            com.music.spotui.data.preferences.loadLastPlayback(context)?.let { (song, positionMs) ->
                val snapshot = com.music.spotui.data.preferences.loadPlaybackQueue(context)
                if (snapshot != null && snapshot.songs.any { it.id == song.id }) playerViewModel.restoreQueue(snapshot)
                else playerViewModel.updateQueue(listOf(song))
                playerViewModel.updateSongState(
                    song.coverUri, song.title, song.singer, false, song.id, playerViewModel.queue.value.indexOfFirst { it.id == song.id }.coerceAtLeast(0), song.album)
                com.music.spotui.di.SongPlayer.ensureCreated(context)
                com.music.spotui.di.SongPlayer.setRestorePoint(song.url, positionMs)
                if (startDestination != Routes.Setup.route && com.music.spotui.data.preferences.wasPlaybackActive(context)) {
                    com.music.spotui.di.SongPlayer.playSong(song.url, context)
                }
            }
        }
    }

    com.music.spotui.ui.screens.StartupConnectionCheck(navHostController)

    NavHost(
        navController = navHostController,
        startDestination = startDestination,
        // Small compositor transitions across every route; no list remeasurement.
        enterTransition = { fadeIn(tween(200)) + slideInHorizontally(tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it / 24 } },
        exitTransition = { fadeOut(tween(110)) },
        popEnterTransition = { fadeIn(tween(180)) + slideInHorizontally(tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it / 24 } },
        popExitTransition = { fadeOut(tween(150)) + slideOutHorizontally(tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it / 24 } },
    ){
        composable(Routes.Setup.route) {
            LaunchedEffect(Unit) { bottomBarState.value = false; bottomBarPlayerState.value = false }
            com.music.spotui.ui.screens.SetupScreen(navHostController)
        }
        composable(Routes.Login.route){
            LaunchedEffect(Unit) {
                bottomBarState.value = false
                bottomBarPlayerState.value = false
            }
            SpotifyLoginScreen(navHostController)
        }
        composable(Routes.Home.route){
            LaunchedEffect(playerState.value) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState.value != ""
            }
                HomeScreen(navHostController)
        }
        composable(Routes.Search.route){
            LaunchedEffect(playerState.value) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState.value != ""
            }
            SearchScreen(navHostController)
        }
        composable(Routes.Library.route) {
            LaunchedEffect(playerState.value) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState.value != ""
            }
            LibraryScreen(navHostController)
        }
        composable(
            Routes.Player.route,
            enterTransition = { slideInVertically(tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it / 5 } + fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(140)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { slideOutVertically(tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it / 5 } + fadeOut(tween(200)) },
        ) {
            LaunchedEffect(playerState.value) {
                bottomBarState.value = false
                bottomBarPlayerState.value = playerState.value != ""
            }
            PlayerScreen(navHostController)
        }

        composable(Routes.Queue.route){
            LaunchedEffect(playerState.value) {
                bottomBarState.value = false
                bottomBarPlayerState.value = false
            }
            QueueScreen(navHostController)
        }

        composable(Routes.Liked.route){
            LaunchedEffect(playerState.value) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState.value != ""
            }
            LikedSongsScreen(navHostController)
        }

        composable(Routes.Downloads.route){
            LaunchedEffect(playerState.value) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState.value != ""
            }
            DownloadsScreen(navHostController)
        }

        composable(Routes.Settings.route){
            LaunchedEffect(playerState.value) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState.value != ""
            }
            SettingsScreen(navHostController)
        }

        composable("${Routes.Radio.route}/{seedId}") { entry ->
            LaunchedEffect(playerState.value) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState.value != ""
            }
            com.music.spotui.ui.screens.RadioScreen(navHostController, entry.arguments?.getString("seedId").orEmpty())
        }
        composable(Routes.History.route){
            LaunchedEffect(playerState.value) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState.value != ""
            }
            HistoryScreen(navHostController)
        }

        composable(
            "${Routes.DeezerLogin.route}?next={next}",
            arguments = listOf(navArgument("next") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState.value) {
                bottomBarState.value = false
                bottomBarPlayerState.value = false
            }
            DeezerLoginScreen(
                navHostController,
                next = navBackStackEntry.arguments?.getString("next").orEmpty(),
            )
        }

        composable(Routes.DeezerIntro.route){
            LaunchedEffect(playerState.value) {
                bottomBarState.value = false
                bottomBarPlayerState.value = playerState.value != ""
            }
            DeezerIntroScreen(navHostController)
        }

        composable(Routes.MusicSource.route) {
            LaunchedEffect(Unit) {
                bottomBarState.value = false
                bottomBarPlayerState.value = false
            }
            com.music.spotui.ui.screens.SetupScreen(navHostController)
        }

        composable(
            "${Routes.YouTubeLogin.route}?next={next}",
            arguments = listOf(navArgument("next") { defaultValue = "" }),
        ) { entry ->
            LaunchedEffect(Unit) {
                bottomBarState.value = false
                bottomBarPlayerState.value = false
            }
            YouTubeLoginScreen(navHostController, entry.arguments?.getString("next").orEmpty())
        }

        composable(Routes.LocalFiles.route){
            LaunchedEffect(playerState.value) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState.value != ""
            }
            LocalFilesScreen(navHostController)
        }

        composable(
            "${Routes.SpotiflacVerify.route}?next={next}",
            arguments = listOf(navArgument("next") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState.value) {
                bottomBarState.value = false
                bottomBarPlayerState.value = false
            }
            SpotiflacVerifyScreen(
                navHostController,
                next = navBackStackEntry.arguments?.getString("next").orEmpty(),
            )
        }

        composable(
            "${Routes.Category.route}/{genre}?title={title}",
            arguments = listOf(navArgument("title") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState.value) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState.value != ""
            }
            val genre = navBackStackEntry.arguments?.getString("genre").orEmpty()
            val title = navBackStackEntry.arguments?.getString("title").orEmpty()
            CategoryScreen(navHostController, genre = genre, title = title.ifBlank { genre })
        }

        composable(
            "${Routes.Album.route}/{uString}?artist={artist}",
            arguments = listOf(navArgument("artist") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState.value) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState.value != ""
            }

            /* Extracting the id from the route */
            val uId = navBackStackEntry.arguments?.getString("uString")
            val artist = navBackStackEntry.arguments?.getString("artist").orEmpty()
            /* We check if it's not null */
            uId?.let { id->
                AlbumScreen(navController = navHostController, albumName = id, artist = artist)
            }
        }

        composable(
            "${Routes.Playlist.route}/{pId}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState.value) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState.value != ""
            }
            val pId = navBackStackEntry.arguments?.getString("pId")
            val name = navBackStackEntry.arguments?.getString("name").orEmpty()
            pId?.let { PlaylistScreen(navHostController, playlistId = it, playlistName = name) }
        }

        composable(
            "${Routes.Show.route}/{sId}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState.value) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState.value != ""
            }
            val sId = navBackStackEntry.arguments?.getString("sId")
            val name = navBackStackEntry.arguments?.getString("name").orEmpty()
            sId?.let { ShowScreen(navHostController, showId = it, showName = name) }
        }

        composable("${Routes.ArtistReleases.route}/{aString}") { navBackStackEntry ->
            LaunchedEffect(playerState.value) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState.value != ""
            }
            val aId = navBackStackEntry.arguments?.getString("aString")
            aId?.let { ArtistReleasesScreen(navHostController, it) }
        }

        composable(
            "${Routes.Artist.route}/{aString}?id={artistId}",
            arguments = listOf(navArgument("artistId") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState.value) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState.value != ""
            }

            /* Extracting the id from the route */
            val aId = navBackStackEntry.arguments?.getString("aString")
            val artistId = navBackStackEntry.arguments?.getString("artistId").orEmpty()
            /* We check if it's not null */
            aId?.let { aid->
                ArtistScreen(navHostController, aid, artistId)
            }
        }
    }
}
