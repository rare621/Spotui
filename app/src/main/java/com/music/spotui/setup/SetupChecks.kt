package com.music.spotui.setup

import android.content.Context
import com.metrolist.innertube.YouTube
import com.metrolist.spotify.Spotify
import com.music.spotui.data.api.SpotifyTokenProvider
import com.music.spotui.data.preferences.*
import kotlinx.coroutines.*

object SetupChecks {
    private suspend fun checked(c: Context, service: String, check: suspend () -> Boolean): Boolean {
        PlaybackDiagnostics.record(c, "Connection check started", service, song = "")
        return try {
            check().also { PlaybackDiagnostics.record(c, "Connection checked", "$service: ${if (it) "Verified" else "Not confirmed"}", song = "") }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            PlaybackDiagnostics.record(c, "Connection check failed", "$service: ${e.javaClass.simpleName}", song = "")
            throw e
        }
    }
    suspend fun spotify(c: Context): Boolean = checked(c, "Spotify") { withContext(Dispatchers.IO) {
        withTimeoutOrNull(25_000) {
            SpotifyTokenProvider.ensureToken(c, force = true) && Spotify.me().getOrNull()?.id?.isNotBlank() == true
        } == true
    }
    }
    suspend fun youtube(c: Context, candidate: String = getYoutubeCookie(c)): Boolean = checked(c, "YouTube Music") {
        if (candidate.isBlank()) return@checked false
        val previous = YouTube.cookie
        val previousVisitor = YouTube.visitorData
        val previousSync = YouTube.dataSyncId
        val previousBrowse = YouTube.useLoginForBrowse
        var valid = false
        var committed = false
        try {
            YouTube.cookie = candidate
            YouTube.dataSyncId = null
            YouTube.useLoginForBrowse = true
            valid = withTimeoutOrNull(25_000) {
                YouTube.visitorData = YouTube.visitorData().getOrNull()
                YouTube.validateLogin().getOrThrow()
            } == true
            currentCoroutineContext().ensureActive()
            if (valid) { setYoutubeLogin(c, candidate, YouTube.visitorData.orEmpty()); committed = true }
            valid
        } finally {
            if (!committed) {
                YouTube.cookie = previous; YouTube.visitorData = previousVisitor
                YouTube.dataSyncId = previousSync; YouTube.useLoginForBrowse = previousBrowse
            }
        }
    }
    suspend fun deezer(c: Context, candidate: String? = getDeezerArl(c)): Boolean = checked(c, "Deezer") { withContext(Dispatchers.IO) {
        if (candidate.isNullOrBlank()) return@withContext false
        withTimeoutOrNull(25_000) {
            com.music.spotui.deezer.DeezerSession.setArl(candidate)
            com.music.spotui.deezer.DeezerSession.validateAccount()
        } == true
    }
    }
    fun failure(error: Throwable?): String = when (error) {
        is java.net.UnknownHostException -> "Could not reach the service. Check Wi-Fi or mobile data."
        is java.net.SocketTimeoutException, is TimeoutCancellationException -> "The connection timed out. Try again on a stable connection."
        else -> "The connection could not be confirmed. Retry or reconnect your account."
    }
}
