package com.mototriptracker.app.tracking.coordinator

import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.testing.TestDatabaseFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TRK-001 acceptance: "one active capture only; repeated Start is
 * idempotent; service rehydrates state from Room." The service itself
 * (`TrackingForegroundServiceTest`) proves it delegates correctly; this
 * proves the actual decision logic, independent of any Android Service.
 */
@RunWith(RobolectricTestRunner::class)
class TrackingSessionCoordinatorTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var coordinator: TrackingSessionCoordinator

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        coordinator = TrackingSessionCoordinator(
            tripCaptureDao = db.tripCaptureDao(),
            diagnosticEventDao = db.diagnosticEventDao(),
            clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L),
            idGenerator = FakeIdGenerator(prefix = "capture")
        )
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
        assertEquals(1, db.tripCaptureDao().countByStatus(com.mototriptracker.app.core.model.CaptureStatus.ACTIVE))
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
}
