package com.mototriptracker.app.tracking.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.mototriptracker.app.core.common.DispatcherProvider
import com.mototriptracker.app.core.notification.TrackingNotificationController
import com.mototriptracker.app.core.notification.TrackingNotificationController.Companion.NOTIFICATION_ID
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.annotation.VisibleForTesting

/**
 * ADR-004: the Android owner of an active capture. TRK-001 scope only —
 * manual Start and sticky-restart rehydration. Pause/Resume/Finish
 * (TRK-003/TRK-004) and real location ingestion (TRK-002) are separate
 * tasks that extend this class, not duplicated here.
 *
 * `startForeground()` is called synchronously as the very first thing in
 * [onStartCommand], before any suspending work — Android requires it within
 * a short window of the service starting, especially on API 26+/31+/34+
 * (verified against F0.4's sources); doing DB work first and calling
 * `startForeground` afterward risks a
 * `ForegroundServiceDidNotStartInTimeException` in the field even though it
 * may work fine on a fast emulator.
 */
@AndroidEntryPoint
class TrackingForegroundService : Service() {

    @Inject lateinit var coordinator: TrackingSessionCoordinator
    @Inject lateinit var notificationController: TrackingNotificationController
    @Inject lateinit var dispatchers: DispatcherProvider

    private lateinit var serviceScope: CoroutineScope

    /**
     * The [Job] for the command handled by the most recent [onStartCommand].
     * Production code never reads this (the service is genuinely
     * fire-and-forget from Android's point of view — that's what
     * `START_STICKY` + Room rehydration are for). Tests join on it instead
     * of fighting coroutine-dispatcher/scheduler synchronization to know
     * when the launched work has actually finished.
     */
    @VisibleForTesting
    var lastCommandJob: Job? = null
        private set

    @VisibleForTesting
    var lastUncaughtCommandError: Throwable? = null
        private set

    @VisibleForTesting
    var lastStartResult: TrackingSessionCoordinator.StartResult? = null
        private set

    /**
     * A `launch`ed child under a bare `SupervisorJob()` silently drops an
     * uncaught exception (`Job.join()` does not rethrow it) — found while
     * writing this service's own test, where a real failure inside
     * [coordinator]'s work was producing no visible error at all. Logging it
     * here is both the fix for that blind spot and, independent of testing,
     * the right thing for a fire-and-forget service command to do in
     * production (F0.13's evidence-not-silence principle).
     */
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in tracking command", throwable)
        lastUncaughtCommandError = throwable
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + dispatchers.default + exceptionHandler)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notificationController.buildTrackingNotification())

        lastCommandJob = when (intent?.action) {
            ACTION_START -> serviceScope.launch { lastStartResult = coordinator.startManualCapture() }
            else -> serviceScope.launch { rehydrateOrStop() }
        }

        return START_STICKY
    }

    /**
     * F0.10 §7.2/REL-002: a sticky restart (or any restart with an unknown/
     * null action) must rehydrate from Room, never assume the intent that
     * originally started it is still meaningful. If no capture is ACTIVE
     * anymore, there's nothing for this service to own — stop cleanly
     * instead of sitting in the foreground for no reason.
     */
    private suspend fun rehydrateOrStop() {
        val active = coordinator.findActiveCapture()
        if (active == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TrackingFgService"
        const val ACTION_START = "com.mototriptracker.app.action.START_TRACKING"

        fun createStartIntent(context: Context): Intent =
            Intent(context, TrackingForegroundService::class.java).setAction(ACTION_START)
    }
}
