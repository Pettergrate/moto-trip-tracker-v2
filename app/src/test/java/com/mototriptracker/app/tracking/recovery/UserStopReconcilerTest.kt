package com.mototriptracker.app.tracking.recovery

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.EndSource
import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.testing.FakeLocationGateway
import com.mototriptracker.app.testing.FakeProcessingScheduler
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * REC-004/F0.10 §9: after the user stops the app (Task Manager Stop, Force
 * stop) a capture still ACTIVE is sealed as an interrupted, visible partial
 * Trip instead of being blindly revived; anything else (a crash, an OOM kill,
 * no information at all) is left to the sticky service exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
class UserStopReconcilerTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var coordinator: TrackingSessionCoordinator
    private lateinit var reader: FakeExitReader
    private lateinit var store: FakeHandledExitStore
    private lateinit var reconciler: UserStopReconciler
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
        clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L),
        idGenerator = FakeIdGenerator(prefix = "capture")
    )

    private fun sample(elapsedNanos: Long) = LocationSample(
        wallTimeEpochMs = 2_000L, elapsedRealtimeNanos = elapsedNanos, receivedAtElapsedRealtimeNanos = elapsedNanos,
        latitude = 10.0 + elapsedNanos * 0.000001, longitude = -20.0, horizontalAccuracyM = 5.0f, requestProfileId = "test-profile"
    )

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        coordinator = coordinatorWith(emptyList())
        reader = FakeExitReader(null)
        store = FakeHandledExitStore()
        reconciler = UserStopReconciler(reader, store, coordinator)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun startWithPoints(vararg elapsed: Long): String {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        coordinatorWith(elapsed.map { sample(it) }).recordLocationUpdates(captureId)
        return captureId
    }

    @Test
    fun aUserStopSealsTheActiveCaptureAsAVisibleInterruptedTripInsteadOfReviving() = runTest {
        val captureId = startWithPoints(2_000L, 3_000L, 4_000L)
        reader.exit = ProcessExit(timestampMillis = 500L, wasUserRequested = true)

        val outcome = reconciler.reconcile() as UserStopReconciler.Outcome.Sealed

        val capture = requireNotNull(db.tripCaptureDao().findById(captureId))
        assertEquals(CaptureStatus.ABORTED, capture.status)
        assertEquals(EndSource.RECOVERY, capture.endSource)
        assertEquals("last persisted evidence, never an invented now", 4_000L, capture.endElapsedRealtimeNanos)
        assertNotNull("the interrupted ride stays visible (F0.10 §22)", outcome.partialTripId)
        assertEquals(3, db.rawTrackPointDao().countByCapture(captureId))
        assertNull("nothing ACTIVE any more - the user's own next Start is unblocked", coordinator.findActiveCapture())
        assertEquals(500L, store.handled)
        val event = db.diagnosticEventDao().findAll().single { it.eventType == TrackingSessionCoordinator.EVENT_CAPTURE_SEALED_AFTER_USER_STOP }
        assertEquals("PROCESS_EXIT_USER_REQUESTED", event.reasonCode)
    }

    /**
     * Found on the phone: a Force stop left TWO identical partial Trips. Reconciliations
     * can overlap (app start, boot receiver, a service restart) and each read the capture
     * before sealing it; the guard must live inside the sealing transaction.
     */
    @Test
    fun overlappingSealsOfTheSameCaptureProduceExactlyOneTripAndOneEvent() = kotlinx.coroutines.runBlocking {
        val captureId = startWithPoints(2_000L, 3_000L, 4_000L)

        val outcomes = kotlinx.coroutines.coroutineScope {
            (1..8).map {
                async(kotlinx.coroutines.Dispatchers.Default) { coordinator.sealActiveCaptureAfterUserStop() }
            }.map { it.await() }
        }

        assertEquals("only one caller actually sealed it", 1, outcomes.count { it is TrackingSessionCoordinator.ReconcileOutcome.SealedAfterReboot })
        assertEquals("one visible partial Trip, not one per caller", 1, db.tripPartDao().countByCaptureId(captureId))
        assertEquals(1, db.diagnosticEventDao().findAll().count { it.eventType == TrackingSessionCoordinator.EVENT_CAPTURE_SEALED_AFTER_USER_STOP })
        assertEquals(1, processingScheduler.enqueuedRequests.size)
    }

    @Test
    fun aSealedCaptureDoesNotBlockTheUsersOwnExplicitStart() = runTest {
        startWithPoints(2_000L, 3_000L)
        reader.exit = ProcessExit(500L, wasUserRequested = true)
        reconciler.reconcile()

        assertTrue(coordinator.startManualCapture() is TrackingSessionCoordinator.StartResult.Started)
    }

    @Test
    fun anExitThatWasAlreadyHandledNeverSealsALaterCapture() = runTest {
        startWithPoints(2_000L, 3_000L)
        reader.exit = ProcessExit(500L, wasUserRequested = true)
        reconciler.reconcile()

        // The user starts a new trip afterwards; the *old* exit is still the latest one on record.
        val newCapture = startWithPoints(5_000L, 6_000L)
        val outcome = reconciler.reconcile()

        assertEquals(UserStopReconciler.Outcome.NothingToDo, outcome)
        assertEquals(CaptureStatus.ACTIVE, db.tripCaptureDao().findById(newCapture)?.status)
    }

    @Test
    fun aCrashOrKillThatIsNotAUserStopIsLeftToTheStickyServiceToResume() = runTest {
        val captureId = startWithPoints(2_000L, 3_000L)
        reader.exit = ProcessExit(500L, wasUserRequested = false)

        assertEquals(UserStopReconciler.Outcome.NothingToDo, reconciler.reconcile())

        assertEquals(CaptureStatus.ACTIVE, db.tripCaptureDao().findById(captureId)?.status)
        assertEquals("still recorded as handled so it isn't re-evaluated forever", 500L, store.handled)
    }

    @Test
    fun noExitInformationAtAllChangesNothing() = runTest {
        val captureId = startWithPoints(2_000L, 3_000L)
        reader.exit = null

        assertEquals(UserStopReconciler.Outcome.NothingToDo, reconciler.reconcile())

        assertEquals(CaptureStatus.ACTIVE, db.tripCaptureDao().findById(captureId)?.status)
        assertEquals(0L, store.handled)
    }

    @Test
    fun aUserStopWithNoActiveCaptureJustAdvancesTheCursor() = runTest {
        reader.exit = ProcessExit(500L, wasUserRequested = true)

        assertEquals(UserStopReconciler.Outcome.NothingToDo, reconciler.reconcile())
        assertEquals(500L, store.handled)
        assertEquals(0, db.diagnosticEventDao().findAll().count { it.eventType == TrackingSessionCoordinator.EVENT_CAPTURE_SEALED_AFTER_USER_STOP })
    }

    @Test
    fun aUserStopBeforeAnyPointWasRecordedSealsWithoutATripButKeepsTheCaptureRow() = runTest {
        val captureId = startWithPoints(2_000L)
        reader.exit = ProcessExit(500L, wasUserRequested = true)

        val outcome = reconciler.reconcile() as UserStopReconciler.Outcome.Sealed

        assertNull("a single point is not a route", outcome.partialTripId)
        assertEquals(CaptureStatus.ABORTED, db.tripCaptureDao().findById(captureId)?.status)
        assertEquals(1, db.rawTrackPointDao().countByCapture(captureId))
    }
}
