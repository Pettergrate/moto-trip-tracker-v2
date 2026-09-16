package com.mototriptracker.app.tracking.coordinator

import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorState
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.testing.FakeLocationGateway
import com.mototriptracker.app.testing.TestDatabaseFactory
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

    private fun coordinatorWith(samples: List<LocationSample>) = TrackingSessionCoordinator(
        tripCaptureDao = db.tripCaptureDao(),
        diagnosticEventDao = db.diagnosticEventDao(),
        rawTrackPointDao = db.rawTrackPointDao(),
        locationGateway = FakeLocationGateway(samples),
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
}
