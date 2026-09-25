package com.mototriptracker.app.tracking.service

import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.common.AndroidDispatcherProvider
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.EndSource
import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.core.model.TransitionType
import com.mototriptracker.app.core.notification.TrackingNotificationController
import com.mototriptracker.app.testing.FakeLocationGateway
import com.mototriptracker.app.testing.FakeProcessingScheduler
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.activityrecognition.ActivityTransitionBus
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import com.mototriptracker.app.tracking.location.LocationGateway
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Fields are set directly instead of going through real Hilt injection —
 * `@HiltAndroidTest` needs a custom test application/runner this project
 * doesn't have yet, and isn't needed to prove this class's own logic. Hilt's
 * own wiring is separately proven by `hiltJavaCompileDebug` succeeding at
 * compile time (it already failed once during this task when a binding was
 * missing — see DatabaseModule).
 *
 * `onStartCommand` fires its work on `serviceScope`, a real background scope
 * decoupled from any test coroutine. Neither `shadowOf(Looper).idle()`, nor
 * a standalone `UnconfinedTestDispatcher()`, nor a `StandardTestDispatcher`
 * sharing `runTest`'s own scheduler with `advanceUntilIdle()` reliably
 * observed it finish (all three were tried and produced the same assertion
 * failure — the launched coroutine's effect was never visible by the time
 * the assertion ran). `TrackingForegroundService.lastCommandJob` exists
 * specifically so this test can `join()` the real `Job` instead of fighting
 * that synchronization — real dispatchers, real waiting, no virtual time.
 */
@RunWith(RobolectricTestRunner::class)
class TrackingForegroundServiceTest {

    private lateinit var db: MotoTripDatabase
    private val processingScheduler = FakeProcessingScheduler()

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        // REC-002: a sticky restart now validates the location permission first, and
        // Robolectric denies dangerous permissions by default - grant it so the
        // pre-existing restart tests keep modelling a healthy device.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun buildServiceController(
        locationSamples: List<LocationSample> = emptyList(),
        elapsedNanos: Long = 1_000L
    ): ServiceController<TrackingForegroundService> {
        val controller = Robolectric.buildService(TrackingForegroundService::class.java)
        val service = controller.get()
        // .create() runs the real Hilt injection first (MotoTripApplication
        // is a genuine @HiltAndroidApp under Robolectric, and the graph is
        // complete since DatabaseModule was added) — that wires a real,
        // separate production MotoTripDatabase. Overwriting the @Inject
        // fields must happen AFTER .create(), not before, or Hilt's
        // injection clobbers these fakes and the test silently exercises a
        // different database than the one it asserts against.
        controller.create()
        service.coordinator = TrackingSessionCoordinator(
            database = db,
            tripCaptureDao = db.tripCaptureDao(),
            diagnosticEventDao = db.diagnosticEventDao(),
            rawTrackPointDao = db.rawTrackPointDao(),
            captureEventDao = db.captureEventDao(),
            tripDao = db.tripDao(),
            tripPartDao = db.tripPartDao(),
            manualPauseIntervalDao = db.manualPauseIntervalDao(),
            locationGateway = FakeLocationGateway(locationSamples),
            processingScheduler = processingScheduler,
            clock = FakeClock(wallMillis = 1_000L, elapsedNanos = elapsedNanos),
            idGenerator = FakeIdGenerator(prefix = "capture")
        )
        service.notificationController = TrackingNotificationController(
            ApplicationProvider.getApplicationContext()
        )
        service.dispatchers = AndroidDispatcherProvider()
        service.activityTransitionBus = ActivityTransitionBus()
        return controller
    }

    @Test
    fun actionStartCreatesActiveCaptureAndShowsForegroundNotification() = runBlocking {
        val controller = buildServiceController()

        controller.withIntent(TrackingForegroundService.createStartIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        controller.get().lastCommandJob?.join()
        controller.get().lastUncaughtCommandError?.let { throw it }

        val active = db.tripCaptureDao().findByStatus(CaptureStatus.ACTIVE)
        assertNotNull(
            "expected an ACTIVE capture after ACTION_START. " +
                "coordinator returned: ${controller.get().lastStartResult}, " +
                "dao.countByStatus(ACTIVE)=${db.tripCaptureDao().countByStatus(CaptureStatus.ACTIVE)}",
            active
        )

        val manager = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(NotificationManager::class.java)
        val shadowManager = shadowOf(manager) as ShadowNotificationManager
        assertNotNull(
            "expected the tracking notification to be posted",
            shadowManager.getNotification(TrackingNotificationController.NOTIFICATION_ID)
        )
    }

    @Test
    fun actionStartAlsoRecordsLocationSamplesIntoRawTrackPoint() = runBlocking {
        val samples = listOf(
            LocationSample(
                wallTimeEpochMs = 2_000L,
                elapsedRealtimeNanos = 5_000L,
                receivedAtElapsedRealtimeNanos = 5_000L,
                latitude = 10.0,
                longitude = -20.0,
                horizontalAccuracyM = 5.0f,
                requestProfileId = "test-profile"
            )
        )
        val controller = buildServiceController(locationSamples = samples)

        controller.withIntent(TrackingForegroundService.createStartIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        controller.get().lastCommandJob?.join()
        controller.get().locationRecordingJob?.join()
        controller.get().lastUncaughtCommandError?.let { throw it }

        val captureId = requireNotNull(db.tripCaptureDao().findByStatus(CaptureStatus.ACTIVE)).id
        val points = db.rawTrackPointDao().findAllByCapture(captureId)
        assertEquals(1, points.size)
        assertEquals(0L, points.single().sequenceNumber)
    }

    @Test
    fun nullIntentRestartWithNoActiveCaptureStopsTheServiceInsteadOfIdlingInForeground() = runBlocking {
        // No prior ACTION_START in this test: simulates a sticky restart
        // (Intent == null) after the capture it was tracking already ended.
        val controller = buildServiceController()

        controller.withIntent(null).startCommand(0, 0)
        controller.get().lastCommandJob?.join()

        val shadowService = shadowOf(controller.get())
        assertTrue(
            "service should have called stopSelf() — nothing ACTIVE for it to own",
            shadowService.isStoppedBySelf
        )
    }

    @Test
    fun nullIntentRestartWithActiveCaptureResumesLocationRecordingInsteadOfStopping() = runBlocking {
        val firstController = buildServiceController()
        firstController.withIntent(TrackingForegroundService.createStartIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        firstController.get().lastCommandJob?.join()
        val captureId = requireNotNull(db.tripCaptureDao().findByStatus(CaptureStatus.ACTIVE)).id

        // A fresh Service instance, as a real process restart would create,
        // backed by the same persisted database (TRK-001's rehydration).
        val restartedSamples = listOf(
            LocationSample(
                wallTimeEpochMs = 2_000L,
                elapsedRealtimeNanos = 9_000L,
                receivedAtElapsedRealtimeNanos = 9_000L,
                latitude = 1.0,
                longitude = 2.0,
                horizontalAccuracyM = 5.0f,
                requestProfileId = "test-profile"
            )
        )
        val restartedController = buildServiceController(locationSamples = restartedSamples)
        restartedController.withIntent(null).startCommand(0, 0)
        restartedController.get().lastCommandJob?.join()
        restartedController.get().locationRecordingJob?.join()

        val shadowService = shadowOf(restartedController.get())
        assertTrue(
            "service should keep running — a capture is still ACTIVE",
            !shadowService.isStoppedBySelf
        )
        assertEquals(1, db.rawTrackPointDao().findAllByCapture(captureId).size)
    }

    /** REC-001/F0.10 SS10.2: a reboot must never look like an ordinary same-boot restart. */
    @Test
    fun nullIntentRestartAfterARebootAbortsTheCaptureInsteadOfResumingIt() = runBlocking {
        val firstController = buildServiceController(elapsedNanos = 5_000L)
        firstController.withIntent(TrackingForegroundService.createStartIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        firstController.get().lastCommandJob?.join()
        val captureId = requireNotNull(db.tripCaptureDao().findByStatus(CaptureStatus.ACTIVE)).id

        // A fresh boot's elapsedRealtimeNanos starts small again - definitely
        // less than the 5_000L the capture recorded as its own start.
        val restartedController = buildServiceController(elapsedNanos = 100L)
        restartedController.withIntent(null).startCommand(0, 0)
        restartedController.get().lastCommandJob?.join()

        val shadowService = shadowOf(restartedController.get())
        assertTrue(
            "nothing left to own after a reboot-aborted capture - the service should stop",
            shadowService.isStoppedBySelf
        )
        val capture = requireNotNull(db.tripCaptureDao().findById(captureId))
        assertEquals(CaptureStatus.ABORTED, capture.status)
        assertEquals(EndSource.RECOVERY, capture.endSource)
        assertEquals(null, db.tripCaptureDao().findByStatus(CaptureStatus.ACTIVE))
    }

    private fun revokeLocationPermission() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).denyPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    /** REC-002/F0.10 §7.3: the restart used to crash with SecurityException in startForeground once the permission was gone. */
    @Test
    fun stickyRestartWithoutLocationPermissionDeclaresDegradedInsteadOfCrashingOrFakingARecording() = runBlocking {
        val firstController = buildServiceController()
        firstController.withIntent(TrackingForegroundService.createStartIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        firstController.get().lastCommandJob?.join()
        val captureId = requireNotNull(db.tripCaptureDao().findByStatus(CaptureStatus.ACTIVE)).id
        revokeLocationPermission()

        val restartedController = buildServiceController(
            locationSamples = listOf(LocationSample(2_000L, 9_000L, 9_000L, 1.0, 2.0, 5.0f, requestProfileId = "test-profile"))
        )
        restartedController.withIntent(null).startCommand(0, 0)
        restartedController.get().lastCommandJob?.join()

        assertTrue("nothing to keep in the foreground", shadowOf(restartedController.get()).isStoppedBySelf)
        assertNull("no location recording was started", restartedController.get().locationRecordingJob)
        assertEquals("no point may be fabricated or recorded without the permission", 0, db.rawTrackPointDao().findAllByCapture(captureId).size)
        assertEquals("the evidence and the capture stay for the user/reconciliation to decide", CaptureStatus.ACTIVE, db.tripCaptureDao().findById(captureId)?.status)
        val events = db.diagnosticEventDao().findAll().filter { it.eventType == TrackingSessionCoordinator.EVENT_RECOVERY_DEGRADED }
        assertEquals(1, events.size)
        assertEquals(captureId, events.single().captureId)
        assertEquals(TrackingForegroundService.REASON_LOCATION_PERMISSION_MISSING, events.single().reasonCode)
        val manager = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(NotificationManager::class.java)
        assertNotNull(
            "an honest not-recording alert, not the tracking notification",
            (shadowOf(manager) as ShadowNotificationManager).getNotification(TrackingNotificationController.REMINDER_NOTIFICATION_ID)
        )
    }

    @Test
    fun repeatedRestartsWithoutPermissionRecordTheDegradedDecisionOnlyOnce() = runBlocking {
        val firstController = buildServiceController()
        firstController.withIntent(TrackingForegroundService.createStartIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        firstController.get().lastCommandJob?.join()
        revokeLocationPermission()

        repeat(3) {
            val controller = buildServiceController()
            controller.withIntent(null).startCommand(0, 0)
            controller.get().lastCommandJob?.join()
        }

        assertEquals(1, db.diagnosticEventDao().findAll().count { it.eventType == TrackingSessionCoordinator.EVENT_RECOVERY_DEGRADED })
    }

    @Test
    fun stickyRestartWithoutPermissionAndNoActiveCaptureJustStopsQuietly() = runBlocking {
        revokeLocationPermission()
        val controller = buildServiceController()

        controller.withIntent(null).startCommand(0, 0)
        controller.get().lastCommandJob?.join()

        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals(0, db.diagnosticEventDao().findAll().count { it.eventType == TrackingSessionCoordinator.EVENT_RECOVERY_DEGRADED })
    }

    @Test
    fun actionFinishCompletesTheCaptureAndStopsTheService() = runBlocking {
        val controller = buildServiceController()
        controller.withIntent(TrackingForegroundService.createStartIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        controller.get().lastCommandJob?.join()
        val captureId = requireNotNull(db.tripCaptureDao().findByStatus(CaptureStatus.ACTIVE)).id

        controller.withIntent(TrackingForegroundService.createFinishIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        controller.get().lastCommandJob?.join()
        controller.get().lastUncaughtCommandError?.let { throw it }

        val capture = requireNotNull(db.tripCaptureDao().findById(captureId))
        assertEquals(CaptureStatus.COMPLETED, capture.status)
        assertNotNull(
            "expected a Finished result, got ${controller.get().lastFinishResult}",
            controller.get().lastFinishResult as? TrackingSessionCoordinator.FinishResult.Finished
        )
        assertEquals(1, processingScheduler.enqueuedRequests.size)

        val shadowService = shadowOf(controller.get())
        assertTrue("service should stop itself after Finish", shadowService.isStoppedBySelf)
    }

    @Test
    fun actionFinishStopsLocationRecordingBeforeFinishingTheCapture() = runBlocking {
        // A gateway that never completes on its own (unlike the finite
        // FakeLocationGateway used elsewhere) - if Finish didn't actually
        // cancel it first, this test would hang forever joining it.
        val neverEndingSamples = object : LocationGateway {
            override fun locationUpdates(): Flow<LocationSample> = flow { awaitCancellation() }
        }
        val controller = Robolectric.buildService(TrackingForegroundService::class.java)
        val service = controller.get()
        controller.create()
        service.coordinator = TrackingSessionCoordinator(
            database = db,
            tripCaptureDao = db.tripCaptureDao(),
            diagnosticEventDao = db.diagnosticEventDao(),
            rawTrackPointDao = db.rawTrackPointDao(),
            captureEventDao = db.captureEventDao(),
            tripDao = db.tripDao(),
            tripPartDao = db.tripPartDao(),
            manualPauseIntervalDao = db.manualPauseIntervalDao(),
            locationGateway = neverEndingSamples,
            processingScheduler = processingScheduler,
            clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L),
            idGenerator = FakeIdGenerator(prefix = "capture")
        )
        service.notificationController = TrackingNotificationController(ApplicationProvider.getApplicationContext())
        service.dispatchers = AndroidDispatcherProvider()

        controller.withIntent(TrackingForegroundService.createStartIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        controller.get().lastCommandJob?.join()
        assertTrue("recording job should still be active", controller.get().locationRecordingJob?.isActive == true)

        controller.withIntent(TrackingForegroundService.createFinishIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        controller.get().lastCommandJob?.join()

        assertTrue(
            "the never-ending recording job must have been cancelled by Finish",
            controller.get().locationRecordingJob?.isActive == false
        )
        val shadowService = shadowOf(controller.get())
        assertTrue(shadowService.isStoppedBySelf)
    }

    @Test
    fun actionFinishWithNoActiveCaptureStopsTheServiceWithoutCrashing() = runBlocking {
        val controller = buildServiceController()

        controller.withIntent(TrackingForegroundService.createFinishIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        controller.get().lastCommandJob?.join()
        controller.get().lastUncaughtCommandError?.let { throw it }

        val shadowService = shadowOf(controller.get())
        assertTrue(shadowService.isStoppedBySelf)
    }

    // --- AUTO-001: ACTION_AUTO_DETECT ------------------------------------
    //
    // `TrackingSessionCoordinatorTest` already exhaustively covers
    // `runAutoDetection`'s own decision logic (confirm/abandon/auto-finish)
    // using a deterministic, virtual-time-scheduled activity Flow. This
    // service, real-dispatcher test only needs to prove the plumbing around
    // it: the right notification for the right phase, no duplicate
    // collector, and that a real live `ActivityTransitionBus` emission
    // actually reaches it. A full confirm-then-auto-finish run isn't
    // exercised here deliberately - the ticker's `delay(15_000L)` is a real
    // wall-clock wait under `runBlocking` (unlike the coordinator test's
    // virtual time), so waiting out a real confirmation window here would
    // make this suite slow for no extra coverage; the abandon path below
    // resolves immediately (no delay involved) and is enough to prove the
    // bus is wired correctly end to end.

    private fun activitySample(type: ActivityType, transition: TransitionType, elapsedNanos: Long) = ActivityTransitionSample(
        activityType = type,
        transitionType = transition,
        elapsedRealtimeNanos = elapsedNanos,
        wallTimeEpochMs = elapsedNanos / 1_000_000,
        source = "test"
    )

    @Test
    fun actionAutoDetectShowsAValidatingNotificationInsteadOfTheTrackingOne() = runBlocking {
        val controller = buildServiceController()

        controller.withIntent(TrackingForegroundService.createAutoDetectIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)

        val manager = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(NotificationManager::class.java)
        val notification = (shadowOf(manager) as ShadowNotificationManager)
            .getNotification(TrackingNotificationController.NOTIFICATION_ID)
        assertNotNull(notification)
        assertNotEquals(
            "a candidate being validated must not show the 'recording your trip' text",
            "Recording your trip",
            notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        )

        controller.destroy()
        Unit
    }

    @Test
    fun aSecondAutoDetectCommandWhileOneIsRunningReusesTheSameJob() = runBlocking {
        val controller = buildServiceController()

        controller.withIntent(TrackingForegroundService.createAutoDetectIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        val firstJob = controller.get().autoDetectionJob

        controller.withIntent(TrackingForegroundService.createAutoDetectIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        val secondJob = controller.get().autoDetectionJob

        assertNotNull(firstJob)
        assertTrue("the dedupe guard must reuse the same Job, not launch a second collector", firstJob === secondJob)

        controller.destroy()
        Unit
    }

    @Test
    fun actionAutoDetectStopsTheServiceWhenARealBusDeliveredCandidateIsAbandoned() = runBlocking {
        val controller = buildServiceController()

        controller.withIntent(TrackingForegroundService.createAutoDetectIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)

        val bus = controller.get().activityTransitionBus
        withTimeout(5_000) { bus.subscriptionCount.first { it > 0 } }
        bus.emit(activitySample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 0L))
        bus.emit(activitySample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 1_000_000L))

        controller.get().autoDetectionJob?.join()

        assertEquals(
            TrackingSessionCoordinator.AutoDetectionOutcome.CandidateAbandoned,
            controller.get().lastAutoDetectionOutcome
        )
        assertEquals(0, db.tripCaptureDao().countByStatus(CaptureStatus.ACTIVE))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun actionAutoDetectDoesNotStartAnAutoCaptureWhenNoCandidateEverArrives() = runBlocking {
        val controller = buildServiceController()

        controller.withIntent(TrackingForegroundService.createAutoDetectIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        val bus = controller.get().activityTransitionBus
        withTimeout(5_000) { bus.subscriptionCount.first { it > 0 } }

        assertNull(db.tripCaptureDao().findByStatus(CaptureStatus.ACTIVE))
        controller.destroy()
        Unit
    }

    // --- TRK-003: ACTION_PAUSE / ACTION_RESUME ---------------------------

    @Test
    fun actionPauseCreatesAnOpenPauseForTheActiveCapture() = runBlocking {
        val controller = buildServiceController()
        controller.withIntent(TrackingForegroundService.createStartIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        controller.get().lastCommandJob?.join()
        val captureId = requireNotNull(db.tripCaptureDao().findByStatus(CaptureStatus.ACTIVE)).id

        controller.withIntent(TrackingForegroundService.createPauseIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        controller.get().lastCommandJob?.join()
        controller.get().lastUncaughtCommandError?.let { throw it }

        assertNotNull(
            "expected a Paused result, got ${controller.get().lastPauseResult}",
            controller.get().lastPauseResult as? TrackingSessionCoordinator.PauseResult.Paused
        )
        assertNotNull(db.manualPauseIntervalDao().findOpenByCapture(captureId))
    }

    @Test
    fun actionResumeClosesTheOpenPause() = runBlocking {
        val controller = buildServiceController()
        controller.withIntent(TrackingForegroundService.createStartIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        controller.get().lastCommandJob?.join()
        val captureId = requireNotNull(db.tripCaptureDao().findByStatus(CaptureStatus.ACTIVE)).id
        controller.withIntent(TrackingForegroundService.createPauseIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        controller.get().lastCommandJob?.join()

        controller.withIntent(TrackingForegroundService.createResumeIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        controller.get().lastCommandJob?.join()
        controller.get().lastUncaughtCommandError?.let { throw it }

        assertEquals(
            TrackingSessionCoordinator.ResumeResult.Resumed(captureId),
            controller.get().lastResumeResult
        )
        assertNull(db.manualPauseIntervalDao().findOpenByCapture(captureId))
    }

    @Test
    fun actionPauseWithNoActiveCaptureIsANoOpAndDoesNotCrash() = runBlocking {
        val controller = buildServiceController()

        controller.withIntent(TrackingForegroundService.createPauseIntent(ApplicationProvider.getApplicationContext()))
            .startCommand(0, 0)
        controller.get().lastCommandJob?.join()
        controller.get().lastUncaughtCommandError?.let { throw it }

        assertEquals(TrackingSessionCoordinator.PauseResult.NoActiveCapture, controller.get().lastPauseResult)
    }
}
