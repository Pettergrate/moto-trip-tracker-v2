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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
            41_000L to sample(elapsedNanos = 41_000_000_000L)
        )
        var startedCaptureId: String? = null

        val outcome = coordinatorWithLocationFlow(locationFlow).runAutoDetection(
            activityEvents = activityFlow,
            onCaptureStarted = { startedCaptureId = it }
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
            listOf(25_000_000_000L, 31_000_000_000L, 36_000_000_000L, 41_000_000_000L),
            points.map { it.elapsedRealtimeNanos }
        )
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
        // close a manually paused Trip." The EXIT/WALKING pair below would
        // normally auto-finish immediately (walking-away confirms with no
        // grace period) - since it's emitted only after Pause is already
        // durably applied, it must be completely ignored; only the real stop
        // emitted after Resume actually finishes the trip. `confirmed` makes
        // the activity flow wait for the location-driven confirm (and this
        // test's own Pause call inside `onCaptureStarted`) before emitting
        // the in-pause events - the same in-flow-sequencing fix as the
        // `recordLocationUpdates` test above, needed here because two
        // separate flows (activity/location) are involved.
        val confirmed = CompletableDeferred<Unit>()
        val activityFlow = flow {
            emit(activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L))
            confirmed.await()
            emit(activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 25_000_000_000L))
            emit(activitySample(ActivityType.WALKING, TransitionType.ENTER, elapsedNanos = 26_000_000_000L))
            coordinator.resumeCapture()
            emit(activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 40_000_000_000L))
            emit(activitySample(ActivityType.WALKING, TransitionType.ENTER, elapsedNanos = 41_000_000_000L))
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
            }
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
            }
        )

        assertTrue("expected a forgotten-pause warning", warned)
        assertTrue(outcome is TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted)
        val tripCompleted = outcome as TrackingSessionCoordinator.AutoDetectionOutcome.TripCompleted
        assertEquals(startedCaptureId, tripCompleted.captureId)
        // The warning itself must never have auto-finished the trip - only
        // the real EXIT/WALKING pair emitted after Resume did.
        assertEquals(EndSource.AUTO, requireNotNull(db.tripCaptureDao().findById(tripCompleted.captureId)).endSource)
    }
}
