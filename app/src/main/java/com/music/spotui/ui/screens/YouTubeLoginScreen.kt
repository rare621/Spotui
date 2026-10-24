package com.music.spotui.ui.screens

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.music.spotui.data.preferences.*
import com.music.spotui.setup.*
import com.music.spotui.ui.navigation.Routes
import kotlinx.coroutines.*

@Composable
fun YouTubeLoginScreen(navController: NavController, next: String = "") {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var busy by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Sign in to YouTube Music. We will check the account before saving it.") }
    val leave: () -> Unit = { SetupProgress.setPage(context, 1); navController.navigate(Routes.Setup.route) { popUpTo(Routes.Setup.route) { inclusive = true }; launchSingleTop = true } }
    LaunchedEffect(attempt) {
        busy = false; blocked = false
        while (isActive) {
            delay(750)
            if (blocked || android.net.Uri.parse(web?.url.orEmpty()).host != "music.youtube.com") continue
            val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
            if (cookie.split(';').none { it.substringBefore('=').trim() == "SAPISID" }) continue
            busy = true; status = "Checking your YouTube Music account…"
            try {
                if (SetupChecks.youtube(context, cookie)) {
                    setPrimaryMusicSource(context, MusicSource.YOUTUBE_MUSIC)
                    SetupProgress.choose(context, MusicSource.YOUTUBE_MUSIC.name)
                    SetupProgress.mark(context, "source", true)
                    SetupProgress.setPage(context, SetupWizardPolicy.afterSourceLogin())
                    navController.navigate(Routes.Setup.route) { popUpTo(Routes.Setup.route) { inclusive = true }; launchSingleTop = true }
                    return@LaunchedEffect
                }
                status = "YouTube could not confirm this account. Complete sign-in, then tap Retry."
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { status = SetupChecks.failure(e) }
            busy = false; blocked = true
        }
    }
    key(attempt) {
        SetupWebPage("Connect YouTube Music", "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F",
            status, busy, { attempt++ }, leave, { web = it }, { status = it })
    }
}
