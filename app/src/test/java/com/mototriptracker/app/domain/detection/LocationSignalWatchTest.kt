package com.mototriptracker.app.domain.detection

import com.mototriptracker.app.domain.detection.LocationSignalWatch.Transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** REC-005 / F0.10 §13: a gap is silence measured on the monotonic clock, never a verdict about the trip. */
class LocationSignalWatchTest {

    private fun seconds(s: Long) = s * 1_000_000_000L

    @Test
    fun steadyFixesNeverOpenAGap() {
        val watch = LocationSignalWatch()

        assertTrue(watch.onSignal(seconds(0)).isEmpty())
        assertTrue(watch.onSignal(seconds(2)).isEmpty())
        assertNull(watch.onTick(seconds(20)))
        assertTrue(watch.onSignal(seconds(22)).isEmpty())
        assertFalse(watch.isInGap)
    }

    @Test
    fun silenceReachingTheThresholdOpensAGapOnTheTickAndTheNextFixClosesIt() {
        val watch = LocationSignalWatch(gapThresholdMs = 30_000L)
        watch.onSignal(seconds(10))

        assertNull("29s of silence is still ordinary jitter", watch.onTick(seconds(39)))
        val started = watch.onTick(seconds(40)) as Transition.GapStarted
        assertEquals(seconds(10), started.lastSignalAtElapsedRealtimeNanos)
        assertEquals(30_000L, started.silenceMs)
        assertTrue(watch.isInGap)

        assertNull("an open gap is not reported twice", watch.onTick(seconds(55)))

        val ended = watch.onSignal(seconds(70)).single() as Transition.GapEnded
        assertEquals("the gap runs from the last fix before the silence to the first after it", 60_000L, ended.durationMs)
        assertEquals(seconds(10), ended.lastSignalAtElapsedRealtimeNanos)
        assertFalse(watch.isInGap)
    }

    /** The ticker does not run in deep sleep: the gap must still be reported, whole, when the next fix shows up. */
    @Test
    fun aGapNobodyTickedDuringIsReportedRetroactivelyAsStartedThenEnded() {
        val watch = LocationSignalWatch()
        watch.onSignal(seconds(0))

        val transitions = watch.onSignal(seconds(300))

        assertEquals(2, transitions.size)
        val started = transitions[0] as Transition.GapStarted
        val ended = transitions[1] as Transition.GapEnded
        assertEquals(seconds(0), started.lastSignalAtElapsedRealtimeNanos)
        assertEquals(300_000L, started.silenceMs)
        assertEquals(300_000L, ended.durationMs)
        assertFalse(watch.isInGap)
    }

    @Test
    fun aFixJustUnderTheThresholdIsNotAGap() {
        val watch = LocationSignalWatch(gapThresholdMs = 30_000L)
        watch.onSignal(seconds(0))

        assertTrue(watch.onSignal(seconds(29)).isEmpty())
    }

    @Test
    fun beforeTheFirstFixThereIsNothingToHaveLostSoTheWatchStaysQuiet() {
        val watch = LocationSignalWatch()

        assertNull("searching for the first fix is not a gap", watch.onTick(seconds(600)))
        assertTrue("the first fix has no earlier evidence to be a gap from", watch.onSignal(seconds(601)).isEmpty())
        assertFalse(watch.isInGap)
    }

    @Test
    fun seedingWithALastPersistedFixLetsARestartedRecordingReportTheGapItSlept() {
        // A sticky restart: the last stored fix was at t=100s, the first new one arrives at t=400s.
        val watch = LocationSignalWatch(initialSignalAtElapsedRealtimeNanos = seconds(100))

        val transitions = watch.onSignal(seconds(400))

        assertEquals(listOf(300_000L, 300_000L), listOf(
            (transitions[0] as Transition.GapStarted).silenceMs,
            (transitions[1] as Transition.GapEnded).durationMs
        ))
    }

    @Test
    fun aSecondGapAfterRecoveryIsReportedAgain() {
        val watch = LocationSignalWatch()
        watch.onSignal(seconds(0))
        watch.onTick(seconds(31))
        watch.onSignal(seconds(40))
        watch.onSignal(seconds(42))

        assertTrue(watch.onTick(seconds(72)) is Transition.GapStarted)
        assertTrue(watch.onSignal(seconds(80)).single() is Transition.GapEnded)
    }

    @Test
    fun aFixHeardWhileSuspendedNeverOpensAGapNoMatterHowLongTheSilenceWas() {
        val watch = LocationSignalWatch()
        watch.onSignal(seconds(0))

        assertNull(watch.onSuspended(seconds(600)))
        assertFalse(watch.isInGap)
        // Resuming right after: the reference is fresh, so nothing is judged against the pause.
        assertTrue(watch.onSignal(seconds(605)).isEmpty())
    }

    @Test
    fun pausingClosesAGapThatWasStillOpenAndSaysItWasThePauseThatClosedIt() {
        val watch = LocationSignalWatch()
        watch.onSignal(seconds(0))
        watch.onTick(seconds(40))

        val ended = watch.onSuspended(seconds(70)) as Transition.GapEnded

        assertTrue(ended.closedBySuspension)
        assertEquals(70_000L, ended.durationMs)
        assertFalse(watch.isInGap)
    }

    @Test
    fun aPausedRecordingThatNeverHeardAFixIsStillJustSearching() {
        val watch = LocationSignalWatch()

        assertNull(watch.onSuspended(seconds(300)))
        assertNull("no first fix yet, so nothing to have lost after resuming either", watch.onTick(seconds(900)))
    }
}
