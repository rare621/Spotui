package com.music.spotui.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamLookupFallbackTest {
    @Test fun successfulPrimaryNeverStartsBackup() = runTest {
        var called = false
        assertEquals("primary", StreamLookupFallback.resolve(100, 100, true, { "primary" }, { called = true; "backup" }))
        assertFalse(called)
    }
    @Test fun exhaustedOrTimedOutPrimaryGetsBackupWindow() = runTest {
        assertEquals("backup", StreamLookupFallback.resolve(100, 100, true, { delay(200); "late" }, { "backup" }))
        assertEquals("backup", StreamLookupFallback.resolve<String>(100, 100, true, { null }, { "backup" }))
    }
    @Test fun primaryNetworkExceptionAlsoUsesBackup() = runTest {
        assertEquals("backup", StreamLookupFallback.resolve<String>(100, 100, true,
            { throw java.io.IOException("connection lost") }, { "backup" }))
    }
    @Test fun disabledBackupDoesNotStart() = runTest {
        var called = false
        assertNull(StreamLookupFallback.resolve<String>(100, 100, false, { null }, { called = true; "backup" }))
        assertFalse(called)
    }
    @Test fun exhaustedBackupCannotHangLookup() = runTest {
        assertNull(StreamLookupFallback.resolve(100, 100, true, { null }, { delay(200); "late" }))
    }
    @Test fun skipCancellationStopsPrimaryAndDoesNotStartBackup() = runTest {
        var backup = false
        var stopped = false
        val job = launch {
            StreamLookupFallback.resolve(1000, 1000, true, {
                try { awaitCancellation() } finally { stopped = true }
            }, { backup = true; "backup" })
        }
        testScheduler.runCurrent(); job.cancelAndJoin()
        assertTrue(stopped); assertFalse(backup)
    }
    @Test fun skipCancellationStopsBackup() = runTest {
        var stopped = false
        val job = launch {
            StreamLookupFallback.resolve<String>(1000, 1000, true, { null }, {
                try { awaitCancellation() } finally { stopped = true }
            })
        }
        testScheduler.runCurrent(); job.cancelAndJoin(); assertTrue(stopped)
    }
    @Test fun forcedRecoveryNeverUsesPrimaryEvenWhenPrimaryWouldSucceed() = runTest {
        var primaryCalled = false
        assertEquals("backup", StreamLookupFallback.resolve(100, 100, true,
            { primaryCalled = true; "normal audio" }, { "backup" }, forceBackup = true))
        assertFalse(primaryCalled)
    }
    @Test fun forcedRecoveryFailureDoesNotSilentlyReturnRejectedPrimary() = runTest {
        var primaryCalled = false
        assertNull(StreamLookupFallback.resolve<String>(100, 100, true,
            { primaryCalled = true; "normal audio" }, { null }, forceBackup = true))
        assertFalse(primaryCalled)
    }
}
