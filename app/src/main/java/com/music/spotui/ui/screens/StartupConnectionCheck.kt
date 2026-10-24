package com.music.spotui.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.metrolist.innertube.YouTube
import com.music.spotui.BuildConfig
import com.music.spotui.data.api.SpotifySession
import com.music.spotui.data.preferences.*
import com.music.spotui.setup.*
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.theme.*
import kotlinx.coroutines.*

/** Checks on foreground entry, including the first launch after an update. Never logs out on a network failure. */
@Composable
fun StartupConnectionCheck(nav: NavHostController) {
    val c = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val entry by nav.currentBackStackEntryAsState()
    val route by rememberUpdatedState(entry?.destination?.route.orEmpty())
    var job by remember { mutableStateOf<Job?>(null) }
    var last by remember { mutableLongStateOf(0L) }
    var lastBuild by remember { mutableIntStateOf(0) }
    var issues by remember { mutableStateOf(emptyList<String>()) }
    var needsSetup by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val inSetup = listOf("setup", "login", "musicsource", "youtubelogin", "deezerlogin", "spotiflacverify").any { route.startsWith(it) }
    LaunchedEffect(inSetup) {
        if (inSetup) { job?.cancel(); job = null; busy = false; issues = emptyList() }
    }
    fun runCheck(force: Boolean = false) {
        if (!SetupProgress.completed(c) || job?.isActive == true) return
        if (listOf("setup", "login", "musicsource", "youtubelogin", "deezerlogin", "spotiflacverify").any { route.startsWith(it) }) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && !StartupCheckPolicy.shouldCheck(now, last, BuildConfig.VERSION_CODE, lastBuild)) return
        last = now; lastBuild = BuildConfig.VERSION_CODE; busy = true
        job = scope.launch {
            try {
                val missing = mutableListOf<String>()
                val spotifyPresent = SpotifySession.spDc(c).isNotBlank()
                val selected = getPrimaryMusicSource(c)
                val audioPresent = when (selected) {
                    MusicSource.YOUTUBE_MUSIC -> isYoutubeLoggedIn(c)
                    MusicSource.DEEZER -> !getDeezerArl(c).isNullOrBlank()
                    null -> false
                }
                if (!spotifyPresent) missing += "Reconnect Spotify to restore library access."
                if (!audioPresent) missing += "Reconnect your audio account."
                needsSetup = StartupCheckPolicy.needsSignIn(StartupCheckPolicy.connection(spotifyPresent, false, false)) || StartupCheckPolicy.needsSignIn(StartupCheckPolicy.connection(audioPresent, false, false))
                val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val online = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                if (online) {
                    suspend fun safe(check: suspend () -> Boolean): Boolean = try { check() } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                    val spotify = async { if (spotifyPresent) safe { SetupChecks.spotify(c) } else false }
                    val audio = async {
                        if (!audioPresent) false else safe {
                            withContext(Dispatchers.IO) {
                                withTimeoutOrNull(20_000) {
                                    when (selected) {
                                        MusicSource.YOUTUBE_MUSIC -> YouTube.validateLogin().getOrThrow()
                                        MusicSource.DEEZER -> {
                                            com.music.spotui.deezer.DeezerSession.setArl(getDeezerArl(c).orEmpty())
                                            com.music.spotui.deezer.DeezerSession.validateAccount()
                                        }
                                        null -> false
                                    }
                                } == true
                            }
                        }
                    }
                    if (!spotify.await() && spotifyPresent) missing += "Spotify could not be verified. Retry before signing in again."
                    if (!audio.await() && audioPresent) missing += "Your audio account could not be verified. Retry before signing in again."
                } else if (force) missing += "You're offline. Saved music is still available; retry when connected."
                if (getSpotiflacSession(c) == null) { missing += "Complete SpotiFLAC verification in setup."; needsSetup = true }
                getSpotiflacSession(c)?.let {
                    if (!SetupProgress.sessionCurrent(it.expiresAt)) missing += "Your SpotiFLAC session has expired. Verify SpotiFLAC again in setup."
                }
                issues = missing
                PlaybackDiagnostics.record(c, "Startup connection check",
                    when { missing.isNotEmpty() -> "Build ${BuildConfig.VERSION_CODE}: attention needed"
                        !online -> "Offline: online checks deferred"
                        else -> "Build ${BuildConfig.VERSION_CODE}: accounts confirmed; playback checked when requested" }, song = "")
                c.getSharedPreferences("uspot_setup", 0).edit().putInt("last_checked_build", BuildConfig.VERSION_CODE).apply()
            } finally { busy = false }
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) runCheck()
            if (event == Lifecycle.Event.ON_STOP) { job?.cancel(); job = null; busy = false }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) runCheck()
        onDispose { owner.lifecycle.removeObserver(observer); job?.cancel() }
    }
    if (issues.isNotEmpty() && !inSetup) {
        AlertDialog(
            onDismissRequest = { issues = emptyList() }, containerColor = AppSurface,
            title = { Text(if (needsSetup) "Finish connecting your music" else "Check your connections", color = AppText) },
            text = { Text(issues.joinToString("\n\n") + "\n\nYour saved accounts and music data have been kept.", color = AppSecondary) },
            confirmButton = { TextButton(onClick = { issues = emptyList(); job?.cancel(); SetupProgress.restart(c); nav.navigate(Routes.Setup.route) { launchSingleTop = true } }) { Text("Open setup", color = AppPalette) } },
            dismissButton = { androidx.compose.foundation.layout.Row {
                TextButton(onClick = { runCheck(true) }, enabled = !busy) { Text(if (busy) "Checking…" else "Retry", color = AppPalette) }
                TextButton(onClick = { issues = emptyList() }) { Text("Later", color = AppSecondary) }
            } }
        )
    }
}
