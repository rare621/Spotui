package com.music.spotui.data.backup

import org.junit.Assert.*
import org.junit.Test

class BackupIdentityPolicyTest {
    @Test fun acceptsRegularAndPreviewInBothDirections() {
        val regular = "com.music.spotui.modified"
        val preview = "$regular.webpreview"
        assertTrue(BackupIdentityPolicy.compatible(regular, preview))
        assertTrue(BackupIdentityPolicy.compatible(preview, regular))
        assertTrue(BackupIdentityPolicy.compatible(regular, regular))
        val desktop = "$regular.desktoppreview"
        assertTrue(BackupIdentityPolicy.compatible(regular, desktop))
        assertTrue(BackupIdentityPolicy.compatible(preview, desktop))
        assertTrue(BackupIdentityPolicy.compatible(desktop, regular))
    }
    @Test fun rejectsOtherAppsAndLookalikePrefixes() {
        assertFalse(BackupIdentityPolicy.compatible("com.music.spotui", "com.music.spotui.modified"))
        assertFalse(BackupIdentityPolicy.compatible("com.music.spotui.modified.other", "com.music.spotui.modified.webpreview"))
        assertFalse(BackupIdentityPolicy.compatible("com.music.spotui.modified", "other"))
        assertFalse(BackupIdentityPolicy.compatible("", ""))
    }
    @org.junit.Test fun streamingPreviewSharesBackupFamily() {
        org.junit.Assert.assertTrue(BackupIdentityPolicy.compatible("com.music.spotui.modified", "com.music.spotui.modified.streampreview"))
        org.junit.Assert.assertTrue(BackupIdentityPolicy.compatible("com.music.spotui.modified.streampreview", "com.music.spotui.modified"))
    }
}
