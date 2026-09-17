package com.mototriptracker.app.tracking.coordinator

import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorState
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.EndSource
import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.core.model.TransitionType
import com.mototriptracker.app.testing.FakeLocationGateway
import com.mototriptracker.app.testing.FakeProcessingScheduler
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.location.LocationGateway
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TRK-001 acceptance: "one active capture only; repeated Start is
 * idempotent; service rehydrates state from Room." TRK-002 acceptance:
 * "ordering preserved; invalid/poor points are assessed without rewriting
 * raw evidence; offline works" (offline trivially holds — nothing in this
 * class or [FakeLocationGateway] ever touches the network). The service
 * itself (`TrackingForegroundServiceTest`) proves it delegates correctly;
 * this proves the actual decision logic, independent of any Android Service.
 */
@RunWith(RobolectricTestRunner::class)
class TrackingSessionCoordinatorTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var coordinator: TrackingSessionCoordinator
    private val clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L)
    private val processingScheduler = FakeProcessingScheduler()

    private fun coordinatorWith(samples: List<LocationSample>) = TrackingSessionCoordinator(
        database = db,
        tripCaptureDao = db.tripCaptureDao(),
        diagnosticEventDao = db.diagnosticEventDao(),
        rawTrackPointDao = db.rawTrackPointDao(),
        captureEventDao = db.captureEventDao(),
        tripDao = db.tripDao(),
        tripPartDao = db.tripPartDao(),
        locationGateway = FakeLocationGateway(samples),
        processingScheduler = processingScheduler,
        clock = clock,
        idGenerator = FakeIdGenerator(prefix = "capture")
    )

    private fun sample(elapsedNanos: Long, lat: Double = 10.0, lon: Double = -20.0) = LocationSample(
        wallTimeEpochMs = 2_000L,
        elapsedRealtimeNanos = elapsedNanos,
        receivedAtElapsedRealtimeNanos = elapsedNanos,
        latitude = lat,
        longitude = lon,
        horizontalAccuracyM = 5.0f,
        requestProfileId = "test-profile"
    )

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        coordinator = coordinatorWith(emptyList())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun firstManualStartCreatesANewActiveCapture() = runTest {
        val result = coordinator.startManualCapture()

        assertTrue(result is TrackingSessionCoordinator.StartResult.Started)
        val active = coordinator.findActiveCapture()
        assertEquals((result as TrackingSessionCoordinator.StartResult.Started).captureId, active?.id)
    }

    @Test
    fun secondManualStartReusesTheSameActiveCaptureInsteadOfCreatingAnother() = runTest {
        val first = coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started

        val second = coordinator.startManualCapture()

        assertTrue(second is TrackingSessionCoordinator.StartResult.AlreadyActive)
        assertEquals(first.captureId, (second as TrackingSessionCoordinator.StartResult.AlreadyActive).captureId)
        assertEquals(1, db.tripCaptureDao().countByStatus(CaptureStatus.ACTIVE))
    }

    @Test
    fun findActiveCaptureReturnsNullWhenNothingWasStarted() = runTest {
        assertEquals(null, coordinator.findActiveCapture())
    }

    @Test
    fun startCommandIsRecordedAsADiagnosticEvent() = runTest {
        coordinator.startManualCapture()

        val events = db.diagnosticEventDao().count()
        assertEquals(1, events)
    }

    @Test
    fun recordLocationUpdatesPersistsSamplesInArrivalOrderEvenWithCollidingTimestamps() = runTest {
        val captureId =
            (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        // TST-DB-003: two samples sharing the same elapsedRealtimeNanos must
        // still land in distinct, arrival-ordered sequenceNumbers.
        val samples = listOf(sample(elapsedNanos = 5_000L), sample(elapsedNanos = 5_000L), sample(elapsedNanos = 4_000L))

        coordinatorWith(samples).recordLocationUpdates(captureId)

        val points = db.rawTrackPointDao().findAllByCapture(captureId)
        assertEquals(listOf(0L, 1L, 2L), points.map { it.sequenceNumber })
        assertEquals(listOf(5_000L, 5_000L, 4_000L), points.map { it.elapsedRealtimeNanos })
    }

    @Test
    fun recordLocationUpdatesPreservesNullableFieldsAndStampsTrackingDetectorState() = runTest {
        val captureId =
            (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        val minimalSample = sample(elapsedNanos = 1_000L)

        coordinatorWith(listOf(minimalSample)).recordLocationUpdates(captureId)

        val point = db.rawTrackPointDao().findAllByCapture(captureId).single()
        assertEquals("test-profile", point.requestProfileId)
        assertEquals(DetectorState.TRACKING.name, point.detectorStateSnapshot)
        assertNull("absent altitude must stay null, not 0.0", point.altitudeEllipsoidM)
        assertNull("absent speed must stay null, not 0.0", point.speedMps)
        assertNull("absent bearing must stay null, not 0.0", point.bearingDeg)
        assertNull(point.callbackBatchId)
    }

    @Test
    fun recordLocationUpdatesResumesFromTheExistingMaxSequenceNumberAfterARestart() = runTest {
        val captureId =
            (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        coordinatorWith(listOf(sample(1_000L), sample(2_000L))).recordLocationUpdates(captureId)

        // Simulates TRK-001's sticky-restart rehydration resuming location
        // recording into the same still-ACTIVE capture with a fresh gateway.
        coordinatorWith(listOf(sample(3_000L))).recordLocationUpdates(captureId)

        val points = db.rawTrackPointDao().findAllByCapture(captureId)
        assertEquals(listOf(0L, 1L, 2L), points.map { it.sequenceNumber })
    }

    @Test
    fun recordLocationUpdatesLogsADiagnosticEventAndMovesOnWhenAPointFailsToPersist() = runTest {
        // No TripCapture row exists for this id, so the raw_track_point
        // foreign key rejects the insert - a real, not simulated, failure.
        val orphanCaptureId = "no-such-capture"

        coordinatorWith(listOf(sample(1_000L))).recordLocationUpdates(orphanCaptureId)

        assertEquals(0, db.rawTrackPointDao().countByCapture(orphanCaptureId))
        val failureEvent = db.diagnosticEventDao().findAll().single { it.captureId == orphanCaptureId }
        assertEquals(DiagnosticCategory.PERSISTENCE, failureEvent.category)
        assertEquals(DiagnosticSeverity.ERROR, failureEvent.severity)
    }

    @Test
    fun finishCaptureCompletesTheCaptureCreatesATripAndEnqueuesProcessing() = runTest {
        val captureId =
            (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId

        val result = coordinator.finishCapture(captureId)

        assertTrue(result is TrackingSessionCoordinator.FinishResult.Finished)
        val tripId = (result as TrackingSessionCoordinator.FinishResult.Finished).tripId

        val capture = requireNotNull(db.tripCaptureDao().findById(captureId))
        assertEquals(CaptureStatus.COMPLETED, capture.status)
        assertEquals(clock.wallClockMillis(), capture.endedAt)
        assertEquals(0, db.tripCaptureDao().countByStatus(CaptureStatus.ACTIVE))

        val trip = requireNotNull(db.tripDao().findById(tripId))
        assertEquals(com.mototriptracker.app.core.model.TripStatus.COMPLETED, trip.status)

        val part = requireNotNull(db.tripPartDao().findByCaptureId(captureId))
        assertEquals(tripId, part.tripId)
        assertEquals(capture.startElapsedRealtimeNanos, part.startElapsedRealtimeNanos)

        assertEquals(
            listOf(FakeProcessingScheduler.EnqueuedRequest(tripId, captureId)),
            processingScheduler.enqueuedRequests
        )
    }

    @Test
    fun finishCaptureWithNoRawPointsLeavesTripPartSequenceNumbersNull() = runTest {
        val captureId =
            (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId

        coordinator.finishCapture(captureId)

        val part = requireNotNull(db.tripPartDao().findByCaptureId(captureId))
        assertNull("no points were ever recorded - must stay null, not 0", part.startSequenceNumber)
        assertNull(part.endSequenceNumber)
    }

    @Test
    fun finishCaptureWithRawPointsSetsStartAndEndSequenceNumbers() = runTest {
        val captureId =
            (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        coordinatorWith(listOf(sample(1_000L), sample(2_000L), sample(3_000L))).recordLocationUpdates(captureId)

        coordinator.finishCapture(captureId)

        val part = requireNotNull(db.tripPartDao().findByCaptureId(captureId))
        assertEquals(0L, part.startSequenceNumber)
        assertEquals(2L, part.endSequenceNumber)
    }

    @Test
    fun repeatedFinishIsIdempotentAndDoesNotCreateASecondTripOrEnqueueTwice() = runTest {
        val captureId =
            (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        val first = coordinator.finishCapture(captureId) as TrackingSessionCoordinator.FinishResult.Finished

        val second = coordinator.finishCapture(captureId)

        assertTrue(second is TrackingSessionCoordinator.FinishResult.AlreadyFinished)
        assertEquals(first.tripId, (second as TrackingSessionCoordinator.FinishResult.AlreadyFinished).tripId)
        assertEquals(1, processingScheduler.enqueuedRequests.size)

        // The second Finish's own diagnostic event must record what was
        // actually true going into it (COMPLETED), not blindly repeat the
        // first Finish's ACTIVE -> COMPLETED transition.
        val secondFinishEvent = db.diagnosticEventDao().findAll().last { it.eventType == "FINISH" }
        assertEquals(CaptureStatus.COMPLETED.name, secondFinishEvent.stateBefore)
    }

    @Test
    fun finishCommandIsRecordedAsADiagnosticEvent() = runTest {
        val captureId =
            (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId

        coordinator.finishCapture(captureId)

        // 1 for Start, 1 for Finish.
        assertEquals(2, db.diagnosticEventDao().count())
    }

    @Test
    fun concurrentFinishAttemptsForTheSameCaptureCreateOnlyOneTrip() = runTest {
        val captureId =
            (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId

        // Mirrors FND-003's concurrent-start proof, but for
        // database.withTransaction {} rather than a @Transaction DAO method
        // - a different Room mechanism, verified separately rather than
        // assumed to behave the same way. A real UuidIdGenerator (not the
        // colliding-by-design FakeIdGenerator, whose counter restarts at 1
        // for every new instance) so a genuine double-Trip bug shows up as
        // two distinct rows instead of a misleading primary-key collision.
        val jobs = (1..20).map {
            async {
                TrackingSessionCoordinator(
                    database = db,
                    tripCaptureDao = db.tripCaptureDao(),
                    diagnosticEventDao = db.diagnosticEventDao(),
                    rawTrackPointDao = db.rawTrackPointDao(),
                    captureEventDao = db.captureEventDao(),
                    tripDao = db.tripDao(),
                    tripPartDao = db.tripPartDao(),
                    locationGateway = FakeLocationGateway(emptyList()),
                    processingScheduler = FakeProcessingScheduler(),
                    clock = clock,
                    idGenerator = com.mototriptracker.app.core.common.UuidIdGenerator()
                ).finishCapture(captureId)
            }
        }
        val results = jobs.awaitAll()

        val finishedCount = results.count { it is TrackingSessionCoordinator.FinishResult.Finished }
        assertEquals(1, finishedCount)
        assertEquals(CaptureStatus.COMPLETED, db.tripCaptureDao().findById(captureId)?.status)
        assertEquals(1, results.map { it.tripId }.distinct().size)
    }

    // --- AUTO-001: runAutoDetection ------------------------------------
    //
    // Cross-source ordering (activity vs. location vs. the internal ticker)
    // is made deterministic here by driving both replay flows through real
    // `delay()` calls under `runTest`'s virtual-time scheduler, rather than
    // `ActivityReplaySource`/`LocationReplaySource`'s instant `.asFlow()` -
    // those two sources have no way to interleave predictably against each
    // other otherwise. The ticker itself fires throughout every case here on
    // the shared, never-advanced [clock]; since its `TimeTick`s always carry
    // the same tiny constant `elapsedRealtimeNanos`, they never spuriously
    // expire/confirm anything - the engines' own exhaustive `TimeTick` test
    // suites (`CandidateStartEngineTest`/`CandidateStopEngineTest`) already
    // cover that logic in isolation, so it isn't re-proven here.

    private fun coordinatorWithLocationFlow(locationFlow: Flow<LocationSample>) = TrackingSessionCoordinator(
        database = db,
        tripCaptureDao = db.tripCaptureDao(),
        diagnosticEventDao = db.diagnosticEventDao(),
        rawTrackPointDao = db.rawTrackPointDao(),
        captureEventDao = db.captureEventDao(),
        tripDao = db.tripDao(),
        tripPartDao = db.tripPartDao(),
        locationGateway = object : LocationGateway {
            override fun locationUpdates(): Flow<LocationSample> = locationFlow
        },
        processingScheduler = processingScheduler,
        clock = clock,
        // A distinct prefix from the shared `coordinator`'s "capture-N" -
        // some of these tests (the race-lost one) have both write to the
        // same db, and a fresh FakeIdGenerator restarting at 1 would
        // otherwise collide with an ID `coordinator` already inserted.
        idGenerator = FakeIdGenerator(prefix = "auto-capture")
    )

    private fun <T> timedFlow(vararg entries: Pair<Long, T>): Flow<T> = flow {
        var previousMs = 0L
        for ((atMs, value) in entries) {
            delay(atMs - previousMs)
            previousMs = atMs
            emit(value)
        }
    }

    private fun activitySample(type: ActivityType, transition: TransitionType, elapsedNanos: Long) = ActivityTransitionSample(
        activityType = type,
        transitionType = transition,
        elapsedRealtimeNanos = elapsedNanos,
        wallTimeEpochMs = elapsedNanos / 1_000_000,
        source = "test"
    )

    @Test
    fun runAutoDetectionAbandonsWhenInVehicleExitArrivesBeforeConfirming() = runTest {
        val activityFlow = timedFlow(
            0L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L),
            5_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 5_000_000_000L)
        )

        val outcome = coordinatorWithLocationFlow(emptyFlow()).runAutoDetection(activityFlow)

        assertEquals(TrackingSessionCoordinator.AutoDetectionOutcome.CandidateAbandoned, outcome)
        assertEquals(0, db.tripCaptureDao().countByStatus(CaptureStatus.ACTIVE))
    }

    @Test
    fun runAutoDetectionAbandonsWhenAnotherCaptureAlreadyWonTheRace() = runTest {
        coordinator.startManualCapture()
        val activityFlow = timedFlow(
            0L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L)
        )
        val locationFlow = timedFlow(
            1L to sample(elapsedNanos = 1_000_000L),
            20_000L to sample(elapsedNanos = 20_000_000_000L, lat = 10.001, lon = -20.0)
        )

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(activityFlow)

        assertEquals(TrackingSessionCoordinator.AutoDetectionOutcome.CandidateAbandoned, outcome)
        assertEquals(1, db.tripCaptureDao().countByStatus(CaptureStatus.ACTIVE))
    }

    @Test
    fun runAutoDetectionConfirmsStartsAnAutoCaptureThenAutoFinishesOnWalkingAway() = runTest {
        val activityFlow = timedFlow(
            0L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L),
            28_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 28_000_000_000L),
            30_000L to activitySample(ActivityType.WALKING, TransitionType.ENTER, elapsedNanos = 30_000_000_000L)
        )
        val locationFlow = timedFlow(
            // Anchor - candidate not yet open when captured, so never persisted (documented v1 loss).
            1L to sample(elapsedNanos = 1_000_000L),
            // >=15s and >=40m from the anchor - confirms the start.
            20_000L to sample(elapsedNanos = 20_000_000_000L, lat = 10.001, lon = -20.0),
            // Arrives once a real capture exists - this one IS persisted.
            25_000L to sample(elapsedNanos = 25_000_000_000L, lat = 10.0011, lon = -20.0)
        )
        var startedCaptureId: String? = null

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(
            activityEvents = activityFlow,
            onCaptureStarted = { startedCaptureId = it }
        )

        assertTrue(outcome is TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted)
        val tripCompleted = outcome as TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted
        assertEquals(startedCaptureId, tripCompleted.captureId)

        val capture = requireNotNull(db.tripCaptureDao().findById(tripCompleted.captureId))
        assertEquals(StartSource.AUTO, capture.startSource)
        assertEquals(CaptureStatus.COMPLETED, capture.status)
        assertEquals(EndSource.AUTO, capture.endSource)

        val points = db.rawTrackPointDao().findAllByCapture(tripCompleted.captureId)
        assertEquals(1, points.size)
        assertEquals(25_000_000_000L, points.single().elapsedRealtimeNanos)

        val trip = requireNotNull(db.tripDao().findById(tripCompleted.tripId))
        assertEquals(com.mototriptracker.app.core.model.TripStatus.COMPLETED, trip.status)
    }
}
