package com.music.spotui.ui.screens

import android.app.Activity
import android.webkit.CookieManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.metrolist.spotify.SpotifyAuth
import com.music.spotui.data.api.SpotifyTokenProvider
import com.music.spotui.setup.*
import com.music.spotui.ui.navigation.Routes
import kotlinx.coroutines.*

@Composable
fun SpotifyLoginScreen(navController: NavController) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Sign in to connect your Spotify library.") }
    var busy by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    val leave: () -> Unit = { SetupProgress.setPage(context, 0); navController.navigate(Routes.Setup.route) { popUpTo(Routes.Setup.route) { inclusive = true }; launchSingleTop = true } }
    LaunchedEffect(Unit) { com.music.spotui.di.SpotifyWebPlayer.detach() }
    DisposableEffect(Unit) { onDispose { (context as? Activity)?.let { com.music.spotui.di.SpotifyWebPlayer.attach(it) } } }
    LaunchedEffect(attempt) {
        busy = false; blocked = false
        while (isActive) {
            delay(750)
            if (blocked) continue
            val cookie = CookieManager.getInstance().getCookie("https://open.spotify.com").orEmpty()
                .split(';').firstOrNull { it.substringBefore('=').trim() == "sp_dc" }?.substringAfter('=').orEmpty()
            if (cookie.isBlank()) continue
            busy = true; status = "Checking your Spotify account…"
            try {
                val ok = withTimeoutOrNull(30_000) { SpotifyTokenProvider.connectVerified(context, cookie) } == true
                if (ok) {
                    SetupProgress.mark(context, "spotify", true)
                    (context as? Activity)?.let { com.music.spotui.di.SpotifyWebPlayer.refreshLogin(it) }
                    status = "Spotify account verified"
                    SetupProgress.setPage(context, SetupWizardPolicy.afterSpotify())
                    navController.navigate(Routes.Setup.route) { popUpTo(Routes.Setup.route) { inclusive = true }; launchSingleTop = true }
                    return@LaunchedEffect
                }
                status = "Spotify could not confirm the connection. Your saved setup is safe. Tap Retry."
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { status = SetupChecks.failure(e) }
            busy = false; blocked = true
        }
    }
    key(attempt) {
        SetupWebPage("Connect Spotify", SpotifyAuth.LOGIN_URL, status, busy,
            onRetry = { attempt++ }, onBack = leave, onReady = {}, onPageError = { status = it }, spotifyLayout = true)
    }
}
