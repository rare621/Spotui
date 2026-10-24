package com.music.spotui.setup

import org.junit.Assert.*
import org.junit.Test

class GuidedSetupTest {
    @Test fun spotifyAlwaysShowsSourceChoice() {
        // The destination must not depend on saved Google/Deezer cookies.
        assertEquals(1, SetupWizardPolicy.afterSpotify())
    }
    @Test fun savedOrNewAudioLoginStillNeedsExplicitConfirmation() {
        assertEquals(1, SetupWizardPolicy.afterSourceLogin())
    }
    @Test fun backVisitsEarlierStepsWithoutRequiringCredentialDeletion() {
        assertEquals(2, SetupWizardPolicy.back(3))
        assertEquals(1, SetupWizardPolicy.back(2))
        assertEquals(0, SetupWizardPolicy.back(1))
        assertEquals(0, SetupWizardPolicy.back(0))
        assertEquals(1, SetupWizardPolicy.resumePage(1, true, true, true))
        assertEquals(0, SetupWizardPolicy.resumePage(0, true, true, true))
    }
    @Test fun cannotResumePastMissingConnection() {
        assertEquals(0, SetupWizardPolicy.resumePage(3, false, true, true))
        assertEquals(1, SetupWizardPolicy.resumePage(3, true, false, true))
        assertEquals(2, SetupWizardPolicy.resumePage(3, true, true, false))
        assertEquals(3, SetupWizardPolicy.resumePage(3, true, true, true))
    }
    @Test fun allThreeConnectionsFinishWithoutAnAudioTest() {
        assertEquals(3, SetupWizardPolicy.afterLossless())
        for (bits in 0..7) {
            assertEquals(bits == 7, SetupWizardPolicy.canFinish(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0))
        }
    }
}
