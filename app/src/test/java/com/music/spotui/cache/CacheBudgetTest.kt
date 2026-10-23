package com.music.spotui.cache

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheBudgetTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file(dir: File, name: String, bytes: Int, at: Long): File = File(dir, name).apply {
        writeBytes(ByteArray(bytes) { 7 }); check(setLastModified(at))
    }

    @Test fun oldestReusableFilesGoFirst() {
        val dir = temp.newFolder("slsk")
        val old = file(dir, "old.flac", 6, 1000)
        val recent = file(dir, "recent.flac", 6, 2000)
        assertEquals(6L, CacheBudget.trimLossless(dir, 8, emptySet(), 3000))
        assertFalse(old.exists()); assertTrue(recent.exists())
    }

    @Test fun playingAndPreparedFilesAreProtectedEvenAboveBudget() {
        val dir = temp.newFolder("slsk")
        val playing = file(dir, "playing.flac", 8, 1000)
        val next = file(dir, "next.flac", 8, 1001)
        val old = file(dir, "old.flac", 8, 1002)
        assertEquals(16L, CacheBudget.trimLossless(dir, 4, setOf(playing.name, next.name), 5000))
        assertTrue(playing.exists()); assertTrue(next.exists()); assertFalse(old.exists())
    }

    @Test fun recentlyOpenedFilesHaveARaceProtectionWindow() {
        val dir = temp.newFolder("slsk")
        val recent = file(dir, "recent.flac", 8, 9000)
        assertEquals(8L, CacheBudget.trimLossless(dir, 0, emptySet(), 5000))
        assertTrue(recent.exists())
    }

    @Test fun historyStatsHomeAndDownloadsRemainByteForByteUnchanged() {
        val app = temp.newFolder("app")
        val cache = File(app, "cache/slsk").apply { mkdirs() }
        val stores = listOf("shared_prefs/all_time.xml", "shared_prefs/monthly.xml", "shared_prefs/history_30_days.xml",
            "shared_prefs/home_personalization.xml", "shared_prefs/radio_progress.xml", "files/downloads/song.flac")
        val originals = stores.associateWith { name -> File(app, name).apply { parentFile.mkdirs(); writeText("Saved $name") }.readBytes() }
        val unknown = File(cache, "preferences.json").apply { writeText("leave this alone") }
        val nested = File(cache, "downloads/keep.flac").apply { parentFile.mkdirs(); writeText("offline") }
        file(cache, "old.flac", 8, 1000)
        assertEquals(0L, CacheBudget.trimLossless(cache, 0, emptySet(), Long.MAX_VALUE))
        originals.forEach { (name, content) -> assertArrayEquals(content, File(app, name).readBytes()) }
        assertEquals("leave this alone", unknown.readText()); assertEquals("offline", nested.readText())
    }

    @Test fun aCacheSymlinkCannotLeadCleanupIntoSavedData() {
        val cache = temp.newFolder("slsk")
        val history = temp.newFile("history.flac").apply { writeText("all-time history") }
        val link = File(cache, "old.flac")
        Files.createSymbolicLink(link.toPath(), history.toPath())
        CacheBudget.trimLossless(cache, 0, emptySet(), Long.MAX_VALUE)
        assertEquals("all-time history", history.readText()); assertTrue(link.exists())
    }

    @Test fun installedUpdateFilesAreRemovedButPendingNewerUpdatesStay() {
        val dir = temp.newFolder("updates")
        val old = file(dir, "USpot-85.apk", 2, 1000)
        val installed = file(dir, "USpot-86.apk", 2, 1000)
        val future = file(dir, "USpot-87.apk", 2, 1000)
        val partial = file(dir, "update.part", 2, 99_000_000)
        CacheBudget.pruneInstalledUpdates(dir, 86, 100_000_000)
        assertFalse(old.exists()); assertFalse(installed.exists()); assertTrue(future.exists()); assertTrue(partial.exists())
    }

    @Test fun interruptedOldUpdateCleanupDoesNotTouchBackupFiles() {
        val dir = temp.newFolder("updates")
        val stale = file(dir, "update.part", 2, 1000)
        val backup = file(dir, "backup.zip", 2, 1000)
        CacheBudget.pruneInstalledUpdates(dir, 86, 100_000_000)
        assertFalse(stale.exists()); assertTrue(backup.exists())
    }
}
