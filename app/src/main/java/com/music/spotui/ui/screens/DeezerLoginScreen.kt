package com.music.spotui.ui.screens

import android.webkit.CookieManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.music.spotui.data.preferences.*
import com.music.spotui.deezer.DeezerSession
import com.music.spotui.setup.*
import com.music.spotui.ui.navigation.Routes
import kotlinx.coroutines.*

@Composable
fun DeezerLoginScreen(navController: NavController, next: String = "") {
    val c = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Sign in to connect your Deezer audio account.") }
    var busy by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    val leave: () -> Unit = { SetupProgress.setPage(c, 1); navController.navigate(Routes.Setup.route) { popUpTo(Routes.Setup.route) { inclusive = true }; launchSingleTop = true } }
    LaunchedEffect(attempt) {
        busy = false; blocked = false
        while (isActive) {
            delay(750)
            if (blocked) continue
            val cookie = CookieManager.getInstance().getCookie("https://www.deezer.com").orEmpty()
                .split(';').firstOrNull { it.substringBefore('=').trim() == "arl" }?.substringAfter('=').orEmpty()
            if (cookie.isBlank()) continue
            busy = true; status = "Checking your Deezer account…"
            val previous = getDeezerArl(c).orEmpty()
            var committed = false
            try {
                if (SetupChecks.deezer(c, cookie)) {
                    ensureActive()
                    setDeezerArl(c, cookie)
                    setDeezerTier(c, when (DeezerSession.entitledQuality) {
                        DeezerSession.QUALITY_FLAC -> "Premium (FLAC)"
                        DeezerSession.QUALITY_MP3_320 -> "Premium (MP3 320)"
                        else -> "Free (MP3 128)"
                    })
                    setPrimaryMusicSource(c, MusicSource.DEEZER)
                    SetupProgress.choose(c, MusicSource.DEEZER.name)
                    SetupProgress.mark(c, "source", true)
                    committed = true; leave(); return@LaunchedEffect
                }
                status = "Deezer could not confirm this account. Tap Retry to reconnect."
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { status = SetupChecks.failure(e)
            } finally { if (!committed) DeezerSession.setArl(previous) }
            busy = false; blocked = true
        }
    }
    key(attempt) {
        SetupWebPage("Connect Deezer", "https://www.deezer.com/login", status, busy,
            onRetry = { attempt++ }, onBack = leave, onReady = {}, onPageError = { status = it })
    }
}
