package com.music.spotui.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.music.spotui.data.api.SpotifySession
import com.music.spotui.data.preferences.*
import com.music.spotui.setup.*
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.theme.*
import kotlinx.coroutines.*

@Composable
fun SetupScreen(navController: NavController) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { SetupProgress.begin(c); true }
    var spotify by remember { mutableStateOf(SetupProgress.verified(c, "spotify") && SpotifySession.spDc(c).isNotBlank()) }
    var sourceChoice by remember { mutableStateOf(SetupProgress.choice(c)?.let { runCatching { MusicSource.valueOf(it) }.getOrNull() } ?: getPrimaryMusicSource(c) ?: MusicSource.YOUTUBE_MUSIC) }
    fun savedSource(choice: MusicSource) = when (choice) {
        MusicSource.YOUTUBE_MUSIC -> isYoutubeLoggedIn(c)
        MusicSource.DEEZER -> !getDeezerArl(c).isNullOrBlank()
    }
    var source by remember { mutableStateOf(SetupProgress.verified(c, "source") && getPrimaryMusicSource(c) == sourceChoice && savedSource(sourceChoice)) }
    fun validLossless() = SetupProgress.verified(c, "lossless") && getSpotiflacSession(c)?.let { SetupProgress.sessionCurrent(it.expiresAt) } == true
    var lossless by remember { mutableStateOf(validLossless()) }
    var page by remember { mutableIntStateOf(SetupWizardPolicy.resumePage(SetupProgress.page(c), spotify, source, lossless)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Your progress is saved on this device.") }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    fun stopWork() {
        generation++; job?.cancel(); job = null
        busy = false
    }
    fun move(next: Int) {
        stopWork()
        page = next; SetupProgress.setPage(c, next)
        status = "Your progress is saved on this device."
    }
    fun back() {
        if (page > 0) move(SetupWizardPolicy.back(page))
        else { stopWork(); (c as? android.app.Activity)?.finish() }
    }
    BackHandler { back() }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    fun login(route: String) {
        stopWork(); SetupProgress.setPage(c, page)
        navController.navigate(route) { launchSingleTop = true }
    }
    fun checkSpotify() {
        if (SpotifySession.spDc(c).isBlank()) { login(Routes.Login.route); return }
        stopWork(); busy = true; val ticket = generation
        job = scope.launch {
            try {
                status = "Checking Spotify…"
                val ok = SetupChecks.spotify(c); ensureActive()
                spotify = ok; SetupProgress.mark(c, "spotify", ok)
                if (ok) move(1) else status = "Spotify could not be verified. Retry or sign in again."
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { status = SetupChecks.failure(e)
            } finally { if (generation == ticket) busy = false }
        }
    }
    fun checkSource() {
        val route = if (sourceChoice == MusicSource.YOUTUBE_MUSIC) Routes.YouTubeLogin.route else Routes.DeezerLogin.route
        if (!savedSource(sourceChoice)) { login("$route?next=guided"); return }
        stopWork(); busy = true; val ticket = generation
        job = scope.launch {
            try {
                status = "Checking ${sourceChoice.label}…"
                val ok = when (sourceChoice) {
                    MusicSource.YOUTUBE_MUSIC -> SetupChecks.youtube(c)
                    MusicSource.DEEZER -> SetupChecks.deezer(c)
                }
                ensureActive()
                source = ok; SetupProgress.mark(c, "source", ok)
                if (ok) {
                    setPrimaryMusicSource(c, sourceChoice)
                    move(2)
                } else status = "${sourceChoice.label} could not be verified. Retry or sign in again."
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { status = SetupChecks.failure(e)
            } finally { if (generation == ticket) busy = false }
        }
    }
    Column(Modifier.fillMaxSize().background(AppBackground).safeDrawingPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (page > 0) TextButton(onClick = { back() }) { Text("‹ Back", color = AppText) }
        Text("Welcome to USpot", color = AppText, style = MaterialTheme.typography.headlineLarge)
        Text("Spotify → audio account → SpotiFLAC", color = AppSecondary)
        Text(if (page < 3) "Step ${page + 1} of 3" else "All connected", color = AppPalette)
        LinearProgressIndicator(progress = { page / 3f }, color = AppPalette, trackColor = AppRaised, modifier = Modifier.fillMaxWidth())
        when (page) {
            0 -> SetupCard("Connect Spotify", if (spotify) "Your Spotify connection is saved. Continue to verify it." else "Connect Spotify for your library and recommendations.") {
                SetupButton("Continue with Spotify", !busy) { checkSpotify() }
                if (SpotifySession.spDc(c).isNotBlank()) TextButton(onClick = { login(Routes.Login.route) }, enabled = !busy) { Text("Sign in again", color = AppPalette) }
            }
            1 -> SetupCard("Choose your audio account", "Choose YouTube Music or Deezer. This step always needs your confirmation.") {
                MusicSource.entries.forEach { choice ->
                    FilterChip(selected = sourceChoice == choice, enabled = !busy, onClick = {
                        if (sourceChoice != choice) { sourceChoice = choice; SetupProgress.choose(c, choice.name); source = false; SetupProgress.mark(c, "source", false) }
                    }, label = { Text(choice.label) })
                }
                Text(if (savedSource(sourceChoice)) "Saved ${sourceChoice.label} account found. Continue to verify and use it." else "Sign in to ${sourceChoice.label} to connect audio.", color = AppSecondary)
                SetupButton("Continue with ${sourceChoice.label}", !busy) { checkSource() }
                if (savedSource(sourceChoice)) TextButton(onClick = {
                    login("${if (sourceChoice == MusicSource.YOUTUBE_MUSIC) Routes.YouTubeLogin.route else Routes.DeezerLogin.route}?next=guided")
                }, enabled = !busy) { Text("Open sign-in page", color = AppPalette) }
            }
            2 -> SetupCard("Connect SpotiFLAC", if (lossless) "Your verified session is saved. Continue to finish setup." else "Complete verification to receive a SpotiFLAC session.") {
                SetupButton(if (lossless) "Continue" else "Verify SpotiFLAC", !busy) {
                    lossless = validLossless()
                    if (lossless) move(3) else login("${Routes.SpotiflacVerify.route}?next=guided")
                }
                if (lossless) TextButton(onClick = { login("${Routes.SpotiflacVerify.route}?next=guided") }, enabled = !busy) { Text("Verify again", color = AppPalette) }
            }
            3 -> SetupCard("You're connected", "Spotify, ${sourceChoice.label}, and SpotiFLAC are connected. You're ready to open USpot.") {
                SetupButton("Start listening", SetupWizardPolicy.canFinish(spotify, source, lossless)) {
                    lossless = validLossless()
                    if (!lossless) { move(2); status = "Your SpotiFLAC session expired. Verify it again to finish setup." }
                    else {
                        SetupProgress.finish(c)
                        navController.navigate(Routes.Home.route) { popUpTo(Routes.Setup.route) { inclusive = true }; launchSingleTop = true }
                    }
                }
            }
        }
        if (busy) LinearProgressIndicator(color = AppPalette, modifier = Modifier.fillMaxWidth())
        Text(status, color = AppSecondary)
        Text("Going Back keeps completed connections. Changing your audio account requires a new check.", color = AppSecondary)
        Spacer(Modifier.height(100.dp))
    }
}

@Composable private fun SetupCard(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(AppSurface, RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = AppText, style = MaterialTheme.typography.titleMedium)
        Text(description, color = AppSecondary)
        content()
    }
}
@Composable private fun SetupButton(text: String, enabled: Boolean = true, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = AppPalette, contentColor = androidx.compose.ui.graphics.Color.Black)) { Text(text) }
}
