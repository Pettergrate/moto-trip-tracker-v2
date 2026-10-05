package com.mototriptracker.app.domain.detection

import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.core.model.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CandidateStartEngineTest {

    // Short, deterministic profile so tests don't need unrealistically long synthetic sequences.
    private val profile = CandidateStartProfile(
        minConfirmationDurationMs = 10_000L,
        minDisplacementMeters = 40.0,
        maxCandidateWindowMs = 60_000L
    )
    private lateinit var engine: CandidateStartEngine

    @Before
    fun setUp() {
        engine = CandidateStartEngine(profile)
    }

    private fun activity(type: ActivityType, transition: TransitionType, elapsedNanos: Long) = ActivityTransitionSample(
        activityType = type,
        transitionType = transition,
        elapsedRealtimeNanos = elapsedNanos,
        wallTimeEpochMs = elapsedNanos / 1_000_000,
        source = "test"
    )

    private fun location(elapsedNanos: Long, latitude: Double, longitude: Double = -84.0, speedMps: Float? = null) = LocationSample(
        wallTimeEpochMs = elapsedNanos / 1_000_000,
        elapsedRealtimeNanos = elapsedNanos,
        receivedAtElapsedRealtimeNanos = elapsedNanos,
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyM = 5.0f,
        requestProfileId = "candidate-start-burst",
        speedMps = speedMps
    )

    private fun open() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0L)))
    }

    private fun fixAt(seconds: Int, speedMps: Float?, latitude: Double = 10.0) =
        engine.accept(DetectionEvent.Location(location(seconds * 1_000_000_000L, latitude, speedMps = speedMps)))

    /** ~1m of latitude displacement per 0.00001 degree near the equator-ish latitudes used here - close enough for test fixtures, exact math is GeoMathTest's job. */
    private fun latitudeOffsetMeters(baseLatitude: Double, meters: Double): Double = baseLatitude + meters / 111_195.0

    @Test
    fun aSingleInVehicleEnterEventAloneDoesNotConfirmATrip() {
        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0L)))

        assertEquals(CandidateStartDecision.CandidateOpened, decision)
        assertTrue(engine.isCandidateOpen)
    }

    @Test
    fun remainsIdleWhenOnlyLocationSamplesArriveWithNoActivityTrigger() {
        val decision = engine.accept(DetectionEvent.Location(location(0L, 10.0)))

        assertEquals(CandidateStartDecision.NoChange, decision)
        assertFalse(engine.isCandidateOpen)
    }

    @Test
    fun confirmsWhenBothDurationAndDisplacementMeetTheProfile() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0L)))
        engine.accept(DetectionEvent.Location(location(0L, 10.0))) // anchor
        val elapsedNanos = 11_000_000_000L // 11s > 10s minimum
        val displaced = latitudeOffsetMeters(10.0, 50.0) // 50m > 40m minimum

        val decision = engine.accept(DetectionEvent.Location(location(elapsedNanos, displaced)))

        assertTrue(decision is CandidateStartDecision.Confirmed)
        assertEquals(0L, (decision as CandidateStartDecision.Confirmed).candidateOpenedAtElapsedRealtimeNanos)
        assertEquals(elapsedNanos, decision.confirmedAtElapsedRealtimeNanos)
        assertFalse("engine resets after confirming - the caller owns what happens next", engine.isCandidateOpen)
    }

    @Test
    fun doesNotConfirmWhenDurationIsMetButDisplacementIsNot() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0L)))
        engine.accept(DetectionEvent.Location(location(0L, 10.0))) // anchor
        val barelyMoved = latitudeOffsetMeters(10.0, 5.0) // well under 40m

        val decision = engine.accept(DetectionEvent.Location(location(11_000_000_000L, barelyMoved)))

        assertEquals(CandidateStartDecision.NoChange, decision)
        assertTrue("candidate stays open - not confirmed, not abandoned", engine.isCandidateOpen)
    }

    @Test
    fun doesNotConfirmWhenDisplacementIsMetButDurationIsNot() {
        // A GPS jump right after the candidate opens must not confirm on its own (SCN-015).
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0L)))
        engine.accept(DetectionEvent.Location(location(0L, 10.0))) // anchor
        val jumped = latitudeOffsetMeters(10.0, 500.0)

        val decision = engine.accept(DetectionEvent.Location(location(1_000_000_000L, jumped))) // only 1s later

        assertEquals(CandidateStartDecision.NoChange, decision)
        assertTrue(engine.isCandidateOpen)
    }

    @Test
    fun abandonsWhenInVehicleExitArrivesBeforeConfirmation() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0L)))

        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 2_000_000_000L)))

        assertTrue(decision is CandidateStartDecision.Abandoned)
        assertEquals(CandidateStartEngine.REASON_IN_VEHICLE_EXIT, (decision as CandidateStartDecision.Abandoned).reasonCode)
        assertEquals(2_000L, decision.evidence.elapsedMs)
        assertFalse(engine.isCandidateOpen)
    }

    @Test
    fun abandonsWhenTheCandidateWindowExpiresViaATimeTickWithNoNewSamples() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0L)))

        val decision = engine.accept(DetectionEvent.TimeTick(nowElapsedRealtimeNanos = 70_000_000_000L)) // > 60s max window

        assertTrue(decision is CandidateStartDecision.Abandoned)
        decision as CandidateStartDecision.Abandoned
        assertEquals(CandidateStartEngine.REASON_WINDOW_EXPIRED, decision.reasonCode)
        assertEquals("no fix ever arrived - the evidence says so", 0, decision.evidence.fixCount)
        assertNull(decision.evidence.firstFixDelayMs)
        assertFalse(engine.isCandidateOpen)
    }

    @Test
    fun abandonsWhenTheCandidateWindowExpiresEvenWithLocationSamplesStillArriving() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0L)))
        engine.accept(DetectionEvent.Location(location(0L, 10.0))) // anchor, no displacement ever follows

        val decision = engine.accept(DetectionEvent.Location(location(70_000_000_000L, 10.0)))

        assertTrue(decision is CandidateStartDecision.Abandoned)
        assertEquals(CandidateStartEngine.REASON_WINDOW_EXPIRED, (decision as CandidateStartDecision.Abandoned).reasonCode)
    }

    @Test
    fun anExpiredCandidateReportsWhatItSawSoTheLostRideCanBeExplained() {
        // DET-008: four whole rides in one day left no trace of why they never started.
        open()
        fixAt(2, speedMps = 0.2f)
        fixAt(20, speedMps = 0.4f)

        val decision = fixAt(70, speedMps = 0.1f)

        decision as CandidateStartDecision.Abandoned
        assertEquals("the expiring fix itself is not counted", 2, decision.evidence.fixCount)
        assertEquals(2_000L, decision.evidence.firstFixDelayMs)
        assertEquals(0.4f, decision.evidence.maxSpeedMps!!, 0.001f)
        assertEquals(0.0, decision.evidence.displacementMeters!!, 0.5)
    }

    // --- DET-008: speed as evidence, and a window that survives a wait ---

    @Test
    fun aSustainedGpsSpeedConfirmsEvenWhenTheStraightLineDisplacementIsTooSmall() {
        // A GPS that reports a steady vehicle speed while the positions barely move (a tight loop, a stale anchor).
        open()
        fixAt(0, speedMps = 3.0f)
        fixAt(5, speedMps = 3.0f)

        val decision = fixAt(11, speedMps = 3.0f) // 11 s >= 10 s, three fast fixes in a row, still ~0 m from the anchor

        assertTrue(decision is CandidateStartDecision.Confirmed)
        decision as CandidateStartDecision.Confirmed
        assertEquals(CandidateStartEngine.REASON_CONFIRMED_SPEED, decision.reasonCode)
        assertEquals(3.0f, decision.evidence.maxSpeedMps!!, 0.001f)
    }

    @Test
    fun displacementStillConfirmsAndIsReportedAsTheReasonWhenBothHold() {
        open()
        fixAt(0, speedMps = 6f)
        fixAt(5, speedMps = 6f)

        val decision = fixAt(11, speedMps = 6f, latitude = latitudeOffsetMeters(10.0, 60.0))

        decision as CandidateStartDecision.Confirmed
        assertEquals(CandidateStartEngine.REASON_CONFIRMED_DISPLACEMENT, decision.reasonCode)
    }

    @Test
    fun twoFastFixesAreNotEnoughAndASlowOneRestartsTheCount() {
        // DP-001: one or two quick numbers are not a pattern.
        open()
        fixAt(0, speedMps = 4f)
        fixAt(5, speedMps = 4f)
        fixAt(8, speedMps = 0.5f) // breaks the run

        assertEquals(CandidateStartDecision.NoChange, fixAt(11, speedMps = 4f))
        assertEquals(CandidateStartDecision.NoChange, fixAt(12, speedMps = 4f))
        assertTrue("three in a row after the break confirm", fixAt(13, speedMps = 4f) is CandidateStartDecision.Confirmed)
    }

    @Test
    fun aWalkingPaceGpsSpeedNeverConfirms() {
        open()
        for (second in listOf(0, 4, 8, 12, 16, 20, 24)) {
            assertEquals(CandidateStartDecision.NoChange, fixAt(second, speedMps = 1.8f))
        }
        assertTrue(engine.isCandidateOpen)
    }

    @Test
    fun speedAloneNeverConfirmsBeforeTheMinimumDuration() {
        open()
        fixAt(0, speedMps = 5f)
        fixAt(1, speedMps = 5f)

        assertEquals("three fast fixes in 2 s is still not DP-002's sustained evidence", CandidateStartDecision.NoChange, fixAt(2, speedMps = 5f))
    }

    @Test
    fun theDefaultWindowOutlastsATypicalWaitBeforeTheRiderActuallyLeaves() {
        // The reason for 5 minutes: Activity Recognition says IN_VEHICLE once, when the engine is on, and never again.
        val defaults = CandidateStartEngine()
        defaults.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0L)))
        defaults.accept(DetectionEvent.Location(location(1_000_000_000L, 10.0, speedMps = 0f)))
        // Warming up and waiting for 3 minutes, standing still: the old 2-minute window would have given up here.
        assertEquals(CandidateStartDecision.NoChange, defaults.accept(DetectionEvent.Location(location(180_000_000_000L, 10.0, speedMps = 0f))))

        // ...then the rider leaves.
        defaults.accept(DetectionEvent.Location(location(185_000_000_000L, latitudeOffsetMeters(10.0, 40.0), speedMps = 8f)))
        val decision = defaults.accept(DetectionEvent.Location(location(190_000_000_000L, latitudeOffsetMeters(10.0, 80.0), speedMps = 8f)))

        assertTrue(decision is CandidateStartDecision.Confirmed)
    }

    @Test
    fun aSecondInVehicleEnterWhileAlreadyACandidateChangesNothing() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0L)))

        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 1_000_000_000L)))

        assertEquals(CandidateStartDecision.NoChange, decision)
        assertTrue(engine.isCandidateOpen)
    }

    @Test
    fun anExitWithNoOpenCandidateChangesNothing() {
        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 0L)))

        assertEquals(CandidateStartDecision.NoChange, decision)
        assertFalse(engine.isCandidateOpen)
    }

    @Test
    fun nonVehicleActivityTransitionsAreIgnored() {
        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.WALKING, TransitionType.ENTER, 0L)))

        assertEquals(CandidateStartDecision.NoChange, decision)
        assertFalse(engine.isCandidateOpen)
    }

    @Test
    fun aNewCandidateCanOpenAfterAPriorOneWasAbandoned() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0L)))
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 1_000_000_000L)))

        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 2_000_000_000L)))

        assertEquals(CandidateStartDecision.CandidateOpened, decision)
        assertTrue(engine.isCandidateOpen)
    }

    @Test
    fun aNewCandidateCanOpenAfterAPriorOneWasConfirmed() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0L)))
        engine.accept(DetectionEvent.Location(location(0L, 10.0)))
        engine.accept(DetectionEvent.Location(location(11_000_000_000L, latitudeOffsetMeters(10.0, 50.0))))
        check(!engine.isCandidateOpen)

        val decision = engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 20_000_000_000L)))

        assertEquals(CandidateStartDecision.CandidateOpened, decision)
    }

    // --- DET-009 (ADR-026): Android also gives a motorcycle the label ON_BICYCLE ---

    private fun label(type: ActivityType, transition: TransitionType, atSeconds: Int) =
        engine.accept(DetectionEvent.Activity(activity(type, transition, atSeconds * 1_000_000_000L)))

    @Test
    fun anOnBicycleEnterOpensACandidateJustLikeInVehicleDoes() {
        // Field evidence (2026-10-01): a 1 km motorcycle ride was labelled ON_BICYCLE for 70 s before IN_VEHICLE, so
        // nothing ever opened.
        val decision = label(ActivityType.ON_BICYCLE, TransitionType.ENTER, 0)

        assertEquals(CandidateStartDecision.CandidateOpened, decision)
        assertTrue(engine.isCandidateOpen)
    }

    @Test
    fun aRideLabelledOnBicycleStillNeedsRealMovementToConfirm() {
        label(ActivityType.ON_BICYCLE, TransitionType.ENTER, 0)

        val decision = fixAt(11, speedMps = 0.2f)

        assertEquals(CandidateStartDecision.NoChange, decision)
        assertTrue(engine.isCandidateOpen)
    }

    @Test
    fun aRideLabelledOnBicycleConfirmsOnSpeedLikeAnyOther() {
        label(ActivityType.ON_BICYCLE, TransitionType.ENTER, 0)
        fixAt(0, speedMps = 6.0f)
        fixAt(5, speedMps = 6.0f)

        val decision = fixAt(11, speedMps = 6.0f)

        assertTrue(decision is CandidateStartDecision.Confirmed)
        assertEquals(CandidateStartEngine.REASON_CONFIRMED_SPEED, (decision as CandidateStartDecision.Confirmed).reasonCode)
    }

    @Test
    fun anOnBicycleExitAbandonsAndSaysItWasTheBicycleLabel() {
        label(ActivityType.ON_BICYCLE, TransitionType.ENTER, 0)

        val decision = label(ActivityType.ON_BICYCLE, TransitionType.EXIT, 2)

        assertTrue(decision is CandidateStartDecision.Abandoned)
        assertEquals(CandidateStartEngine.REASON_ON_BICYCLE_EXIT, (decision as CandidateStartDecision.Abandoned).reasonCode)
        assertFalse(engine.isCandidateOpen)
    }

    @Test
    fun theOtherLabelTakingOverIsTheSameRideNotAnEnd() {
        // The first real ride: IN_VEHICLE at 0, re-labelled ON_BICYCLE 4 s later - ENTER of the new label first, as the
        // receiver hands them over, then the old label's EXIT.
        label(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0)
        label(ActivityType.ON_BICYCLE, TransitionType.ENTER, 4)

        val decision = label(ActivityType.IN_VEHICLE, TransitionType.EXIT, 4)

        assertEquals("the stale EXIT of the old label is not an end", CandidateStartDecision.NoChange, decision)
        assertTrue(engine.isCandidateOpen)
    }

    @Test
    fun theFirstRealRideConfirmsAfterItsLabelChanges() {
        // Replays the phone's 18:39 ride: 5.3 m/s on the very first fixes, label IN_VEHICLE -> ON_BICYCLE after 4 s.
        label(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0)
        fixAt(1, speedMps = 5.3f)
        label(ActivityType.ON_BICYCLE, TransitionType.ENTER, 4)
        label(ActivityType.IN_VEHICLE, TransitionType.EXIT, 4)
        fixAt(6, speedMps = 5.5f)

        val decision = fixAt(12, speedMps = 5.6f)

        assertTrue(decision is CandidateStartDecision.Confirmed)
        assertEquals(CandidateStartEngine.REASON_CONFIRMED_SPEED, (decision as CandidateStartDecision.Confirmed).reasonCode)
    }

    @Test
    fun aLabelChangeThatArrivesExitFirstStillAbandons() {
        // Not the order the receiver produces, and not what the engine can fix: an EXIT of the current label with no
        // ENTER yet is an end. Pinned so the receiver's ordering stays the thing that keeps flips from abandoning.
        label(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0)

        val decision = label(ActivityType.IN_VEHICLE, TransitionType.EXIT, 4)

        assertTrue(decision is CandidateStartDecision.Abandoned)
    }

    @Test
    fun walkingStillAndRunningNeverOpenACandidate() {
        for (type in listOf(ActivityType.WALKING, ActivityType.ON_FOOT, ActivityType.RUNNING, ActivityType.STILL, ActivityType.UNKNOWN)) {
            assertEquals("$type", CandidateStartDecision.NoChange, label(type, TransitionType.ENTER, 0))
            assertFalse("$type", engine.isCandidateOpen)
        }
    }

    @Test
    fun anExitOfAnotherLabelDoesNotAbandonACandidateThatNeverSawThatLabel() {
        label(ActivityType.ON_BICYCLE, TransitionType.ENTER, 0)

        val decision = label(ActivityType.IN_VEHICLE, TransitionType.EXIT, 3)

        assertEquals(CandidateStartDecision.NoChange, decision)
        assertTrue(engine.isCandidateOpen)
    }

    // --- DET-012 (ADR-031): a start probe - a candidate opened without any vehicle label, confirmed by speed alone ---

    private val probeProfile = StartProbeProfile()

    private fun probeEngine() = CandidateStartEngine(probeProfile.toCandidateProfile()).also { it.openByProbe(0L) }

    private fun CandidateStartEngine.probeFix(seconds: Int, speedMps: Float?, latitude: Double = 10.0) =
        accept(DetectionEvent.Location(location(seconds * 1_000_000_000L, latitude, speedMps = speedMps)))

    @Test
    fun aProbeOpensACandidateWithNoLabelAndOnlyOnce() {
        val probe = CandidateStartEngine(probeProfile.toCandidateProfile())

        assertEquals(CandidateStartDecision.CandidateOpened, probe.openByProbe(0L))
        assertTrue(probe.isCandidateOpen)
        assertEquals("a second open changes nothing", CandidateStartDecision.NoChange, probe.openByProbe(1_000_000_000L))
    }

    /** Walking 300 m is the commonest thing a probe will see: it must never look like the start of a trip. */
    @Test
    fun aProbeNeverConfirmsOnDisplacementHoweverFarTheWalkerGoes() {
        val probe = probeEngine()
        var latitude = 10.0

        val decisions = (0..200 step 5).map { seconds ->
            latitude = latitudeOffsetMeters(10.0, seconds * 1.5)
            probe.probeFix(seconds, speedMps = 1.5f, latitude = latitude)
        }

        assertTrue("300 m on foot confirms nothing", decisions.none { it is CandidateStartDecision.Confirmed })
        assertTrue(probe.isCandidateOpen)
    }

    @Test
    fun aProbeConfirmsOnThreeFastFixesInARowAfterItsMinimumDuration() {
        val probe = probeEngine()
        probe.probeFix(0, speedMps = 0.4f)
        probe.probeFix(60, speedMps = 1.2f)

        probe.probeFix(100, speedMps = 6.0f)
        probe.probeFix(102, speedMps = 6.5f)
        val decision = probe.probeFix(104, speedMps = 7.0f)

        assertTrue(decision is CandidateStartDecision.Confirmed)
        decision as CandidateStartDecision.Confirmed
        assertEquals(CandidateStartEngine.REASON_CONFIRMED_SPEED, decision.reasonCode)
        assertEquals(7.0f, decision.evidence.maxSpeedMps!!, 0.001f)
        assertEquals(104_000L, decision.evidence.elapsedMs)
    }

    @Test
    fun aProbeAbandonsWhenItsWindowExpires() {
        val probe = probeEngine()
        probe.probeFix(0, speedMps = 1.0f)

        val decision = probe.probeFix(361, speedMps = 1.0f)

        assertTrue(decision is CandidateStartDecision.Abandoned)
        assertEquals(CandidateStartEngine.REASON_WINDOW_EXPIRED, (decision as CandidateStartDecision.Abandoned).reasonCode)
        assertFalse(probe.isCandidateOpen)
    }

    @Test
    fun aProbeHasNoLabelSoAVehicleExitIsNotWhatEndsIt() {
        val probe = probeEngine()

        val decision = probe.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.EXIT, 5_000_000_000L)))

        assertEquals(CandidateStartDecision.NoChange, decision)
        assertTrue(probe.isCandidateOpen)
    }
}
