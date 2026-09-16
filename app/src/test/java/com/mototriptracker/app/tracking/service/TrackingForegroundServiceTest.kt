package com.mototriptracker.app.tracking.service

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.common.AndroidDispatcherProvider
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.notification.TrackingNotificationController
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
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

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun buildServiceController(): ServiceController<TrackingForegroundService> {
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
            tripCaptureDao = db.tripCaptureDao(),
            diagnosticEventDao = db.diagnosticEventDao(),
            clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L),
            idGenerator = FakeIdGenerator(prefix = "capture")
        )
        service.notificationController = TrackingNotificationController(
            ApplicationProvider.getApplicationContext()
        )
        service.dispatchers = AndroidDispatcherProvider()
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
}
