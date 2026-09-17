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

    private fun location(elapsedNanos: Long, latitude: Double, longitude: Double = -84.0) = LocationSample(
        wallTimeEpochMs = elapsedNanos / 1_000_000,
        elapsedRealtimeNanos = elapsedNanos,
        receivedAtElapsedRealtimeNanos = elapsedNanos,
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyM = 5.0f,
        requestProfileId = "candidate-start-burst"
    )

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

        assertEquals(CandidateStartDecision.Abandoned, decision)
        assertFalse(engine.isCandidateOpen)
    }

    @Test
    fun abandonsWhenTheCandidateWindowExpiresViaATimeTickWithNoNewSamples() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0L)))

        val decision = engine.accept(DetectionEvent.TimeTick(nowElapsedRealtimeNanos = 70_000_000_000L)) // > 60s max window

        assertEquals(CandidateStartDecision.Abandoned, decision)
        assertFalse(engine.isCandidateOpen)
    }

    @Test
    fun abandonsWhenTheCandidateWindowExpiresEvenWithLocationSamplesStillArriving() {
        engine.accept(DetectionEvent.Activity(activity(ActivityType.IN_VEHICLE, TransitionType.ENTER, 0L)))
        engine.accept(DetectionEvent.Location(location(0L, 10.0))) // anchor, no displacement ever follows

        val decision = engine.accept(DetectionEvent.Location(location(70_000_000_000L, 10.0)))

        assertEquals(CandidateStartDecision.Abandoned, decision)
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
}
