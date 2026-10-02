package com.mototriptracker.app.tracking.coordinator

import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.database.entity.ManualPauseIntervalEntity
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorState
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.domain.detection.CandidateStopProfile
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.EndSource
import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.core.model.TransitionType
import com.mototriptracker.app.testing.FakeLocationGateway
import com.mototriptracker.app.testing.FakeProcessingScheduler
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.activityrecognition.ActivityTransitionRecorder
import com.mototriptracker.app.tracking.location.LocationGateway
import com.mototriptracker.app.tracking.persistence.RawPointWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        manualPauseIntervalDao = db.manualPauseIntervalDao(),
        locationGateway = FakeLocationGateway(samples),
        processingScheduler = processingScheduler,
        clock = clock,
        idGenerator = FakeIdGenerator(prefix = "capture")
    )

    private fun sample(elapsedNanos: Long, lat: Double = 10.0, lon: Double = -20.0, speedMps: Float? = null) = LocationSample(
        wallTimeEpochMs = 2_000L,
        elapsedRealtimeNanos = elapsedNanos,
        receivedAtElapsedRealtimeNanos = elapsedNanos,
        latitude = lat,
        longitude = lon,
        horizontalAccuracyM = 5.0f,
        requestProfileId = "test-profile",
        speedMps = speedMps
    )

    /** DET-008: a short grace period so the end-of-ride tests don't need minutes of synthetic samples. */
    private val shortStopGrace = CandidateStopProfile(minConfirmationDurationMs = 30_000L)

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
        val events = db.diagnosticEventDao().findAll().filter { it.captureId == orphanCaptureId }
        val failureEvent = events.single { it.eventType == RawPointWriter.EVENT_INSERT_FAILED }
        assertEquals(DiagnosticCategory.PERSISTENCE, failureEvent.category)
        assertEquals(DiagnosticSeverity.ERROR, failureEvent.severity)
        // REC-006: a row the database will never accept is not retried forever, and its loss is
        // summarized when the recording ends instead of being left as one lone error.
        assertEquals(1, events.count { it.eventType == RawPointWriter.EVENT_DATA_LOSS })
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
    fun finishCaptureWithATripEndTrimsThePartToTheLastPointAtOrBeforeIt() = runTest {
        // DET-008: the points after the vehicle stopped stay as raw evidence, but the Trip does not include them.
        val captureId =
            (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        coordinatorWith(listOf(sample(10_000_000_000L), sample(20_000_000_000L), sample(30_000_000_000L), sample(40_000_000_000L)))
            .recordLocationUpdates(captureId)

        val result = coordinator.finishCapture(captureId, EndSource.AUTO, tripEndsAtElapsedRealtimeNanos = 25_000_000_000L)

        val part = requireNotNull(db.tripPartDao().findByCaptureId(captureId))
        assertEquals(0L, part.startSequenceNumber)
        assertEquals(1L, part.endSequenceNumber)
        assertEquals("the Trip ends at the last kept point's own time (EDT-003's convention)", 20_000_000_000L, part.endElapsedRealtimeNanos)
        assertEquals("nothing was deleted (ADR-006)", 4, db.rawTrackPointDao().countByCapture(captureId))
        // The Trip's own time is that point's wall time ("when the ride ended"), not the moment the Finish ran (the clock reads 1000).
        assertEquals(2_000L, requireNotNull(db.tripDao().findById((result as TrackingSessionCoordinator.FinishResult.Finished).tripId)).createdAt)
    }

    @Test
    fun aRepeatedAutomaticFinishWithATripEndIsStillIdempotentAndChangesNothing() = runTest {
        val captureId =
            (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        coordinatorWith(listOf(sample(10_000_000_000L), sample(20_000_000_000L), sample(30_000_000_000L))).recordLocationUpdates(captureId)
        val first = coordinator.finishCapture(captureId, EndSource.AUTO, tripEndsAtElapsedRealtimeNanos = 25_000_000_000L)

        val second = coordinator.finishCapture(captureId, EndSource.AUTO, tripEndsAtElapsedRealtimeNanos = 15_000_000_000L)

        assertTrue(second is TrackingSessionCoordinator.FinishResult.AlreadyFinished)
        assertEquals(first.tripId, second.tripId)
        assertEquals(1L, requireNotNull(db.tripPartDao().findByCaptureId(captureId)).endSequenceNumber)
    }

    @Test
    fun finishCaptureWithATripEndBeforeEveryRecordedPointTrimsNothing() = runTest {
        // A Trip trimmed down to zero points would be empty; with nothing at or before the bound, the whole capture stays.
        val captureId =
            (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        coordinatorWith(listOf(sample(10_000_000_000L), sample(20_000_000_000L))).recordLocationUpdates(captureId)

        coordinator.finishCapture(captureId, EndSource.AUTO, tripEndsAtElapsedRealtimeNanos = 5_000_000_000L)

        val part = requireNotNull(db.tripPartDao().findByCaptureId(captureId))
        assertEquals(1L, part.endSequenceNumber)
        assertEquals(clock.elapsedRealtimeNanos(), part.endElapsedRealtimeNanos)
    }

    @Test
    fun aManualFinishNeverTrims() = runTest {
        val captureId =
            (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        coordinatorWith(listOf(sample(10_000_000_000L), sample(20_000_000_000L), sample(30_000_000_000L))).recordLocationUpdates(captureId)

        coordinator.finishCapture(captureId)

        assertEquals(2L, requireNotNull(db.tripPartDao().findByCaptureId(captureId)).endSequenceNumber)
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
                    manualPauseIntervalDao = db.manualPauseIntervalDao(),
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
        manualPauseIntervalDao = db.manualPauseIntervalDao(),
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
        val manual = coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started
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
        assertEquals(
            "the fixes the lost candidate held are not the manual capture's evidence (DET-010)",
            0,
            db.rawTrackPointDao().countByCapture(manual.captureId)
        )
    }

    @Test
    fun runAutoDetectionConfirmsStartsAnAutoCaptureThenAutoFinishesWhenTheRideEndsAndLeavesTheWalkingTailOutOfTheTrip() = runTest {
        val activityFlow = timedFlow(
            0L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L),
            // The vehicle stops here, and the rider walks away: Activity Recognition says so a moment later.
            28_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 28_000_000_000L),
            30_000L to activitySample(ActivityType.WALKING, TransitionType.ENTER, elapsedNanos = 30_000_000_000L)
        )
        val locationFlow = timedFlow(
            // Anchor - candidate not yet open when captured, so never persisted (documented v1 loss).
            1L to sample(elapsedNanos = 1_000_000L),
            // >=15s and >=40m from the anchor - confirms the start.
            20_000L to sample(elapsedNanos = 20_000_000_000L, lat = 10.001, lon = -20.0),
            // Arrives once a real capture exists - this one IS persisted, and is before the vehicle stopped.
            25_000L to sample(elapsedNanos = 25_000_000_000L, lat = 10.0011, lon = -20.0),
            // The walk away (walking pace): persisted (raw evidence is never thrown away) but not part of the Trip.
            29_000L to sample(elapsedNanos = 29_000_000_000L, lat = 10.0012, lon = -20.0, speedMps = 1.2f),
            40_000L to sample(elapsedNanos = 40_000_000_000L, lat = 10.0013, lon = -20.0, speedMps = 1.3f),
            // 32 s after the vehicle stopped, past the 30 s grace: the stop is confirmed here, not at the WALKING sample.
            60_000L to sample(elapsedNanos = 60_000_000_000L, lat = 10.0013, lon = -20.0, speedMps = 1.3f)
        )
        var startedCaptureId: String? = null

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(
            activityEvents = activityFlow,
            onCaptureStarted = { startedCaptureId = it },
            stopProfile = shortStopGrace
        )

        assertTrue(outcome is TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted)
        val tripCompleted = outcome as TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted
        assertEquals(startedCaptureId, tripCompleted.captureId)

        val capture = requireNotNull(db.tripCaptureDao().findById(tripCompleted.captureId))
        assertEquals(StartSource.AUTO, capture.startSource)
        assertEquals(CaptureStatus.COMPLETED, capture.status)
        assertEquals(EndSource.AUTO, capture.endSource)

        val points = db.rawTrackPointDao().findAllByCapture(tripCompleted.captureId)
        assertEquals(
            "every sample is kept as raw evidence (ADR-006), including the two seen while the start was being validated (DET-010)",
            listOf(1_000_000L, 20_000_000_000L, 25_000_000_000L, 29_000_000_000L, 40_000_000_000L, 60_000_000_000L),
            points.map { it.elapsedRealtimeNanos }
        )
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), points.map { it.sequenceNumber })
        assertEquals(
            "the points taken while the candidate was validated say so",
            listOf("CANDIDATE_START", "CANDIDATE_START", "TRACKING", "TRACKING", "TRACKING", "TRACKING"),
            points.map { it.detectorStateSnapshot }
        )

        // DET-010: the capture - and so the Trip - begins at the first kept fix, not at the confirmation 20 s later.
        assertEquals(1_000_000L, capture.startElapsedRealtimeNanos)

        // DET-008 / F0.3 §7 req. 5: the Trip ends at the last point before the vehicle stopped (the 25 s one - the
        // stop itself is at 28 s), not where the grace period ended.
        val part = requireNotNull(db.tripPartDao().findByCaptureId(tripCompleted.captureId))
        assertEquals(0L, part.startSequenceNumber)
        assertEquals(1_000_000L, part.startElapsedRealtimeNanos)
        assertEquals(2L, part.endSequenceNumber)
        assertEquals(25_000_000_000L, part.endElapsedRealtimeNanos)

        val trip = requireNotNull(db.tripDao().findById(tripCompleted.tripId))
        assertEquals(com.mototriptracker.app.core.model.TripStatus.COMPLETED, trip.status)
    }

    // --- DET-010 (ADR-027): the fixes seen while a start candidate is validated become the capture's first points ---

    @Test
    fun anAbandonedCandidateLeavesNoCaptureAndSoNoStoredCoordinates() = runTest {
        // Fixes arrive while the candidate is open; it is then abandoned (the rider walked off). Nothing may be
        // written: a raw point cannot exist without a capture, and no capture was created.
        val activityFlow = timedFlow(
            0L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L),
            9_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 9_000_000_000L)
        )
        val locationFlow = timedFlow(
            1L to sample(elapsedNanos = 1_000_000L),
            4_000L to sample(elapsedNanos = 4_000_000_000L, lat = 10.0001, lon = -20.0),
            8_000L to sample(elapsedNanos = 8_000_000_000L, lat = 10.0002, lon = -20.0)
        )

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(activityFlow)

        assertEquals(TrackingSessionCoordinator.AutoDetectionOutcome.CandidateAbandoned, outcome)
        assertEquals(0, db.tripCaptureDao().countByStatus(CaptureStatus.ACTIVE))
        assertNull(db.tripCaptureDao().findMostRecentlyEnded())
    }

    @Test
    fun theCaptureBeginsAtTheFirstKeptFixAndTheTripPartCoversTheRetainedPoints() = runTest {
        val activityFlow = timedFlow(
            0L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L),
            50_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 50_000_000_000L)
        )
        val locationFlow = timedFlow(
            2_000L to sample(elapsedNanos = 2_000_000_000L),
            10_000L to sample(elapsedNanos = 10_000_000_000L, lat = 10.0003, lon = -20.0),
            20_000L to sample(elapsedNanos = 20_000_000_000L, lat = 10.001, lon = -20.0), // confirms the start
            30_000L to sample(elapsedNanos = 30_000_000_000L, lat = 10.002, lon = -20.0, speedMps = 9f),
            90_000L to sample(elapsedNanos = 90_000_000_000L, lat = 10.002, lon = -20.0, speedMps = 0f)
        )

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(activityFlow, stopProfile = shortStopGrace)

        val tripCompleted = outcome as TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted
        val capture = requireNotNull(db.tripCaptureDao().findById(tripCompleted.captureId))
        assertEquals("the capture began at the first fix it kept, not at the confirmation", 2_000_000_000L, capture.startElapsedRealtimeNanos)
        assertEquals(2_000L, capture.startedAt)
        val part = requireNotNull(db.tripPartDao().findByCaptureId(tripCompleted.captureId))
        assertEquals("the Trip's duration is measured from there", 2_000_000_000L, part.startElapsedRealtimeNanos)
        assertEquals(0L, part.startSequenceNumber)
        val points = db.rawTrackPointDao().findAllByCapture(tripCompleted.captureId)
        assertEquals(listOf(2_000_000_000L, 10_000_000_000L, 20_000_000_000L, 30_000_000_000L, 90_000_000_000L), points.map { it.elapsedRealtimeNanos })
        assertEquals("sequence numbers continue without a gap or a repeat", (0L..4L).toList(), points.map { it.sequenceNumber })
    }

    @Test
    fun aCandidateKeepsAtMostTheMostRecentFixesAndTheCaptureBeginsAtTheOldestOfThose() = runTest {
        // 650 fixes in the same place (so nothing confirms), then the one that confirms: only the last
        // MAX_CANDIDATE_FIXES of those 651 are kept, and the capture begins at the oldest of them.
        val cap = TrackingSessionCoordinator.MAX_CANDIDATE_FIXES
        val waiting = (1..650).map { i -> (i * 400L) to sample(elapsedNanos = i * 400_000_000L) }
        val locationFlow = timedFlow(
            *waiting.toTypedArray(),
            270_000L to sample(elapsedNanos = 270_000_000_000L, lat = 10.001, lon = -20.0), // confirms the start
            300_000L to sample(elapsedNanos = 300_000_000_000L, lat = 10.001, lon = -20.0, speedMps = 0f),
            330_000L to sample(elapsedNanos = 330_000_000_000L, lat = 10.001, lon = -20.0, speedMps = 0f)
        )
        val activityFlow = timedFlow(
            0L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L),
            280_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 280_000_000_000L)
        )

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(activityFlow, stopProfile = shortStopGrace)

        val tripCompleted = outcome as TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted
        val points = db.rawTrackPointDao().findAllByCapture(tripCompleted.captureId)
        assertEquals("the kept fixes plus the two taken after the start", cap + 2, points.size)
        val oldestKept = (651 - cap + 1) * 400_000_000L // the 52nd of 651 fixes, 20.8 s
        assertEquals(oldestKept, points.first().elapsedRealtimeNanos)
        assertEquals(oldestKept, requireNotNull(db.tripCaptureDao().findById(tripCompleted.captureId)).startElapsedRealtimeNanos)
    }

    // --- AUTO-002 (ADR-028): an automatic capture whose service was restarted goes back to watching for its end ---

    private fun rawPoint(captureId: String, sequenceNumber: Long, elapsedNanos: Long, lat: Double = 10.0, speedMps: Float? = null) = RawTrackPointEntity(
        captureId = captureId,
        sequenceNumber = sequenceNumber,
        capturedAt = elapsedNanos / 1_000_000,
        elapsedRealtimeNanos = elapsedNanos,
        receivedAtElapsedRealtimeNanos = elapsedNanos,
        latitude = lat,
        longitude = -20.0,
        horizontalAccuracyM = 5f,
        altitudeEllipsoidM = null,
        altitudeMslM = null,
        verticalAccuracyM = null,
        speedMps = speedMps,
        speedAccuracyMps = null,
        bearingDeg = null,
        bearingAccuracyDeg = null,
        provider = null,
        isMock = null,
        requestProfileId = "test-profile",
        callbackBatchId = null,
        detectorStateSnapshot = "TRACKING"
    )

    /**
     * What the phone holds when the process died mid-ride: an ACTIVE capture the detector started, the points recorded
     * so far, and the activity transitions the receiver stored (it records them even with the app not running).
     * [points] are (elapsed seconds, speed); [transitions] are recorded in the order given.
     */
    private suspend fun seedAutoCaptureLeftBehindByADeadProcess(
        points: List<Pair<Int, Float?>>,
        transitions: List<ActivityTransitionSample>
    ): String {
        val started = coordinator.startAutoCapture() as TrackingSessionCoordinator.StartResult.Started
        points.forEachIndexed { index, (seconds, speed) ->
            db.rawTrackPointDao().insert(rawPoint(started.captureId, index.toLong(), seconds * 1_000_000_000L, lat = 10.0 + index * 0.0001, speedMps = speed))
        }
        val recorder = ActivityTransitionRecorder(db.diagnosticEventDao(), clock, FakeIdGenerator(prefix = "transition"))
        transitions.forEach { recorder.record(it) }
        return started.captureId
    }

    private fun transition(type: ActivityType, transition: TransitionType, seconds: Int) =
        activitySample(type, transition, elapsedNanos = seconds * 1_000_000_000L)

    @Test
    fun aRestartedAutoCaptureKeepsRecordingIntoTheSameCaptureAndEndsWhenTheRideEnds() = runTest {
        val captureId = seedAutoCaptureLeftBehindByADeadProcess(
            points = listOf(10 to 8f, 20 to 8f),
            transitions = listOf(transition(ActivityType.IN_VEHICLE, TransitionType.ENTER, 1))
        )
        var resumedCaptureId: String? = null
        val activityFlow = timedFlow(45_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 45_000_000_000L))
        val locationFlow = timedFlow(
            30_000L to sample(elapsedNanos = 30_000_000_000L, lat = 10.001, speedMps = 8f),
            40_000L to sample(elapsedNanos = 40_000_000_000L, lat = 10.002, speedMps = 8f),
            80_000L to sample(elapsedNanos = 80_000_000_000L, lat = 10.002, speedMps = 0f) // 35 s after the EXIT: past the 30 s grace
        )

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(
            activityEvents = activityFlow,
            onCaptureStarted = { resumedCaptureId = it },
            resumeCaptureId = captureId,
            stopProfile = shortStopGrace
        )

        val completed = outcome as TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted
        assertEquals("it ended the capture that was left behind, not a new one", captureId, completed.captureId)
        assertEquals(captureId, resumedCaptureId)
        assertEquals(EndSource.AUTO, requireNotNull(db.tripCaptureDao().findById(captureId)).endSource)
        val points = db.rawTrackPointDao().findAllByCapture(captureId)
        assertEquals("numbering continues from the points already stored", (0L..4L).toList(), points.map { it.sequenceNumber })
        // The Trip ends at the last point before the vehicle stopped (the 40 s one), as for any automatic Finish.
        assertEquals(3L, requireNotNull(db.tripPartDao().findByCaptureId(captureId)).endSequenceNumber)
        val resumedEvent = db.diagnosticEventDao().findAll().single { it.eventType == TrackingSessionCoordinator.EVENT_AUTO_STOP_MONITORING_RESUMED }
        assertEquals("TRACKING", resumedEvent.reasonCode)
        assertEquals(captureId, resumedEvent.captureId)
        assertEquals("2", resumedEvent.metadata["replayedPoints"])
    }

    @Test
    fun aStopCandidateThatWasOpenBeforeTheRestartStillEndsTheTripAtTheRealStop() = runTest {
        // The vehicle stopped at 22 s, the process died, and the service came back long after: Activity Recognition had
        // delivered the EXIT to the receiver, which stored it, but the engine that should have counted the grace period
        // was gone.
        val captureId = seedAutoCaptureLeftBehindByADeadProcess(
            points = listOf(10 to 8f, 20 to 8f, 25 to 0f),
            transitions = listOf(
                transition(ActivityType.IN_VEHICLE, TransitionType.ENTER, 1),
                transition(ActivityType.IN_VEHICLE, TransitionType.EXIT, 22)
            )
        )
        val locationFlow = timedFlow(60_000L to sample(elapsedNanos = 60_000_000_000L, lat = 10.0, speedMps = 0f))

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(
            activityEvents = emptyFlow(),
            resumeCaptureId = captureId,
            stopProfile = shortStopGrace
        )

        assertTrue(outcome is TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted)
        val part = requireNotNull(db.tripPartDao().findByCaptureId(captureId))
        assertEquals("the Trip ends at the last point before the 22 s stop", 1L, part.endSequenceNumber)
        assertEquals(20_000_000_000L, part.endElapsedRealtimeNanos)
        val resumedEvent = db.diagnosticEventDao().findAll().single { it.eventType == TrackingSessionCoordinator.EVENT_AUTO_STOP_MONITORING_RESUMED }
        assertEquals("STOP_CANDIDATE_OPEN", resumedEvent.reasonCode)
    }

    @Test
    fun aStopCandidateThatTheVehicleMovingOffHadCancelledDoesNotEndTheTripAfterTheRestart() = runTest {
        // EXIT at 22 s, then two fast fixes in a row (26 s and 28 s): the light turned green and the live engine
        // cancelled the candidate. Rebuilt from the activity record alone it would still look open, and the first fix
        // after the restart would end a ride that was still going.
        val captureId = seedAutoCaptureLeftBehindByADeadProcess(
            points = listOf(20 to 8f, 23 to 0f, 26 to 6f, 28 to 7f),
            transitions = listOf(
                transition(ActivityType.IN_VEHICLE, TransitionType.ENTER, 1),
                transition(ActivityType.IN_VEHICLE, TransitionType.EXIT, 22)
            )
        )
        val activityFlow = timedFlow(140_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 140_000_000_000L))
        val locationFlow = timedFlow(
            100_000L to sample(elapsedNanos = 100_000_000_000L, lat = 10.01, speedMps = 8f),
            130_000L to sample(elapsedNanos = 130_000_000_000L, lat = 10.02, speedMps = 8f),
            180_000L to sample(elapsedNanos = 180_000_000_000L, lat = 10.02, speedMps = 0f) // 40 s after the real EXIT
        )

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(
            activityEvents = activityFlow,
            resumeCaptureId = captureId,
            stopProfile = shortStopGrace
        )

        assertTrue(outcome is TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted)
        // Four stored points (0-3), then the fixes at 100 s (4), 130 s (5) and 180 s (6): the Trip ends at the 130 s
        // point, before the real stop at 140 s. An end taken from the stale candidate would have come at the 100 s fix.
        assertEquals(5L, requireNotNull(db.tripPartDao().findByCaptureId(captureId)).endSequenceNumber)
        val resumedEvent = db.diagnosticEventDao().findAll().single { it.eventType == TrackingSessionCoordinator.EVENT_AUTO_STOP_MONITORING_RESUMED }
        assertEquals("the candidate the vehicle moving off cancelled is not open any more", "TRACKING", resumedEvent.reasonCode)
    }

    @Test
    fun aRecordThatAlreadySaysTheRideEndedFinishesItAtOnce() = runTest {
        // The process died after the last point that proved the stop (38 s after the EXIT, past the 30 s grace) was
        // stored but before the Finish could run.
        val captureId = seedAutoCaptureLeftBehindByADeadProcess(
            points = listOf(10 to 8f, 20 to 8f, 25 to 0f, 60 to 0f),
            transitions = listOf(
                transition(ActivityType.IN_VEHICLE, TransitionType.ENTER, 1),
                transition(ActivityType.IN_VEHICLE, TransitionType.EXIT, 22)
            )
        )
        var resumeAnnounced = false

        val outcome = coordinatorWithLocationFlow(emptyFlow()).runAutoDetection(
            activityEvents = emptyFlow(),
            onCaptureStarted = { resumeAnnounced = true },
            resumeCaptureId = captureId,
            stopProfile = shortStopGrace
        )

        assertTrue(outcome is TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted)
        assertEquals(CaptureStatus.COMPLETED, requireNotNull(db.tripCaptureDao().findById(captureId)).status)
        assertEquals(1L, requireNotNull(db.tripPartDao().findByCaptureId(captureId)).endSequenceNumber)
        assertFalse("a ride that is already over is not announced as being watched again", resumeAnnounced)
    }

    @Test
    fun resumingACaptureThatIsNoLongerActiveDoesNothing() = runTest {
        val captureId = seedAutoCaptureLeftBehindByADeadProcess(points = listOf(10 to 8f, 20 to 8f), transitions = emptyList())
        coordinator.finishCapture(captureId)
        var started = false

        val outcome = coordinatorWithLocationFlow(emptyFlow()).runAutoDetection(
            activityEvents = emptyFlow(),
            onCaptureStarted = { started = true },
            resumeCaptureId = captureId
        )

        assertEquals(TrackingSessionCoordinator.AutoDetectionOutcome.CandidateAbandoned, outcome)
        assertFalse(started)
    }

    /**
     * Runs the resumed monitoring on real threads just long enough for it to say what state it rebuilt, then stops it:
     * these cases have no ending to wait for, and virtual time could jump past a timeout while a Room call is pending.
     */
    private suspend fun theStateARestartRebuiltFor(captureId: String): DiagnosticEventEntity {
        val activityEvents = Channel<ActivityTransitionSample>(Channel.UNLIMITED)
        val run = CoroutineScope(Dispatchers.Default).async {
            coordinatorWithLocationFlow(emptyFlow()).runAutoDetection(activityEvents.receiveAsFlow(), resumeCaptureId = captureId, stopProfile = shortStopGrace)
        }
        try {
            return withTimeout(15_000L) {
                var found: DiagnosticEventEntity? = null
                while (found == null) {
                    found = db.diagnosticEventDao().findAll().firstOrNull { it.eventType == TrackingSessionCoordinator.EVENT_AUTO_STOP_MONITORING_RESUMED }
                    if (found == null) delay(20L)
                }
                found
            }
        } finally {
            run.cancelAndJoin()
            activityEvents.close()
        }
    }

    @Test
    fun aPauseThatIsOpenAtTheRestartMeansNothingIsReplayed() = runBlocking {
        // A pause discards any stop candidate while it lasts; the transitions from before it must not bring one back.
        val captureId = seedAutoCaptureLeftBehindByADeadProcess(
            points = listOf(10 to 8f, 20 to 8f),
            transitions = listOf(
                transition(ActivityType.IN_VEHICLE, TransitionType.ENTER, 1),
                transition(ActivityType.IN_VEHICLE, TransitionType.EXIT, 22)
            )
        )
        coordinator.pauseCapture()

        val event = theStateARestartRebuiltFor(captureId)

        assertEquals("TRACKING", event.reasonCode)
        assertEquals("0", event.metadata["replayedTransitions"])
        assertEquals("0", event.metadata["replayedPoints"])
    }

    @Test
    fun onlyWhatWasRecordedAfterTheLastPauseEndedIsReplayed() = runBlocking {
        val captureId = seedAutoCaptureLeftBehindByADeadProcess(
            points = listOf(10 to 8f, 20 to 8f),
            transitions = listOf(
                transition(ActivityType.IN_VEHICLE, TransitionType.ENTER, 1),
                transition(ActivityType.IN_VEHICLE, TransitionType.EXIT, 22) // before the pause: its candidate was discarded
            )
        )
        // A pause from 25 s to 30 s, closed. Later evidence, recorded later by the wall clock too.
        db.manualPauseIntervalDao().insert(
            ManualPauseIntervalEntity(
                id = "pause-1", captureId = captureId, startedAt = 2_000L, endedAt = 3_000L,
                startElapsedRealtimeNanos = 25_000_000_000L, endElapsedRealtimeNanos = 30_000_000_000L,
                startReason = "USER", endReason = "USER"
            )
        )
        clock.setWallClockMillis(4_000L)
        ActivityTransitionRecorder(db.diagnosticEventDao(), clock, FakeIdGenerator(prefix = "later-transition"))
            .record(transition(ActivityType.IN_VEHICLE, TransitionType.ENTER, 40))
        db.rawTrackPointDao().insert(rawPoint(captureId, 2, 35_000_000_000L, lat = 10.01, speedMps = 8f))
        db.rawTrackPointDao().insert(rawPoint(captureId, 3, 45_000_000_000L, lat = 10.02, speedMps = 8f))

        val event = theStateARestartRebuiltFor(captureId)

        assertEquals("the EXIT from before the pause is not replayed, so no candidate is open", "TRACKING", event.reasonCode)
        assertEquals("1", event.metadata["replayedTransitions"])
        assertEquals("2", event.metadata["replayedPoints"])
    }

    // --- DET-009 (ADR-026): Android also gives a motorcycle the label ON_BICYCLE. Replays of the two real rides of 2026-10-01 ---

    @Test
    fun aRideLabelledOnBicycleFromTheStartIsRecordedAndEndsWhenThatLabelEnds() = runTest {
        // The second real ride (21:02): ON_BICYCLE first, no IN_VEHICLE until the very end. Nothing started it before.
        val activityFlow = timedFlow(
            0L to activitySample(ActivityType.ON_BICYCLE, TransitionType.ENTER, elapsedNanos = 0L),
            100_000L to activitySample(ActivityType.ON_BICYCLE, TransitionType.EXIT, elapsedNanos = 100_000_000_000L),
            101_000L to activitySample(ActivityType.STILL, TransitionType.ENTER, elapsedNanos = 101_000_000_000L)
        )
        val locationFlow = timedFlow(
            1L to sample(elapsedNanos = 1_000_000L),
            20_000L to sample(elapsedNanos = 20_000_000_000L, lat = 10.001, lon = -20.0), // confirms the start
            60_000L to sample(elapsedNanos = 60_000_000_000L, lat = 10.004, lon = -20.0, speedMps = 8f),
            95_000L to sample(elapsedNanos = 95_000_000_000L, lat = 10.007, lon = -20.0, speedMps = 6f),
            110_000L to sample(elapsedNanos = 110_000_000_000L, lat = 10.0071, lon = -20.0, speedMps = 0f),
            140_000L to sample(elapsedNanos = 140_000_000_000L, lat = 10.0071, lon = -20.0, speedMps = 0f) // past the 30 s grace
        )

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(activityFlow, stopProfile = shortStopGrace)

        val tripCompleted = outcome as TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted
        val capture = requireNotNull(db.tripCaptureDao().findById(tripCompleted.captureId))
        assertEquals(StartSource.AUTO, capture.startSource)
        assertEquals(EndSource.AUTO, capture.endSource)
        val events = db.diagnosticEventDao().findAll()
        assertEquals("CONFIRMED_DISPLACEMENT", events.single { it.eventType == TrackingSessionCoordinator.EVENT_CANDIDATE_START_CONFIRMED }.reasonCode)
        assertEquals(
            "the stop candidate says it was the bicycle label that ended",
            "ON_BICYCLE_EXIT",
            events.single { it.eventType == TrackingSessionCoordinator.EVENT_CANDIDATE_STOP_ENTERED }.reasonCode
        )
    }

    @Test
    fun aRideRelabelledAfterFourSecondsIsNotAbandonedAndIsRecorded() = runTest {
        // The first real ride (18:39): IN_VEHICLE for 4 s, then ON_BICYCLE for the rest. The old IN_VEHICLE EXIT
        // abandoned the candidate at 3.5 s; the receiver now hands the new label's ENTER over first.
        val activityFlow = timedFlow(
            0L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L),
            4_000L to activitySample(ActivityType.ON_BICYCLE, TransitionType.ENTER, elapsedNanos = 4_000_000_000L),
            4_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 4_000_000_000L),
            100_000L to activitySample(ActivityType.ON_BICYCLE, TransitionType.EXIT, elapsedNanos = 100_000_000_000L)
        )
        val locationFlow = timedFlow(
            1L to sample(elapsedNanos = 1_000_000L),
            20_000L to sample(elapsedNanos = 20_000_000_000L, lat = 10.001, lon = -20.0), // confirms the start
            60_000L to sample(elapsedNanos = 60_000_000_000L, lat = 10.004, lon = -20.0, speedMps = 8f),
            110_000L to sample(elapsedNanos = 110_000_000_000L, lat = 10.0041, lon = -20.0, speedMps = 0f),
            140_000L to sample(elapsedNanos = 140_000_000_000L, lat = 10.0041, lon = -20.0, speedMps = 0f)
        )

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(activityFlow, stopProfile = shortStopGrace)

        assertTrue("a ride that only changed label was recorded", outcome is TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted)
        val events = db.diagnosticEventDao().findAll()
        assertEquals(1, events.count { it.eventType == TrackingSessionCoordinator.EVENT_CANDIDATE_START_CONFIRMED })
        assertEquals(0, events.count { it.eventType == TrackingSessionCoordinator.EVENT_CANDIDATE_START_REJECTED })
    }

    @Test
    fun aLabelChangeMidRideDoesNotOpenAStopCandidateAndTheRealEndStillDoes() = runTest {
        val activityFlow = timedFlow(
            0L to activitySample(ActivityType.ON_BICYCLE, TransitionType.ENTER, elapsedNanos = 0L),
            // Re-labelled IN_VEHICLE at 50 s: the new label's ENTER first, then the old label's EXIT.
            50_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 50_000_000_000L),
            50_000L to activitySample(ActivityType.ON_BICYCLE, TransitionType.EXIT, elapsedNanos = 50_000_000_000L),
            100_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 100_000_000_000L)
        )
        val locationFlow = timedFlow(
            1L to sample(elapsedNanos = 1_000_000L),
            20_000L to sample(elapsedNanos = 20_000_000_000L, lat = 10.001, lon = -20.0), // confirms the start
            60_000L to sample(elapsedNanos = 60_000_000_000L, lat = 10.004, lon = -20.0, speedMps = 8f),
            110_000L to sample(elapsedNanos = 110_000_000_000L, lat = 10.0041, lon = -20.0, speedMps = 0f),
            140_000L to sample(elapsedNanos = 140_000_000_000L, lat = 10.0041, lon = -20.0, speedMps = 0f)
        )

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(activityFlow, stopProfile = shortStopGrace)

        assertTrue(outcome is TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted)
        val opened = db.diagnosticEventDao().findAll().filter { it.eventType == TrackingSessionCoordinator.EVENT_CANDIDATE_STOP_ENTERED }
        assertEquals("only the real end opened a stop candidate, not the label change", listOf("IN_VEHICLE_EXIT"), opened.map { it.reasonCode })
    }

    @Test
    fun runAutoDetectionDoesNotEndTheTripWhenActivityRecognitionSaysWalkingAtAStopAndTheVehicleMovesOff() = runTest {
        // The field-day failure, end to end: 13 fragments in one ride, each cut the instant Activity Recognition said
        // WALKING at a stop. Here the vehicle stops, AR says WALKING, then the light turns green - and the ride
        // really ends at a second stop later on.
        val locationFlow = timedFlow(
            1L to sample(elapsedNanos = 1_000_000L),
            20_000L to sample(elapsedNanos = 20_000_000_000L, lat = 10.001, lon = -20.0), // confirms the start
            26_000L to sample(elapsedNanos = 26_000_000_000L, lat = 10.001, lon = -20.0, speedMps = 0f),
            // The light turns green: two fixes in a row at vehicle speed.
            34_000L to sample(elapsedNanos = 34_000_000_000L, lat = 10.00105, lon = -20.0, speedMps = 5f),
            36_000L to sample(elapsedNanos = 36_000_000_000L, lat = 10.0012, lon = -20.0, speedMps = 7f),
            // Well past the 30 s grace counted from the first stop (24 s): had that candidate stayed open it would
            // have ended the trip here. It is the second stop (60 s) that ends it, 40 s later.
            60_000L to sample(elapsedNanos = 60_000_000_000L, lat = 10.004, lon = -20.0, speedMps = 0f),
            100_000L to sample(elapsedNanos = 100_000_000_000L, lat = 10.004, lon = -20.0, speedMps = 0f)
        )
        val activityFlowWithSecondStop = timedFlow(
            0L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L),
            24_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 24_000_000_000L),
            25_000L to activitySample(ActivityType.WALKING, TransitionType.ENTER, elapsedNanos = 25_000_000_000L),
            59_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 59_000_000_000L)
        )

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(
            activityEvents = activityFlowWithSecondStop,
            stopProfile = shortStopGrace
        )

        // One logical Trip across the stop at the light: a single capture, ended at the real stop (59 s), not the first (24 s).
        val tripCompleted = outcome as TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted
        assertEquals(1, db.tripCaptureDao().countByStatus(CaptureStatus.COMPLETED))
        val part = requireNotNull(db.tripPartDao().findByCaptureId(tripCompleted.captureId))
        assertEquals("the last point recorded before the second stop (59 s) is the 36 s one", 36_000_000_000L, part.endElapsedRealtimeNanos)

        val events = db.diagnosticEventDao().findAll().sortedBy { it.occurredAt }
        val abandoned = events.single { it.eventType == TrackingSessionCoordinator.EVENT_CANDIDATE_STOP_CANCELLED }
        assertEquals("MOVEMENT_RESUMED", abandoned.reasonCode)
        assertEquals("true", abandoned.metadata["walkingSeen"])
        assertEquals(2, events.count { it.eventType == TrackingSessionCoordinator.EVENT_CANDIDATE_STOP_ENTERED })
        assertEquals("GRACE_PERIOD_ELAPSED", events.single { it.eventType == TrackingSessionCoordinator.EVENT_CANDIDATE_STOP_CONFIRMED }.reasonCode)
    }

    @Test
    fun runAutoDetectionDoesNotTrimWhenThePointsAfterTheStopHaveNoUsableSpeedToSayTheVehicleStayedStopped() = runTest {
        // Poor-accuracy fixes (or any fix without a speed) cannot show that the vehicle stopped, so the points after the
        // candidate opened might be real riding: they stay in the Trip rather than being orphaned by the trim.
        val activityFlow = timedFlow(
            0L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L),
            28_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 28_000_000_000L)
        )
        val locationFlow = timedFlow(
            1L to sample(elapsedNanos = 1_000_000L),
            20_000L to sample(elapsedNanos = 20_000_000_000L, lat = 10.001, lon = -20.0),
            25_000L to sample(elapsedNanos = 25_000_000_000L, lat = 10.001, lon = -20.0),
            29_000L to sample(elapsedNanos = 29_000_000_000L, lat = 10.001, lon = -20.0),
            60_000L to sample(elapsedNanos = 60_000_000_000L, lat = 10.001, lon = -20.0)
        )

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(activityFlow, stopProfile = shortStopGrace)

        val tripCompleted = outcome as TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted
        val part = requireNotNull(db.tripPartDao().findByCaptureId(tripCompleted.captureId))
        // Two fixes seen while the start candidate was validated (1 ms and 20 s) are the capture's first points (DET-010).
        assertEquals("all five recorded points stay in the Trip", 4L, part.endSequenceNumber)
        assertEquals(clock.elapsedRealtimeNanos(), part.endElapsedRealtimeNanos)
    }

    @Test
    fun runAutoDetectionDoesNotTrimWhenTheCandidateAlreadySawAVehicleSpeedFix() = runTest {
        // One spike (not the two in a row that would have cancelled the stop) is still a sign the vehicle may have moved.
        val activityFlow = timedFlow(
            0L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L),
            28_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 28_000_000_000L)
        )
        val locationFlow = timedFlow(
            1L to sample(elapsedNanos = 1_000_000L),
            20_000L to sample(elapsedNanos = 20_000_000_000L, lat = 10.001, lon = -20.0),
            25_000L to sample(elapsedNanos = 25_000_000_000L, lat = 10.001, lon = -20.0, speedMps = 0f),
            29_000L to sample(elapsedNanos = 29_000_000_000L, lat = 10.001, lon = -20.0, speedMps = 5f),
            40_000L to sample(elapsedNanos = 40_000_000_000L, lat = 10.001, lon = -20.0, speedMps = 0f),
            60_000L to sample(elapsedNanos = 60_000_000_000L, lat = 10.001, lon = -20.0, speedMps = 0f)
        )

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(activityFlow, stopProfile = shortStopGrace)

        val tripCompleted = outcome as TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted
        // Six points: the two seen while the start candidate was validated (DET-010), then four live ones.
        assertEquals(5L, requireNotNull(db.tripPartDao().findByCaptureId(tripCompleted.captureId)).endSequenceNumber)
    }

    @Test
    fun aManualPauseDiscardsAnOpenStopCandidateSoResumingDoesNotEndTheTrip() = runBlocking {
        // The stop engine counts grace time in absolute timestamps, so a candidate left open across a pause would confirm
        // on the first fix after Resume - and, with the Trip now trimmed back to where the candidate opened, cut off the
        // riding that follows. Driven through channels and polled on real threads: the order of "candidate opens ->
        // pause -> resume -> first fix" has to be exact, which the virtual-time flows cannot promise across Room calls.
        val activityEvents = Channel<ActivityTransitionSample>(Channel.UNLIMITED)
        val locationFixes = Channel<LocationSample>(Channel.UNLIMITED)
        val run = async(Dispatchers.Default) {
            coordinatorWithLocationFlow(locationFixes.receiveAsFlow()).runAutoDetection(activityEvents.receiveAsFlow(), stopProfile = shortStopGrace)
        }
        suspend fun awaitUntil(what: String, condition: suspend () -> Boolean) {
            withTimeout(15_000L) { while (!condition()) delay(20L) }
        }
        try {
            activityEvents.send(activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L))
            // Two channels merged on real threads have no promised order: a fix overtaking the ENTER would be dropped as
            // "no candidate yet", and the next one would become the anchor with nothing to measure displacement from.
            delay(300L)
            locationFixes.send(sample(elapsedNanos = 1_000_000L))
            delay(300L)
            locationFixes.send(sample(elapsedNanos = 20_000_000_000L, lat = 10.001, lon = -20.0)) // confirms the start
            awaitUntil("the capture to start") { db.tripCaptureDao().countByStatus(CaptureStatus.ACTIVE) == 1 }

            activityEvents.send(activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 25_000_000_000L))
            awaitUntil("the stop candidate to open") {
                db.diagnosticEventDao().findAll().any { it.eventType == TrackingSessionCoordinator.EVENT_CANDIDATE_STOP_ENTERED }
            }

            coordinator.pauseCapture()
            locationFixes.send(sample(elapsedNanos = 30_000_000_000L, lat = 10.001, lon = -20.0, speedMps = 0f)) // arrives while paused
            delay(500L)
            coordinator.resumeCapture()
            // Riding again, 35 s after the candidate opened - past the 30 s grace had the candidate survived the pause.
            locationFixes.send(sample(elapsedNanos = 60_000_000_000L, lat = 10.002, lon = -20.0, speedMps = 8f))
            delay(700L)

            assertEquals("the trip is still being recorded", 1, db.tripCaptureDao().countByStatus(CaptureStatus.ACTIVE))
            assertTrue(
                "no stop was confirmed",
                db.diagnosticEventDao().findAll().none { it.eventType == TrackingSessionCoordinator.EVENT_CANDIDATE_STOP_CONFIRMED }
            )
        } finally {
            run.cancel()
            activityEvents.close()
            locationFixes.close()
        }
    }

    @Test
    fun runAutoDetectionLogsWhyACandidateStartNeverConfirmedSoALostRideCanBeExplained() = runTest {
        // DET-008: four rides in one field day never started and nothing said why.
        val activityFlow = timedFlow(
            0L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L),
            10_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 10_000_000_000L)
        )

        val outcome = coordinatorWithLocationFlow(emptyFlow()).runAutoDetection(activityFlow)

        assertEquals(TrackingSessionCoordinator.AutoDetectionOutcome.CandidateAbandoned, outcome)
        val event = db.diagnosticEventDao().findAll().single { it.eventType == TrackingSessionCoordinator.EVENT_CANDIDATE_START_REJECTED }
        assertEquals(DiagnosticCategory.DETECTOR, event.category)
        assertEquals("IN_VEHICLE_EXIT", event.reasonCode)
        assertEquals("no fix ever arrived", "0", event.metadata["fixCount"])
        assertNull(event.captureId)
    }

    @Test
    fun runAutoDetectionLogsTheEvidenceThatConfirmedTheStartAndTheStop() = runTest {
        val activityFlow = timedFlow(
            0L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L),
            28_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 28_000_000_000L)
        )
        val locationFlow = timedFlow(
            1L to sample(elapsedNanos = 1_000_000L),
            20_000L to sample(elapsedNanos = 20_000_000_000L, lat = 10.001, lon = -20.0),
            60_000L to sample(elapsedNanos = 60_000_000_000L, lat = 10.001, lon = -20.0)
        )

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(activityFlow, stopProfile = shortStopGrace)

        val tripCompleted = outcome as TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted
        val events = db.diagnosticEventDao().findAll()
        val started = events.single { it.eventType == TrackingSessionCoordinator.EVENT_CANDIDATE_START_CONFIRMED }
        assertEquals("CONFIRMED_DISPLACEMENT", started.reasonCode)
        assertEquals(tripCompleted.captureId, started.captureId)
        val opened = events.single { it.eventType == TrackingSessionCoordinator.EVENT_CANDIDATE_STOP_ENTERED }
        assertEquals(tripCompleted.captureId, opened.captureId)
        val stopped = events.single { it.eventType == TrackingSessionCoordinator.EVENT_CANDIDATE_STOP_CONFIRMED }
        assertEquals("GRACE_PERIOD_ELAPSED", stopped.reasonCode)
        assertNotNull(stopped.metadata["elapsedMs"])
        assertTrue("diagnostics carry counts and speeds, never a coordinate", events.none { e -> e.metadata.keys.any { it.contains("lat", true) || it.contains("lon", true) } })
    }

    @Test
    fun runAutoDetectionSurvivesRepeatedCongestionChurnAsOneContinuousAutoTripBeforeFinishingOnARealStop() = runTest {
        // DET-004/F0.3 §18 SCN-006/SCN-026: dense traffic can flap
        // IN_VEHICLE EXIT/ENTER repeatedly while the ride is genuinely still
        // going. No new orchestration behavior is needed for this beyond
        // what DET-002/DET-003/AUTO-001 already do - CandidateStopEngine
        // resets to Tracking on each Abandoned, and runAutoDetection just
        // keeps routing events to it - this test proves that end to end
        // rather than leaving it as an inferred property of the pieces.
        val activityFlow = timedFlow(
            0L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L),
            // Three quick traffic-light-style EXIT/ENTER cycles once tracking.
            28_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 28_000_000_000L),
            29_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 29_000_000_000L),
            33_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 33_000_000_000L),
            34_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 34_000_000_000L),
            38_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 38_000_000_000L),
            39_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 39_000_000_000L),
            // The ride genuinely ends now.
            45_000L to activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 45_000_000_000L),
            47_000L to activitySample(ActivityType.WALKING, TransitionType.ENTER, elapsedNanos = 47_000_000_000L)
        )
        val locationFlow = timedFlow(
            1L to sample(elapsedNanos = 1_000_000L),
            20_000L to sample(elapsedNanos = 20_000_000_000L, lat = 10.001, lon = -20.0), // confirms the start
            25_000L to sample(elapsedNanos = 25_000_000_000L),
            31_000L to sample(elapsedNanos = 31_000_000_000L),
            36_000L to sample(elapsedNanos = 36_000_000_000L),
            41_000L to sample(elapsedNanos = 41_000_000_000L),
            // Past the 30 s grace counted from the real stop at 45 s (DET-008: WALKING no longer ends it on its own).
            80_000L to sample(elapsedNanos = 80_000_000_000L, speedMps = 0f)
        )
        var startedCaptureId: String? = null

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(
            activityEvents = activityFlow,
            onCaptureStarted = { startedCaptureId = it },
            stopProfile = shortStopGrace
        )

        assertTrue(outcome is TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted)
        val tripCompleted = outcome as TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted

        // One continuous capture the whole time - the churn never triggered
        // a second Start, and only one Trip/TripPart was ever created.
        assertEquals(1, db.tripCaptureDao().countByStatus(CaptureStatus.COMPLETED))
        assertEquals(startedCaptureId, tripCompleted.captureId)
        val capture = requireNotNull(db.tripCaptureDao().findById(tripCompleted.captureId))
        assertEquals(StartSource.AUTO, capture.startSource)
        assertEquals(EndSource.AUTO, capture.endSource)

        // Every post-confirmation location sample was persisted without
        // interruption, including the ones that landed between churn cycles.
        val points = db.rawTrackPointDao().findAllByCapture(tripCompleted.captureId)
        assertEquals(
            // The first two were seen while the start candidate was validated (DET-010).
            listOf(1_000_000L, 20_000_000_000L, 25_000_000_000L, 31_000_000_000L, 36_000_000_000L, 41_000_000_000L, 80_000_000_000L),
            points.map { it.elapsedRealtimeNanos }
        )

        // The Trip ends at the real stop (45 s), leaving the 80 s point - recorded while waiting out the grace period - outside it.
        val part = requireNotNull(db.tripPartDao().findByCaptureId(tripCompleted.captureId))
        assertEquals(5L, part.endSequenceNumber)
    }

    // --- TRK-003: pauseCapture/resumeCapture ----------------------------

    @Test
    fun pauseCaptureCreatesAnOpenManualPauseIntervalForTheActiveCapture() = runTest {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId

        val result = coordinator.pauseCapture()

        assertTrue(result is TrackingSessionCoordinator.PauseResult.Paused)
        val paused = result as TrackingSessionCoordinator.PauseResult.Paused
        assertEquals(captureId, paused.captureId)
        val openPause = requireNotNull(db.manualPauseIntervalDao().findOpenByCapture(captureId))
        assertEquals(paused.pauseId, openPause.id)
        assertNull(openPause.endedAt)
    }

    @Test
    fun pauseCaptureWithNoActiveCaptureReturnsNoActiveCapture() = runTest {
        val result = coordinator.pauseCapture()

        assertEquals(TrackingSessionCoordinator.PauseResult.NoActiveCapture, result)
    }

    @Test
    fun pausingAnAlreadyPausedCaptureIsIdempotentAndDoesNotCreateASecondOpenPause() = runTest {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        val first = coordinator.pauseCapture() as TrackingSessionCoordinator.PauseResult.Paused

        val second = coordinator.pauseCapture()

        assertTrue(second is TrackingSessionCoordinator.PauseResult.AlreadyPaused)
        assertEquals(first.pauseId, (second as TrackingSessionCoordinator.PauseResult.AlreadyPaused).pauseId)
        assertEquals(1, db.manualPauseIntervalDao().findAllByCapture(captureId).size)
    }

    @Test
    fun resumeCaptureClosesTheOpenPauseInterval() = runTest {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        val paused = coordinator.pauseCapture() as TrackingSessionCoordinator.PauseResult.Paused

        val result = coordinator.resumeCapture()

        assertEquals(TrackingSessionCoordinator.ResumeResult.Resumed(captureId), result)
        assertNull(db.manualPauseIntervalDao().findOpenByCapture(captureId))
        val closed = db.manualPauseIntervalDao().findAllByCapture(captureId).single { it.id == paused.pauseId }
        assertNotNull(closed.endedAt)
    }

    @Test
    fun resumeCaptureWithNoActiveCaptureReturnsNoActiveCapture() = runTest {
        val result = coordinator.resumeCapture()

        assertEquals(TrackingSessionCoordinator.ResumeResult.NoActiveCapture, result)
    }

    @Test
    fun resumingWhenNotCurrentlyPausedIsIdempotent() = runTest {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId

        val result = coordinator.resumeCapture()

        assertEquals(TrackingSessionCoordinator.ResumeResult.AlreadyResumed(captureId), result)
    }

    @Test
    fun finishCaptureClosesAnOpenPauseAsPartOfTheSameTransaction() = runTest {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        coordinator.pauseCapture()

        coordinator.finishCapture(captureId)

        val pause = db.manualPauseIntervalDao().findAllByCapture(captureId).single()
        assertNotNull("Finish must close any still-open pause (F0.3 SS8: 'Finish is available while paused')", pause.endedAt)
        assertEquals("CAPTURE_FINISHED", pause.endReason)
    }

    @Test
    fun recordLocationUpdatesSkipsPersistingSamplesWhileAnOpenPauseExists() = runTest {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        coordinator.pauseCapture()

        coordinatorWith(listOf(sample(1_000L), sample(2_000L))).recordLocationUpdates(captureId)

        assertEquals(0, db.rawTrackPointDao().countByCapture(captureId))

        coordinator.resumeCapture()
        coordinatorWith(listOf(sample(3_000L))).recordLocationUpdates(captureId)

        assertEquals(1, db.rawTrackPointDao().countByCapture(captureId))
    }

    @Test
    fun recordLocationUpdatesSkipsOnlyTheSamplesThatArriveWhileGenuinelyPaused() = runTest {
        // A racing, separately-`launch`ed coroutine timing Pause/Resume via
        // `delay()` against this flow was tried first and was genuinely
        // flaky: Room's suspend DAO calls run on their own real executor,
        // not `runTest`'s virtual dispatcher, so a concurrent coroutine's
        // virtual-time delays don't reliably happen-before its Room writes
        // relative to this flow's own progress. Sequencing the pause/resume
        // calls *inside* the flow itself sidesteps that entirely: `emit`
        // doesn't return until the collector has finished with that item, so
        // everything here is strictly ordered with no race at all.
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        val locationFlow = flow {
            emit(sample(elapsedNanos = 1_000L))
            coordinator.pauseCapture()
            emit(sample(elapsedNanos = 2_000L)) // lands inside the pause window
            emit(sample(elapsedNanos = 3_000L)) // lands inside the pause window
            coordinator.resumeCapture()
            emit(sample(elapsedNanos = 4_000L))
        }

        coordinatorWithLocationFlow(locationFlow).recordLocationUpdates(captureId)

        val points = db.rawTrackPointDao().findAllByCapture(captureId)
        assertEquals(listOf(1_000L, 4_000L), points.map { it.elapsedRealtimeNanos })
    }

    @Test
    fun runAutoDetectionDoesNotPersistOrAutoFinishWhileManuallyPaused() = runTest {
        // TRK-003/F0.3 SS8: "automatic stop detection should not silently
        // close a manually paused Trip." The EXIT/WALKING pair below is
        // emitted only after Pause is already durably applied, so it must be
        // completely ignored; only the real stop emitted after Resume (and
        // the grace period that follows it) actually finishes the trip.
        // `confirmed` makes the activity flow wait for the location-driven
        // confirm (and this test's own Pause call inside `onCaptureStarted`)
        // before emitting the in-pause events - the same in-flow-sequencing
        // fix as the `recordLocationUpdates` test above, needed here because
        // two separate flows (activity/location) are involved. DET-008: the
        // stop is now confirmed by the grace period elapsing, not by the
        // WALKING event itself - driven here by advancing the test clock,
        // which the detection ticker reads.
        val confirmed = CompletableDeferred<Unit>()
        val activityFlow = flow {
            emit(activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L))
            confirmed.await()
            emit(activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 25_000_000_000L))
            emit(activitySample(ActivityType.WALKING, TransitionType.ENTER, elapsedNanos = 26_000_000_000L))
            coordinator.resumeCapture()
            emit(activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 40_000_000_000L))
            emit(activitySample(ActivityType.WALKING, TransitionType.ENTER, elapsedNanos = 41_000_000_000L))
            // Time passes: the next tick of the (always running) detection ticker now reads far past the 30 s grace
            // counted from the post-Resume stop at 40 s, which is what confirms it - no sample ordering to race.
            clock.setElapsedRealtimeNanos(100_000_000_000L)
        }
        val locationFlow = flowOf(
            sample(elapsedNanos = 1_000_000L),
            sample(elapsedNanos = 20_000_000_000L, lat = 10.001, lon = -20.0), // confirms the start
            sample(elapsedNanos = 30_000_000_000L) // arrives once paused - must not persist
        )
        var startedCaptureId: String? = null

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(
            activityEvents = activityFlow,
            onCaptureStarted = {
                startedCaptureId = it
                coordinator.pauseCapture()
                confirmed.complete(Unit)
            },
            stopProfile = shortStopGrace
        )

        assertTrue(outcome is TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted)
        val tripCompleted = outcome as TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted
        assertEquals(startedCaptureId, tripCompleted.captureId)

        val points = db.rawTrackPointDao().findAllByCapture(tripCompleted.captureId)
        assertTrue(
            "the sample arriving during the pause must not be persisted",
            points.none { it.elapsedRealtimeNanos == 30_000_000_000L }
        )
    }

    // --- DET-005: forgotten-pause warning --------------------------------

    @Test
    fun recordLocationUpdatesWarnsOnSustainedMovementWhilePausedWithoutResumingOrPersisting() = runTest {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        coordinator.pauseCapture()
        var warned = false

        coordinatorWith(
            listOf(
                sample(elapsedNanos = 0L), // anchors the forgotten-pause watch
                sample(elapsedNanos = 61_000_000_000L, lat = 10.001) // ~111m, 61s later - warns
            )
        ).recordLocationUpdates(captureId, onForgottenPauseWarning = { warned = true })

        assertTrue("expected a forgotten-pause warning", warned)
        assertEquals(0, db.rawTrackPointDao().countByCapture(captureId))
        assertNotNull(
            "manual ownership must remain authoritative - a warning must never auto-resume",
            db.manualPauseIntervalDao().findOpenByCapture(captureId)
        )
        val warningEvent = db.diagnosticEventDao().findAll().single { it.eventType == "FORGOTTEN_PAUSE_WARNING" }
        assertEquals(DiagnosticCategory.DETECTOR, warningEvent.category)
        assertEquals(DiagnosticSeverity.WARN, warningEvent.severity)
    }

    @Test
    fun recordLocationUpdatesDoesNotWarnForOrdinarySmallMovementWhilePaused() = runTest {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        coordinator.pauseCapture()
        var warned = false

        coordinatorWith(
            listOf(
                sample(elapsedNanos = 0L),
                sample(elapsedNanos = 61_000_000_000L, lat = 10.00001) // ~1m - well under the displacement threshold
            )
        ).recordLocationUpdates(captureId, onForgottenPauseWarning = { warned = true })

        assertFalse("ordinary walking-around-at-a-stop movement must not trigger a warning", warned)
    }

    @Test
    fun runAutoDetectionWarnsOnSustainedMovementWhilePausedWithoutAutoFinishing() = runTest {
        val confirmed = CompletableDeferred<Unit>()
        val warningFired = CompletableDeferred<Unit>()
        val resumed = CompletableDeferred<Unit>()
        var warned = false
        // Every step that logically depends on an *earlier* step having
        // actually been processed by the consumer (not just emitted by some
        // producer) needs its own explicit gate here: a merged flow's
        // upstream sources are independently buffered, so a producer can
        // race arbitrarily far ahead of what the collector has consumed so
        // far. Two real races were caught and fixed writing this test, not
        // just one - `resumeCapture()` first raced ahead of `pauseCapture()`
        // (fixed by gating the location flow's post-confirm emissions on
        // `confirmed`), then, after that fix, it raced ahead of the 25s/90s
        // samples actually being consumed and warned about (fixed by adding
        // `warningFired`, completed from `onForgottenPauseWarning` itself -
        // a genuine consumer-side event, not a guess at timing).
        val activityFlow = flow {
            emit(activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L))
            confirmed.await()
            resumed.await()
            emit(activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 100_000_000_000L))
            emit(activitySample(ActivityType.WALKING, TransitionType.ENTER, elapsedNanos = 101_000_000_000L))
            // The detection ticker now reads far past the 30 s grace counted from the post-Resume stop at 100 s.
            clock.setElapsedRealtimeNanos(200_000_000_000L)
        }
        val locationFlow = flow {
            emit(sample(elapsedNanos = 1_000_000L))
            emit(sample(elapsedNanos = 20_000_000_000L, lat = 10.001, lon = -20.0)) // confirms the start
            confirmed.await()
            emit(sample(elapsedNanos = 25_000_000_000L)) // anchors the forgotten-pause watch
            emit(sample(elapsedNanos = 90_000_000_000L, lat = 10.002, lon = -20.0)) // ~222m/65s later - warns
            warningFired.await()
            coordinator.resumeCapture()
            resumed.complete(Unit)
        }
        var startedCaptureId: String? = null

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(
            activityEvents = activityFlow,
            onCaptureStarted = {
                startedCaptureId = it
                coordinator.pauseCapture()
                confirmed.complete(Unit)
            },
            onForgottenPauseWarning = {
                warned = true
                warningFired.complete(Unit)
            },
            stopProfile = shortStopGrace
        )

        assertTrue("expected a forgotten-pause warning", warned)
        assertTrue(outcome is TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted)
        val tripCompleted = outcome as TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted
        assertEquals(startedCaptureId, tripCompleted.captureId)
        // The warning itself must never have auto-finished the trip - only
        // the real EXIT/WALKING pair emitted after Resume did.
        assertEquals(EndSource.AUTO, requireNotNull(db.tripCaptureDao().findById(tripCompleted.captureId)).endSource)
    }

    // --- DET-007: forgotten-finish warning -------------------------------

    @Test
    fun recordLocationUpdatesWarnsOnSustainedStationaryPeriodWhileActiveAndKeepsPersistingNormally() = runTest {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        var warned = false

        coordinatorWith(
            listOf(
                sample(elapsedNanos = 0L), // anchors the forgotten-finish watch
                sample(elapsedNanos = 601_000_000_000L) // same spot, 601s later - past the default 600s bar
            )
        ).recordLocationUpdates(captureId, onForgottenFinishWarning = { warned = true })

        assertTrue("expected a forgotten-finish warning", warned)
        // Unlike the paused case (DET-005), this capture was never paused -
        // both samples must still be persisted normally; a warning is a
        // nudge, never a reason to stop recording (DP-005).
        assertEquals(2, db.rawTrackPointDao().countByCapture(captureId))
        assertEquals(CaptureStatus.ACTIVE, requireNotNull(db.tripCaptureDao().findById(captureId)).status)
        val warningEvent = db.diagnosticEventDao().findAll().single { it.eventType == "FORGOTTEN_FINISH_WARNING" }
        assertEquals(DiagnosticCategory.DETECTOR, warningEvent.category)
        assertEquals(DiagnosticSeverity.WARN, warningEvent.severity)
    }

    @Test
    fun recordLocationUpdatesDoesNotWarnWhileTheRiderKeepsMoving() = runTest {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        var warned = false

        coordinatorWith(
            listOf(
                sample(elapsedNanos = 0L, lat = 10.0),
                sample(elapsedNanos = 300_000_000_000L, lat = 10.001), // ~111m - real movement, re-anchors
                sample(elapsedNanos = 900_000_000_000L, lat = 10.002) // another ~111m - re-anchors again
            )
        ).recordLocationUpdates(captureId, onForgottenFinishWarning = { warned = true })

        assertFalse("continuous real movement must never look like a forgotten Finish", warned)
    }

    @Test
    fun recordLocationUpdatesDoesNotCarryTheStationaryAnchorAcrossAPause() = runTest {
        // If a Pause didn't discard this engine's anchor, the first sample
        // after Resume would inherit an anchor age spanning the entire
        // pause - the same real bug class TRK-003/DET-005 already fixed
        // elsewhere in this file (a producer/consumer detail this test now
        // guards for this engine specifically), just expressed as a stale
        // timestamp bug rather than a race.
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        var warned = false
        val locationFlow = flow {
            emit(sample(elapsedNanos = 0L)) // anchors the finish watch pre-pause
            coordinator.pauseCapture()
            emit(sample(elapsedNanos = 650_000_000_000L)) // 650s later, but paused - must not reach the finish watch as a normal sample
            coordinator.resumeCapture()
            emit(sample(elapsedNanos = 655_000_000_000L)) // only 5s after the real reset, same spot - must not misfire
        }

        coordinatorWithLocationFlow(locationFlow).recordLocationUpdates(captureId, onForgottenFinishWarning = { warned = true })

        assertFalse(
            "a pause must discard the pre-pause stationary anchor, not let its age carry across the gap",
            warned
        )
    }

    // --- NOT-001: currentTrackingSnapshot --------------------------------

    @Test
    fun currentTrackingSnapshotReturnsNullForAMissingCapture() = runTest {
        assertNull(coordinator.currentTrackingSnapshot("does-not-exist"))
    }

    @Test
    fun currentTrackingSnapshotReportsLiveDistanceAndElapsedTimeWhileNotPaused() = runTest {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        coordinatorWith(
            listOf(sample(elapsedNanos = 0L, lat = 10.0), sample(elapsedNanos = 1_000_000_000L, lat = 10.001))
        ).recordLocationUpdates(captureId)
        clock.advanceMillis(5_000L)

        val snapshot = coordinator.currentTrackingSnapshot(captureId)

        assertNotNull(snapshot)
        assertTrue("expected nonzero distance from the two recorded points", snapshot!!.distanceMeters > 0.0)
        assertFalse(snapshot.isPaused)
        assertEquals(5_000L, snapshot.elapsedMs)
    }

    @Test
    fun currentTrackingSnapshotReflectsAnOpenPause() = runTest {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId

        coordinator.pauseCapture()

        val snapshot = coordinator.currentTrackingSnapshot(captureId)

        assertNotNull(snapshot)
        assertTrue(snapshot!!.isPaused)
    }

    // --- REC-003: sealing an orphaned capture after a reboot ------------

    private suspend fun startWithPoints(vararg elapsed: Long): String {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        coordinatorWith(elapsed.map { sample(elapsedNanos = it, lat = 10.0 + it * 0.000001) }).recordLocationUpdates(captureId)
        return captureId
    }

    @Test
    fun bootCompletedSealsAnActiveCaptureAndGivesItAVisiblePartialTrip() = runTest {
        val captureId = startWithPoints(2_000L, 3_000L, 4_000L)

        val outcome = coordinator.sealActiveCaptureAfterBoot() as TrackingSessionCoordinator.ReconcileOutcome.SealedAfterReboot

        val capture = requireNotNull(db.tripCaptureDao().findById(captureId))
        assertEquals(CaptureStatus.ABORTED, capture.status)
        assertEquals(EndSource.RECOVERY, capture.endSource)
        assertEquals("the last raw point's own time, never an invented now", 4_000L, capture.endElapsedRealtimeNanos)
        val tripId = requireNotNull(outcome.partialTripId)
        assertEquals(com.mototriptracker.app.core.model.TripStatus.COMPLETED, requireNotNull(db.tripDao().findById(tripId)).status)
        val part = db.tripPartDao().findAllByTrip(tripId).single()
        assertEquals(captureId, part.captureId)
        assertEquals(4_000L, part.endElapsedRealtimeNanos)
        assertEquals(0L, part.startSequenceNumber)
        assertEquals(2L, part.endSequenceNumber)
        assertEquals(3, db.rawTrackPointDao().countByCapture(captureId))
        assertEquals(listOf(tripId), processingScheduler.enqueuedRequests.map { it.tripId })
        assertNull("nothing is ACTIVE any more, so a new Start is possible again (ADR-020)", coordinator.findActiveCapture())
    }

    @Test
    fun bootCompletedSealsEvenWhenElapsedTimeAloneWouldCallItTheSameBoot() = runTest {
        val captureId = startWithPoints(2_000L, 3_000L)
        // The fresh boot's clock (5_000) is already past the capture's recorded
        // start (1_000): the elapsedRealtime heuristic alone says "same boot".
        clock.setElapsedRealtimeNanos(5_000L)
        assertEquals(TrackingSessionCoordinator.ReconcileOutcome.NothingToReconcile, coordinator.reconcileActiveCaptureAfterReboot())

        coordinator.sealActiveCaptureAfterBoot()

        assertEquals("BOOT_COMPLETED knows a reboot happened", CaptureStatus.ABORTED, db.tripCaptureDao().findById(captureId)?.status)
    }

    @Test
    fun aSealedCaptureWithFewerThanTwoPointsGetsNoTripButKeepsItsEvidence() = runTest {
        val captureId = startWithPoints(2_000L)

        val outcome = coordinator.sealActiveCaptureAfterBoot() as TrackingSessionCoordinator.ReconcileOutcome.SealedAfterReboot

        assertNull(outcome.partialTripId)
        assertEquals(CaptureStatus.ABORTED, db.tripCaptureDao().findById(captureId)?.status)
        assertEquals(1, db.rawTrackPointDao().countByCapture(captureId))
        assertTrue(processingScheduler.enqueuedRequests.isEmpty())
    }

    @Test
    fun anOpenManualPauseIsClosedAtTheLastEvidenceSoTheTripsMetricsCanBeComputed() = runTest {
        val captureId = startWithPoints(2_000L, 3_000L, 4_000L)
        clock.setElapsedRealtimeNanos(3_500L)
        coordinator.pauseCapture()

        coordinator.sealActiveCaptureAfterBoot()

        val pause = db.manualPauseIntervalDao().findAllByCapture(captureId).single()
        assertEquals("CAPTURE_INTERRUPTED", pause.endReason)
        assertNotNull("TripMetricsCalculator rejects an open pause", pause.endElapsedRealtimeNanos)
        assertTrue("never closed before it started", pause.endElapsedRealtimeNanos!! >= pause.startElapsedRealtimeNanos)
    }

    @Test
    fun sealingIsIdempotentAndDoesNothingWhenNoCaptureIsActive() = runTest {
        assertEquals(TrackingSessionCoordinator.ReconcileOutcome.NothingToReconcile, coordinator.sealActiveCaptureAfterBoot())
        startWithPoints(2_000L, 3_000L)
        coordinator.sealActiveCaptureAfterBoot()

        assertEquals(TrackingSessionCoordinator.ReconcileOutcome.NothingToReconcile, coordinator.sealActiveCaptureAfterBoot())
        assertEquals(1, processingScheduler.enqueuedRequests.size)
    }

    @Test
    fun appStartReconciliationSealsOnlyOnTheDefinitiveDiscontinuityAndLeavesASameBootCaptureAlone() = runTest {
        val captureId = startWithPoints(2_000L, 3_000L)
        assertEquals(TrackingSessionCoordinator.ReconcileOutcome.NothingToReconcile, coordinator.reconcileActiveCaptureAfterReboot())
        assertEquals(CaptureStatus.ACTIVE, db.tripCaptureDao().findById(captureId)?.status)

        clock.setElapsedRealtimeNanos(500L) // a real reboot: the clock domain restarted

        assertTrue(coordinator.reconcileActiveCaptureAfterReboot() is TrackingSessionCoordinator.ReconcileOutcome.SealedAfterReboot)
        assertEquals(CaptureStatus.ABORTED, db.tripCaptureDao().findById(captureId)?.status)
    }

    @Test
    fun theRebootBranchOfServiceRecoveryAlsoGivesTheInterruptedRideAVisibleTrip() = runTest {
        val captureId = startWithPoints(2_000L, 3_000L)
        clock.setElapsedRealtimeNanos(500L)

        coordinator.recoverActiveCaptureIfAny()

        assertNotNull(db.tripPartDao().findByCaptureId(captureId))
    }

    // --- REC-001: recoverActiveCaptureIfAny -------------------------------

    @Test
    fun recoverActiveCaptureIfAnyReturnsNoActiveCaptureWhenNothingIsActive() = runTest {
        val outcome = coordinator.recoverActiveCaptureIfAny()

        assertEquals(TrackingSessionCoordinator.RecoveryOutcome.NoActiveCapture, outcome)
    }

    @Test
    fun recoverActiveCaptureIfAnyResumesTheSameCaptureOnAGenuineSameBootProcessDeath() = runTest {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        clock.advanceMillis(5_000L) // real time passing, same boot - the clock only ever moves forward

        val outcome = coordinator.recoverActiveCaptureIfAny()

        assertEquals(TrackingSessionCoordinator.RecoveryOutcome.Resumed(captureId), outcome)
        assertEquals(CaptureStatus.ACTIVE, db.tripCaptureDao().findById(captureId)?.status)
        val event = db.diagnosticEventDao().findAll().single { it.eventType == "PROCESS_RECOVERED" }
        assertEquals(DiagnosticCategory.RECOVERY_SYSTEM, event.category)
        assertEquals(captureId, event.captureId)
    }

    @Test
    fun recoverActiveCaptureIfAnyAbortsUsingTheLastRawPointWhenARebootIsDetected() = runTest {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        coordinatorWith(
            listOf(sample(elapsedNanos = 2_000L, lat = 10.0), sample(elapsedNanos = 3_000L, lat = 10.001))
        ).recordLocationUpdates(captureId)
        // A real reboot: the new boot session's elapsedRealtimeNanos starts
        // small again, definitely less than this capture's own recorded
        // start (the class-level clock's initial 1_000L).
        clock.setElapsedRealtimeNanos(500L)

        val outcome = coordinator.recoverActiveCaptureIfAny()

        assertEquals(TrackingSessionCoordinator.RecoveryOutcome.AbortedAfterReboot(captureId), outcome)
        val capture = db.tripCaptureDao().findById(captureId)
        assertEquals(CaptureStatus.ABORTED, capture?.status)
        assertEquals(EndSource.RECOVERY, capture?.endSource)
        assertEquals(3_000L, capture?.endElapsedRealtimeNanos) // the last raw point's own elapsedRealtimeNanos, not an invented "now"
        assertEquals(
            "raw evidence must never be deleted, even for an aborted capture",
            2,
            db.rawTrackPointDao().countByCapture(captureId)
        )
        val event = db.diagnosticEventDao().findAll().single { it.eventType == "CAPTURE_ABORTED_AFTER_REBOOT" }
        assertEquals(DiagnosticSeverity.WARN, event.severity)
    }

    @Test
    fun recoverActiveCaptureIfAnyFallsBackToTheCaptureStartWhenNoRawPointWasEverRecorded() = runTest {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        val started = requireNotNull(db.tripCaptureDao().findById(captureId))
        clock.setElapsedRealtimeNanos(0L) // rebooted before any location fix ever arrived

        coordinator.recoverActiveCaptureIfAny()

        val aborted = db.tripCaptureDao().findById(captureId)
        assertEquals(CaptureStatus.ABORTED, aborted?.status)
        assertEquals(started.startedAt, aborted?.endedAt)
        assertEquals(started.startElapsedRealtimeNanos, aborted?.endElapsedRealtimeNanos)
    }

    @Test
    fun recoverActiveCaptureIfAnyNeverResumesLocationRecordingAfterARebootAbort() = runTest {
        // A regression guard for the exact bug F0.10 SS10.2 warns about: an
        // aborted-after-reboot capture must stay ABORTED, not flip back to
        // ACTIVE just because something later calls findByStatus(ACTIVE).
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        clock.setElapsedRealtimeNanos(0L)

        coordinator.recoverActiveCaptureIfAny()

        assertEquals(null, coordinator.findActiveCapture())
        assertEquals(captureId, db.tripCaptureDao().findMostRecentlyEnded()?.id)
    }
}
