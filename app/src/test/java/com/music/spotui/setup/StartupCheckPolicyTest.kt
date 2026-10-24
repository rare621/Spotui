package com.music.spotui.setup

import org.junit.Assert.*
import org.junit.Test

class StartupCheckPolicyTest {
    @Test fun launchAndUpdateCheckImmediately() {
        assertTrue(StartupCheckPolicy.shouldCheck(100, 0, 92, 92))
        assertTrue(StartupCheckPolicy.shouldCheck(100, 99, 92, 91))
    }
    @Test fun rapidForegroundEventsAreCoalescedButLaterOpenChecksAgain() {
        assertFalse(StartupCheckPolicy.shouldCheck(1000, 500, 92, 92))
        assertTrue(StartupCheckPolicy.shouldCheck(60500, 500, 92, 92))
        assertTrue(StartupCheckPolicy.shouldCheck(10, 500, 92, 92))
    }
    @Test fun offlineOrUnconfirmedConnectionsDoNotRequireSignIn() {
        assertFalse(StartupCheckPolicy.needsSignIn(StartupCheckPolicy.connection(true, false, false)))
        assertFalse(StartupCheckPolicy.needsSignIn(StartupCheckPolicy.connection(true, true, false)))
        assertFalse(StartupCheckPolicy.needsSignIn(StartupCheckPolicy.connection(true, true, true)))
    }
    @Test fun missingCredentialsNeedRepairEvenOffline() {
        assertTrue(StartupCheckPolicy.needsSignIn(StartupCheckPolicy.connection(false, false, false)))
        assertTrue(StartupCheckPolicy.needsSignIn(StartupCheckPolicy.connection(false, true, true)))
    }
}
