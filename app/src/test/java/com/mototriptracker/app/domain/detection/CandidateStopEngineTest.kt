package com.mototriptracker.app.domain.detection

import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.core.model.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CandidateStopEngineTest {

    // Short, deterministic profile so tests don't need unrealistically long synthetic sequences.
    private val profile = CandidateStopProfile(minConfirmationDurationMs = 30_000L)
    private lateinit var engine: CandidateStopEngine

    @Before
    fun setUp() {
        engine = CandidateStopEngine(profile)
    }

    private fun activity(type: ActivityType, transition: TransitionType, elapsedNanos: Long) = ActivityTransitionSample(
        activityType = type,
        transitionType = transition,
        elapsedRealtimeNanos = elapsedNanos,
        wallTimeEpochMs = elapsedNanos / 1_000_000,
        source = "test"
    )

    private fun location(elapsedNanos: Long) = LocationSample(
        wallTimeEpochMs = elapsedNanos / 1_000_000,
        elapsedRealtimeNanos = elapsedNanos,
        receivedAtElapsedRealtimeNanos = elapsedNanos,
        latitude = 10.0,
        longitude = -84.0,
        horizontalAccuracyM = 5.0f,
        requestProfileId = "tracking-manual-v0"
    )

    @Test
    fun aSingleInVehicleExitAloneDoesNotEndTheTripImmediately() {
        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 0L)))

        assertEquals(CandidateStopDecision.CandidateOpened, decision)
        assertTrue(engine.isCandidateOpen)
    }

    @Test
    fun remainsTrackingWhenNoExitSignalEverArrives() {
        val decision = engine.accept(DetectionEvent.Location(location(0L)))

        assertEquals(CandidateStopDecision.NoChange, decision)
        assertFalse(engine.isCandidateOpen)
    }

    @Test
    fun aShortStopUnderTheGracePeriodDoesNotConfirmEndOfTrip() {
        // The traffic-light case this task's own acceptance criterion names directly.
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 0L)))

        val decision = engine.accept(DetectionEvent.Location(location(20_000_000_000L))) // 20s, under the 30s grace period

        assertEquals(CandidateStopDecision.NoChange, decision)
        assertTrue("still an open candidate - neither confirmed nor abandoned", engine.isCandidateOpen)
    }

    @Test
    fun confirmsAfterTheGracePeriodElapsesViaOrdinaryLocationSamples() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 0L)))

        val decision = engine.accept(DetectionEvent.Location(location(31_000_000_000L)))

        assertTrue(decision is CandidateStopDecision.Confirmed)
        val confirmed = decision as CandidateStopDecision.Confirmed
        assertEquals(0L, confirmed.candidateOpenedAtElapsedRealtimeNanos)
        assertEquals(31_000_000_000L, confirmed.confirmedAtElapsedRealtimeNanos)
        assertEquals(CandidateStopEngine.REASON_GRACE_PERIOD_ELAPSED, confirmed.reasonCode)
        assertFalse("engine resets after confirming - the caller owns what happens next", engine.isCandidateOpen)
    }

    @Test
    fun confirmsAfterTheGracePeriodElapsesViaATimeTickWhenNoLocationArrives() {
        // GPS loss during the stop (tunnel, parking garage) must not prevent confirmation forever.
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 0L)))

        val decision = engine.accept(DetectionEvent.TimeTick(nowElapsedRealtimeNanos = 31_000_000_000L))

        assertTrue(decision is CandidateStopDecision.Confirmed)
        assertEquals(CandidateStopEngine.REASON_GRACE_PERIOD_ELAPSED, (decision as CandidateStopDecision.Confirmed).reasonCode)
    }

    @Test
    fun abandonsWhenInVehicleEntersAgainBeforeTheGracePeriodElapses() {
        // Traffic resumes at the light.
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 0L)))

        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 5_000_000_000L)))

        assertEquals(CandidateStopDecision.Abandoned, decision)
        assertFalse(engine.isCandidateOpen)
    }

    @Test
    fun walkingAwayConfirmsImmediatelyWithoutWaitingForTheGracePeriod() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 0L)))

        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.WALKING, TransitionType.ENTER, 2_000_000_000L)))

        assertTrue(decision is CandidateStopDecision.Confirmed)
        val confirmed = decision as CandidateStopDecision.Confirmed
        assertEquals(2_000_000_000L, confirmed.confirmedAtElapsedRealtimeNanos)
        assertEquals(CandidateStopEngine.REASON_WALKING_AWAY, confirmed.reasonCode)
    }

    @Test
    fun onFootEnteringAlsoConfirmsImmediately() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 0L)))

        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.ON_FOOT, TransitionType.ENTER, 1_000_000_000L)))

        assertTrue(decision is CandidateStopDecision.Confirmed)
        assertEquals(CandidateStopEngine.REASON_WALKING_AWAY, (decision as CandidateStopDecision.Confirmed).reasonCode)
    }

    @Test
    fun walkingEnteringWithNoOpenCandidateChangesNothing() {
        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.WALKING, TransitionType.ENTER, 0L)))

        assertEquals(CandidateStopDecision.NoChange, decision)
        assertFalse(engine.isCandidateOpen)
    }

    @Test
    fun stillActivityTransitionsAreIgnoredWhileCandidateOpen() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 0L)))

        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.STILL, TransitionType.ENTER, 1_000_000_000L)))

        assertEquals(CandidateStopDecision.NoChange, decision)
        assertTrue(engine.isCandidateOpen)
    }

    @Test
    fun aSecondExitWhileAlreadyACandidateChangesNothing() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 0L)))

        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 1_000_000_000L)))

        assertEquals(CandidateStopDecision.NoChange, decision)
        assertTrue(engine.isCandidateOpen)
    }

    @Test
    fun anEnterWithNoOpenCandidateChangesNothing() {
        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0L)))

        assertEquals(CandidateStopDecision.NoChange, decision)
        assertFalse(engine.isCandidateOpen)
    }

    @Test
    fun aNewCandidateCanOpenAfterAPriorOneWasAbandoned() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 0L)))
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 1_000_000_000L)))

        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 2_000_000_000L)))

        assertEquals(CandidateStopDecision.CandidateOpened, decision)
        assertTrue(engine.isCandidateOpen)
    }

    @Test
    fun aNewCandidateCanOpenAfterAPriorOneWasConfirmed() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 0L)))
        engine.accept(DetectionEvent.Location(location(31_000_000_000L)))
        check(!engine.isCandidateOpen)

        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 40_000_000_000L)))

        assertEquals(CandidateStopDecision.CandidateOpened, decision)
    }
}
