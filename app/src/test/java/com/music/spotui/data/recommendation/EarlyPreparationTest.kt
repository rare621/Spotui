package com.music.spotui.data.recommendation

import com.music.spotui.audio.NextTrackPreparationPolicy
import com.music.spotui.audio.PreparationClock
import org.junit.Assert.*
import org.junit.Test

class EarlyPreparationTest {
    @Test fun beginsAtTwoAudibleSeconds() {
        assertFalse(NextTrackPreparationPolicy.shouldPrepareEarly(1999, true, false))
        assertTrue(NextTrackPreparationPolicy.shouldPrepareEarly(2000, true, false))
    }
    @Test fun pausedBufferingAndRepeatDoNotStartPreparation() {
        assertFalse(NextTrackPreparationPolicy.shouldPrepareEarly(5000, false, false))
        assertFalse(NextTrackPreparationPolicy.shouldPrepareEarly(5000, true, true))
    }
    @Test fun clockCountsActualPlayingTicks() {
        val clock = PreparationClock()
        assertEquals(0L, clock.tick("visit", true, 0))
        assertEquals(1000L, clock.tick("visit", true, 1000))
        assertEquals(2000L, clock.tick("visit", true, 2000))
    }
    @Test fun longPausedGapDoesNotCountAsPlaying() {
        val clock = PreparationClock()
        clock.tick("visit", true, 0)
        clock.tick("visit", true, 500)
        clock.tick("visit", false, 750)
        clock.tick("visit", false, 100000)
        assertEquals(500L, clock.tick("visit", true, 100250))
        assertEquals(750L, clock.tick("visit", true, 100500))
    }
    @Test fun skippedTrackGetsItsOwnTwoSecondClock() {
        val clock = PreparationClock()
        clock.tick("first", true, 0); clock.tick("first", true, 1000); clock.tick("first", true, 2000)
        assertEquals(0L, clock.tick("next", true, 2250))
    }
    @Test fun suspendedWatchdogCannotCountMinutesOfPlayback() {
        val clock = PreparationClock()
        clock.tick("visit", true, 0)
        assertEquals(1000L, clock.tick("visit", true, 100000))
    }
    @Test fun preparedStreamCannotBeUsedForDifferentQueueOrSession() {
        assertFalse(NextTrackPreparationPolicy.stillNext("A", "B", true))
        assertFalse(NextTrackPreparationPolicy.stillNext("A", "A", false))
    }
    @Test fun playerStartEventPlusWatchdogTicksReachPreparationAtTwoSeconds() {
        val clock=PreparationClock()
        clock.tick("song",true,100)
        for (time in 350L..1850L step 250L) assertFalse(NextTrackPreparationPolicy.shouldPrepareEarly(clock.tick("song",true,time),true,false))
        assertTrue(NextTrackPreparationPolicy.shouldPrepareEarly(clock.tick("song",true,2100),true,false))
    }
}
