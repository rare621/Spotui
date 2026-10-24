package com.music.spotui.audio

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

/** Reserve a separate bounded window for the backup. Parent cancellation always propagates. */
internal object StreamLookupFallback {
    suspend fun <T> resolve(primaryBudgetMs: Long, backupBudgetMs: Long, backupEnabled: Boolean,
        primary: suspend () -> T?, backup: suspend () -> T?, forceBackup: Boolean = false): T? {
        val first = try { if (forceBackup && backupEnabled) null else withTimeoutOrNull(primaryBudgetMs) { primary() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        first?.let { return it }
        if (!backupEnabled) return null
        return withTimeoutOrNull(backupBudgetMs) { backup() }
    }
}
