package com.music.spotui.data.api

import android.content.Context
import android.util.Log
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyAuth
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ensures the [Spotify] singleton holds a valid web access token before any
 * metadata call. Mirrors how Meld authenticates: a logged-in `sp_dc` cookie is
 * exchanged for a short-lived bearer token via [SpotifyAuth.fetchAccessToken].
 *
 * The `sp_dc` cookie is read from [SpotifySession]; paste yours there (or via
 * the in-app settings hook) to enable real Spotify data. Tokens are cached
 * until ~1 minute before expiry and refreshed on demand.
 */
object SpotifyTokenProvider {
    private const val TAG = "SpotifyTokenProvider"
    private val mutex = Mutex()
    private var expiresAtMs: Long = 0L
    private var tokenCookie: String? = null
    // Each attempt costs 3 requests (one to the rate-limited GitHub API), so a failure
    // is remembered briefly instead of being retried by every call on every screen.
    private const val FAILURE_BACKOFF_MS = 30_000L
    private var lastFailureAtMs: Long = 0L

    /** Validate a candidate before committing it or clearing account-specific caches. */
    suspend fun connectVerified(context: Context, cookie: String): Boolean = mutex.withLock {
        val previous = Spotify.accessToken
        var committed = false
        try {
            val token = SpotifyAuth.fetchAccessToken(cookie).getOrThrow()
            Spotify.accessToken = token.accessToken
            if (Spotify.me().getOrThrow().id.isBlank()) return@withLock false
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            SpotifySession.setSpDc(context, cookie)
            tokenCookie = cookie
            expiresAtMs = token.accessTokenExpirationTimestampMs
            lastFailureAtMs = 0L
            committed = true
            true
        } finally { if (!committed) Spotify.accessToken = previous }
    }

    /** [force] skips the cached token and the failure backoff (fresh login). */
    suspend fun ensureToken(context: Context, force: Boolean = false): Boolean = mutex.withLock {
        val now = System.currentTimeMillis()
        val spDc = SpotifySession.spDc(context)
        if (!force && tokenCookie == spDc && Spotify.accessToken != null && now < expiresAtMs - 60_000L) {
            return@withLock true
        }
        if (!force && tokenCookie == spDc && now - lastFailureAtMs < FAILURE_BACKOFF_MS) return@withLock false
        if (spDc.isBlank()) {
            Log.w(TAG, "No sp_dc cookie set — Spotify data unavailable")
            return@withLock false
        }
        SpotifyAuth.fetchAccessToken(spDc).fold(
            onSuccess = { token ->
                Spotify.accessToken = token.accessToken
                tokenCookie = spDc
                expiresAtMs = token.accessTokenExpirationTimestampMs
                lastFailureAtMs = 0L
                Log.d(TAG, "Spotify token refreshed")
                true
            },
            onFailure = {
                Log.e(TAG, "Failed to fetch Spotify token", it)
                tokenCookie = spDc
                lastFailureAtMs = now
                false
            },
        )
    }
}
