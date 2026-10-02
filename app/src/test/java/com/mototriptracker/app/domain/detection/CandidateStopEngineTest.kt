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

    private fun location(elapsedNanos: Long, speedMps: Float? = null, latitude: Double = 10.0) = LocationSample(
        wallTimeEpochMs = elapsedNanos / 1_000_000,
        elapsedRealtimeNanos = elapsedNanos,
        receivedAtElapsedRealtimeNanos = elapsedNanos,
        latitude = latitude,
        longitude = -84.0,
        horizontalAccuracyM = 5.0f,
        requestProfileId = "tracking-manual-v0",
        speedMps = speedMps
    )

    private fun exit(atSeconds: Int = 0) =
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, atSeconds * 1_000_000_000L)))

    private fun walking(atSeconds: Int) =
        engine.accept(DetectionEvent.Activity(activity(ActivityType.WALKING, TransitionType.ENTER, atSeconds * 1_000_000_000L)))

    private fun fixAt(seconds: Int, speedMps: Float?, latitude: Double = 10.0) =
        engine.accept(DetectionEvent.Location(location(seconds * 1_000_000_000L, speedMps, latitude)))

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

        assertTrue(decision is CandidateStopDecision.Abandoned)
        assertEquals(CandidateStopEngine.REASON_IN_VEHICLE_ENTER, (decision as CandidateStopDecision.Abandoned).reasonCode)
        assertFalse(engine.isCandidateOpen)
    }

    // --- DET-008: Activity Recognition's WALKING no longer ends a trip by itself ---

    @Test
    fun walkingAloneDoesNotEndTheTrip() {
        // The bug this task exists for: 13 of 13 automatic Finishes in one real field day came the instant
        // Activity Recognition said WALKING - at traffic lights and slow turns, not at the end of rides.
        exit(0)

        val decision = walking(atSeconds = 2)

        assertEquals(CandidateStopDecision.NoChange, decision)
        assertTrue("the candidate stays open, waiting for the grace period", engine.isCandidateOpen)
    }

    @Test
    fun onFootAloneDoesNotEndTheTripEither() {
        exit(0)

        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.ON_FOOT, TransitionType.ENTER, 1_000_000_000L)))

        assertEquals(CandidateStopDecision.NoChange, decision)
        assertTrue(engine.isCandidateOpen)
    }

    @Test
    fun walkingDoesNotShortenTheGracePeriodItConfirmsAtTheSameMomentWithOrWithoutIt() {
        exit(0)
        walking(atSeconds = 2)
        assertEquals("still inside the 30 s grace", CandidateStopDecision.NoChange, fixAt(29, speedMps = 0f))

        val decision = fixAt(31, speedMps = 0f)

        assertTrue(decision is CandidateStopDecision.Confirmed)
        decision as CandidateStopDecision.Confirmed
        assertEquals(CandidateStopEngine.REASON_GRACE_PERIOD_ELAPSED, decision.reasonCode)
        assertEquals("opened at the vehicle's own exit, so the caller can trim the tail from there", 0L, decision.candidateOpenedAtElapsedRealtimeNanos)
        assertTrue("walking was seen and is part of the evidence", decision.evidence.walkingSeen)
    }

    @Test
    fun aRealStopStaysPutAndConfirmsAfterTheGracePeriodWithItsEvidence() {
        exit(0)
        fixAt(5, speedMps = 0.0f)
        fixAt(15, speedMps = 0.3f)

        val decision = fixAt(31, speedMps = 0.0f)

        decision as CandidateStopDecision.Confirmed
        assertEquals(3, decision.evidence.fixCount)
        assertEquals(0.3f, decision.evidence.maxSpeedMps!!, 0.001f)
        assertEquals(5_000L, decision.evidence.firstFixDelayMs)
        assertEquals(0.0, decision.evidence.displacementMeters!!, 0.5)
        assertFalse(decision.evidence.walkingSeen)
    }

    @Test
    fun theLightTurningGreenCancelsTheStopEvenIfActivityRecognitionNeverSaysInVehicleAgain() {
        // §7 requirement 4: "Resumed movement during candidate-stop MUST return to TRACKING without creating a split."
        exit(0)
        walking(atSeconds = 1)
        fixAt(10, speedMps = 0.0f)
        assertEquals("one fast fix is not yet movement", CandidateStopDecision.NoChange, fixAt(20, speedMps = 5.0f))

        val decision = fixAt(22, speedMps = 6.5f)

        assertTrue(decision is CandidateStopDecision.Abandoned)
        decision as CandidateStopDecision.Abandoned
        assertEquals(CandidateStopEngine.REASON_MOVEMENT_RESUMED, decision.reasonCode)
        assertEquals(6.5f, decision.evidence.maxSpeedMps!!, 0.001f)
        assertTrue(decision.evidence.walkingSeen)
        assertFalse(engine.isCandidateOpen)
    }

    @Test
    fun aSingleGpsSpeedSpikeDoesNotCancelARealStop() {
        // DP-001.
        exit(0)
        fixAt(10, speedMps = 0.0f)
        fixAt(12, speedMps = 9.0f) // a glitch
        fixAt(14, speedMps = 0.0f) // back to nothing - the run is broken

        assertEquals(CandidateStopDecision.NoChange, fixAt(16, speedMps = 9.0f))
        assertTrue(engine.isCandidateOpen)
        assertTrue("and the stop still confirms on its own schedule", fixAt(31, speedMps = 0f) is CandidateStopDecision.Confirmed)
    }

    @Test
    fun aWalkingPaceNeverCancelsTheStop() {
        exit(0)
        walking(atSeconds = 1)
        for (second in 4..28 step 4) {
            assertEquals(CandidateStopDecision.NoChange, fixAt(second, speedMps = 1.6f))
        }
        assertTrue("a rider walking away from the bike is a stop that confirms", fixAt(31, speedMps = 1.6f) is CandidateStopDecision.Confirmed)
    }

    @Test
    fun resumedMovementOnlyCountsWhileACandidateIsOpen() {
        // Riding normally with no stop candidate: speed samples must not disturb anything.
        assertEquals(CandidateStopDecision.NoChange, fixAt(1, speedMps = 12f))
        assertEquals(CandidateStopDecision.NoChange, fixAt(3, speedMps = 12f))
        assertFalse(engine.isCandidateOpen)
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

    @Test
    fun repeatedTrafficLightChurnNeverConfirmsAndDoesNotPreventARealStopAfterward() {
        // DET-004/F0.3 §18 SCN-006 ("heavy congestion, repeated 0-10 km/h
        // motion") and SCN-026 ("multiple stop/start blocks in city -> one
        // logical Trip"): dense traffic can make Activity Recognition flap
        // IN_VEHICLE EXIT/ENTER many times in quick succession. Nothing new
        // is needed for this - each EXIT/ENTER pair is independent per the
        // engine's own stateless-between-cycles design (state fully resets
        // to Tracking on Abandoned) - this test is that claim made explicit
        // and regression-proof rather than merely inferred from the single-
        // cycle tests above.
        var nowNanos = 0L
        repeat(10) { cycle ->
            val exitAt = nowNanos
            val opened = engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, exitAt)))
            assertEquals("cycle $cycle: exit should open a candidate", CandidateStopDecision.CandidateOpened, opened)

            nowNanos += 2_000_000_000L // 2s later - well under the 30s grace period this test's profile uses
            val abandoned = engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, nowNanos)))
            assertTrue("cycle $cycle: re-entering traffic should abandon the candidate", abandoned is CandidateStopDecision.Abandoned)
            assertFalse(engine.isCandidateOpen)

            nowNanos += 1_000_000_000L
        }

        // The ride actually ends now - churn beforehand must not have left
        // any stale state behind that would block or alter this.
        val realExitAt = nowNanos
        val realExitDecision = engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, realExitAt)))
        assertEquals(CandidateStopDecision.CandidateOpened, realExitDecision)

        val confirmDecision = engine.accept(DetectionEvent.Location(location(realExitAt + 31_000_000_000L)))
        assertTrue(confirmDecision is CandidateStopDecision.Confirmed)
        assertEquals(realExitAt, (confirmDecision as CandidateStopDecision.Confirmed).candidateOpenedAtElapsedRealtimeNanos)
    }
}
