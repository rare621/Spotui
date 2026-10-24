package com.music.spotui.audio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest

class BackupSearchPolicyTest {
    private val target = BackupSearchPolicy.Target("Creepin' (with The Weeknd & 21 Savage)", "Metro Boomin, The Weeknd, 21 Savage", 221000, true)
    private val id = "Abcdef123_-"
    private fun item(title: String = "Metro Boomin, The Weeknd, 21 Savage - Creepin' (Visualizer)", channel: String = "Metro Boomin", duration: Int = 222) =
        JSONObject().put("id", id).put("title", title).put("channel", channel).put("duration", duration)
    private fun response(vararg items: JSONObject) = JSONObject().put("entries", JSONArray(items.toList())).toString()
    @Test fun collaborationCreditsDoNotHideTheOfficialRecording() {
        assertEquals(listOf(id), BackupSearchPolicy.candidates(response(item()), target))
        assertEquals("Creepin' Metro Boomin", BackupSearchPolicy.query(target))
    }
    @Test fun matchingUploaderHandleCanSupplyArtistEvidence() {
        assertEquals(listOf(id), BackupSearchPolicy.candidates(response(item("Creepin' (Official Audio)", "MetroBoominVEVO")), target))
    }
    @Test fun wrongArtistSameTitleDoesNotMatch() {
        assertTrue(BackupSearchPolicy.candidates(response(item("Creepin'", "Unrelated Artist")), target).isEmpty())
    }
    @Test fun versionsAndWrongDurationsAreRejected() {
        for (suffix in listOf("remix", "cover", "karaoke", "slowed", "live", "instrumental")) {
            assertTrue(BackupSearchPolicy.candidates(response(item("Metro Boomin - Creepin' ($suffix)")), target).isEmpty())
        }
        assertTrue(BackupSearchPolicy.candidates(response(item(duration=270)), target).isEmpty())
        assertTrue(BackupSearchPolicy.candidates(response(item("Metro Boomin - Creepin' Again")), target).isEmpty())
    }
    @Test fun obviousWrongExplicitEditionIsRejected() {
        assertTrue(BackupSearchPolicy.candidates(response(item("Metro Boomin - Creepin' (Clean)")), target).isEmpty())
        assertTrue(BackupSearchPolicy.candidates(response(item("Metro Boomin - Creepin' (Explicit)")), target.copy(explicit=false)).isEmpty())
    }
    @Test fun upcomingPrivateAndInvalidVideosAreRejected() {
        for (field in listOf("live_status" to "is_upcoming", "availability" to "private", "id" to "invalid")) {
            assertTrue(BackupSearchPolicy.candidates(response(item().put(field.first,field.second)), target).isEmpty())
        }
    }
    @Test fun missingDurationRequiresMatchingUploader() {
        assertTrue(BackupSearchPolicy.candidates(response(item(channel="random channel",duration=0)), target).isEmpty())
        assertEquals(listOf(id), BackupSearchPolicy.candidates(response(item(duration=0)), target))
    }
    @Test fun malformedSearchOrMissingMetadataIsNotGuessed() {
        assertTrue(BackupSearchPolicy.candidates("not JSON",target).isEmpty())
        assertTrue(BackupSearchPolicy.candidates(response(item()),target.copy(artist="")).isEmpty())
    }
    @Test fun emptyPrimaryResultsStillRunIndependentSearchAndResolve() = runTest {
        var searched=false;var resolved=false
        val found=BackupCandidatePlanner.lookup(emptyList(),{searched=true;listOf(id)},{resolved=true;"audio"})
        assertEquals("audio",found);assertTrue(searched);assertTrue(resolved)
    }
    @Test fun failedKnownIdCanUseAnotherSearchCandidateWithoutRepeatingIt() = runTest {
        val seen=mutableListOf<String>()
        val found=BackupCandidatePlanner.lookup(listOf("known"),{listOf("known","independent")},{seen+=it;if(it=="independent")"audio"else null})
        assertEquals("audio",found);assertEquals(listOf("known","independent"),seen)
    }
    @Test fun successfulKnownIdDoesNotNeedSearch() = runTest {
        var searched=false
        assertEquals("audio",BackupCandidatePlanner.lookup(listOf(id),{searched=true;emptyList()},{"audio"}))
        assertFalse(searched)
    }
    @Test fun titleCleanupKeepsAlternateRecordingNames() {
        assertEquals("Song (Live)",MusicTitlePolicy.clean("Song (Live) [Official Audio]"))
        assertEquals("Song (Remix)",MusicTitlePolicy.clean("Song (Remix) (with Artist)"))
        assertEquals("With You",MusicTitlePolicy.clean("With You"))
    }
    @Test fun rejectedReturnedStreamsGetBackupUntilPlaybackRecovers() {
        RejectedStreamRecovery.failed("a",1)
        assertTrue(RejectedStreamRecovery.preferBackup("a",2));assertFalse(RejectedStreamRecovery.preferBackup("b",2))
        RejectedStreamRecovery.recovered("a");assertFalse(RejectedStreamRecovery.preferBackup("a",2))
        RejectedStreamRecovery.failed("a",1);assertFalse(RejectedStreamRecovery.preferBackup("a",301000000001))
    }
    @Test fun diagnosticCategoriesNeverRepeatSecretStderr() {
        assertEquals("HTTP 403",BackupFailurePolicy.describe("https://private.example?cookie=secret HTTP 403"))
        assertEquals("YouTube sign-in required or rejected",BackupFailurePolicy.describe("Sign in to confirm you're not a bot"))
        assertEquals("No direct audio format",BackupFailurePolicy.describe("Requested format is not available"))
        assertEquals("Extractor or runtime failure",BackupFailurePolicy.describe("private token = secret"))
    }
}
