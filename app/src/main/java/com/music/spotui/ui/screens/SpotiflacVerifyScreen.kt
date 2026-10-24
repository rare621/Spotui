package com.music.spotui.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.music.spotui.data.preferences.*
import com.music.spotui.lossless.LosslessRegistry
import com.music.spotui.setup.*
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.theme.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun SpotiflacVerifyScreen(navController: NavController, next: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val guard = remember { VerificationAttempt() }
    var attempt by remember { mutableIntStateOf(0) }
    var ticket by remember { mutableStateOf<VerificationAttempt.Ticket?>(null) }
    var challenge by remember { mutableStateOf<String?>(null) }
    var base by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Starting secure verification…") }
    var busy by remember { mutableStateOf(true) }
    var exchangeJob by remember { mutableStateOf<Job?>(null) }
    val installId = remember { getSpotiflacSession(context)?.installId?.takeIf { it.isNotBlank() } ?: SetupProgress.installId(context) }
    fun log(event: String, detail: String = "") = PlaybackDiagnostics.record(context, event, detail, song = "")
    val leave: () -> Unit = {
        guard.invalidate(); exchangeJob?.cancel()
        SetupProgress.setPage(context, 2)
        navController.navigate(Routes.Setup.route) { popUpTo(Routes.Setup.route) { inclusive = true }; launchSingleTop = true }
    }
    val retry: () -> Unit = { guard.invalidate(); exchangeJob?.cancel(); challenge = null; attempt++ }
    BackHandler(onBack = leave)
    DisposableEffect(Unit) { onDispose { guard.invalidate(); exchangeJob?.cancel() } }
    LaunchedEffect(attempt) {
        val active = guard.begin(); ticket = active; challenge = null; busy = true
        status = "Starting secure verification…"
        log("Verification started", "SpotiFLAC bootstrap")
        try {
            val pair = withContext(Dispatchers.IO) {
                val endpoint = LosslessRegistry.communityVerifyUrl()
                val response = JSONObject(verificationRequest("$endpoint/bootstrap?install_id=$installId&app_version=4.8.5&platform=android"))
                endpoint to VerificationAttempt.challengeUrl(response.getString("challenge_url"), active)
            }
            if (!guard.isCurrent(active)) return@LaunchedEffect
            base = pair.first; challenge = pair.second; busy = false
            status = "Complete the verification above. USpot will check the issued session before completing setup."
            log("Verification page ready", "Waiting for user")
            delay(180_000)
            if (guard.isCurrent(active) && exchangeJob?.isActive != true) {
                guard.invalidate()
                status = "This verification has expired. Tap Retry to start a fresh one."
                log("Verification failed", "Challenge deadline reached")
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            busy = false; status = verificationError(e)
            log("Verification failed", "Bootstrap: ${verificationCategory(e)}")
        }
    }
    val url = challenge
    if (url == null) {
        Column(Modifier.fillMaxSize().background(AppBackground).safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Connect SpotiFLAC", color = AppText, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp)); Text(status, color = AppSecondary)
            if (busy) { Spacer(Modifier.height(16.dp)); LinearProgressIndicator(color = AppPalette) }
            TextButton(onClick = retry, enabled = !busy) { Text("Retry", color = AppPalette) }
            TextButton(onClick = leave) { Text("Back to setup", color = AppSecondary) }
        }
    } else key(attempt) {
        SetupWebPage("Verify SpotiFLAC", url, status, busy, retry, leave, {}, {
            status = it; log("Verification page failed", "Page network or HTTP error")
        }) { callback ->
            if (android.net.Uri.parse(callback).scheme != "spotiflac") false else {
                val active = ticket
                val grant = active?.let { guard.accept(it, callback) }
                if (active != null && grant != null) {
                    val endpoint = base
                    busy = true; status = "Verification passed. Checking the session…"
                    log("Verification grant received", "Exchanging once")
                    exchangeJob = scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                JSONObject(verificationRequest("$endpoint/session/exchange", JSONObject()
                                    .put("grant", grant).put("install_id", installId)
                                    .put("app_version", "4.8.5").put("platform", "android").toString()))
                            }
                            if (!guard.isCurrent(active)) return@launch
                            val id = result.optString("session_id"); val secret = result.optString("session_secret")
                            check(id.isNotBlank() && secret.isNotBlank() && SetupProgress.sessionCurrent(result.optString("expires_at")))
                            saveSpotiflacSession(context, id, secret, installId, result.optString("expires_at"))
                            SetupProgress.mark(context, "lossless", true)
                            log("Verification session saved", "SpotiFLAC session issued")
                            guard.invalidate()
                            SetupProgress.setPage(context, SetupWizardPolicy.afterLossless())
                            navController.navigate(Routes.Setup.route) { popUpTo(Routes.Setup.route) { inclusive = true }; launchSingleTop = true }
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) {
                            if (guard.isCurrent(active)) {
                                busy = false; status = verificationError(e)
                                log("Verification failed", "Exchange: ${verificationCategory(e)}")
                            }
                        }
                    }
                } else if (exchangeJob?.isActive != true) {
                    status = "That verification is no longer valid. Tap Retry for a new attempt."
                    log("Verification callback ignored", "Stale invalid or duplicate callback")
                }
                true
            }
        }
    }
}

private class VerificationHttpError(val status: Int) : java.io.IOException()
private fun verificationCategory(e: Exception): String = when (e) {
    is VerificationHttpError -> "HTTP ${e.status}"
    is java.net.SocketTimeoutException -> "Connection timeout"
    is java.net.UnknownHostException -> "Host unavailable"
    else -> "Invalid response or connection failure"
}
private fun verificationError(e: Exception): String = when (e) {
    is VerificationHttpError -> when (e.status) {
        401, 403 -> "The service rejected verification. Tap Retry for a fresh challenge. This step must pass before setup can continue."
        429 -> "The service is receiving too many attempts. Wait before trying again."
        else -> "The verification service returned an error (${e.status}). Try again later."
    }
    is java.net.SocketTimeoutException -> "The verification request timed out. Try a stable connection, then Retry."
    else -> "Could not complete verification. Tap Retry or return to setup; your progress is saved."
}
private fun verificationRequest(url: String, body: String? = null): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000; readTimeout = 20_000
        setRequestProperty("Accept", "application/json")
        if (body != null) { requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json") }
    }
    return try {
        body?.let { connection.outputStream.use { out -> out.write(it.toByteArray(Charsets.UTF_8)) } }
        val code = connection.responseCode
        if (code !in 200..299) throw VerificationHttpError(code)
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally { connection.disconnect() }
}
