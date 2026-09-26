package com.mototriptracker.app.tracking.recovery

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.testing.FakeLocationGateway
import com.mototriptracker.app.testing.FakeProcessingScheduler
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * REC-003: BOOT_COMPLETED is only a trigger. Verified on a real Android 16
 * phone, the system also delivers it to an app relaunched after a Force stop
 * (uptime 20+ minutes) - so a capture is sealed as "after a reboot" only when
 * the platform's boot count actually changed.
 */
@RunWith(RobolectricTestRunner::class)
class RebootReconcilerTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var coordinator: TrackingSessionCoordinator
    private lateinit var bootCount: FakeBootCountReader
    private lateinit var store: FakeHandledExitStore
    private lateinit var reconciler: RebootReconciler

    private fun coordinatorWith(samples: List<LocationSample>) = TrackingSessionCoordinator(
        database = db, tripCaptureDao = db.tripCaptureDao(), diagnosticEventDao = db.diagnosticEventDao(),
        rawTrackPointDao = db.rawTrackPointDao(), captureEventDao = db.captureEventDao(), tripDao = db.tripDao(),
        tripPartDao = db.tripPartDao(), manualPauseIntervalDao = db.manualPauseIntervalDao(),
        locationGateway = FakeLocationGateway(samples), processingScheduler = FakeProcessingScheduler(),
        clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L), idGenerator = FakeIdGenerator(prefix = "capture")
    )

    private fun sample(elapsedNanos: Long) = LocationSample(
        wallTimeEpochMs = 2_000L, elapsedRealtimeNanos = elapsedNanos, receivedAtElapsedRealtimeNanos = elapsedNanos,
        latitude = 10.0 + elapsedNanos * 0.000001, longitude = -20.0, horizontalAccuracyM = 5.0f, requestProfileId = "test-profile"
    )

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        coordinator = coordinatorWith(emptyList())
        bootCount = FakeBootCountReader(7)
        store = FakeHandledExitStore(bootCount = 7)
        reconciler = RebootReconciler(bootCount, store, coordinator)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun activeCaptureWithPoints(): String {
        val id = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        coordinatorWith(listOf(sample(2_000L), sample(3_000L))).recordLocationUpdates(id)
        return id
    }

    @Test
    fun aBootCompletedWithTheSameBootCountIsNotARebootAndSealsNothing() = runTest {
        val captureId = activeCaptureWithPoints()

        // The Force-stop relaunch case: BOOT_COMPLETED arrives, the boot count is unchanged.
        assertEquals(TrackingSessionCoordinator.ReconcileOutcome.NothingToReconcile, reconciler.reconcile())

        assertEquals(CaptureStatus.ACTIVE, db.tripCaptureDao().findById(captureId)?.status)
    }

    @Test
    fun aChangedBootCountIsARealRebootAndSealsTheOrphanedCapture() = runTest {
        val captureId = activeCaptureWithPoints()
        bootCount.count = 8

        assertTrue(reconciler.reconcile() is TrackingSessionCoordinator.ReconcileOutcome.SealedAfterReboot)

        assertEquals(CaptureStatus.ABORTED, db.tripCaptureDao().findById(captureId)?.status)
        assertEquals("the new boot is remembered so this is not repeated", 8, store.bootCount)
    }

    @Test
    fun theFirstRunWithNothingRecordedYetCannotTellSoItSealsNothingButRemembers() = runTest {
        val captureId = activeCaptureWithPoints()
        store.bootCount = null

        assertEquals(TrackingSessionCoordinator.ReconcileOutcome.NothingToReconcile, reconciler.reconcile())

        assertEquals(CaptureStatus.ACTIVE, db.tripCaptureDao().findById(captureId)?.status)
        assertEquals(7, store.bootCount)
    }

    @Test
    fun aPlatformThatDoesNotExposeTheBootCountChangesNothing() = runTest {
        val captureId = activeCaptureWithPoints()
        bootCount.count = null

        assertEquals(TrackingSessionCoordinator.ReconcileOutcome.NothingToReconcile, reconciler.reconcile())

        assertEquals(CaptureStatus.ACTIVE, db.tripCaptureDao().findById(captureId)?.status)
        assertEquals(7, store.bootCount)
    }

    @Test
    fun aRealRebootIsActedOnOnlyOnce() = runTest {
        activeCaptureWithPoints()
        bootCount.count = 8
        reconciler.reconcile()

        assertEquals(TrackingSessionCoordinator.ReconcileOutcome.NothingToReconcile, reconciler.reconcile())
    }
}
