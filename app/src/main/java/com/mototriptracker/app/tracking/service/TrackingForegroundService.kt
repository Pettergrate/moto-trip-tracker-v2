package com.mototriptracker.app.tracking.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.mototriptracker.app.core.common.DispatcherProvider
import com.mototriptracker.app.core.notification.TrackingNotificationController
import com.mototriptracker.app.core.notification.TrackingNotificationController.Companion.NOTIFICATION_ID
import com.mototriptracker.app.tracking.activityrecognition.ActivityTransitionBus
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.annotation.VisibleForTesting

/**
 * ADR-004: the Android owner of an active capture. Manual Start,
 * sticky-restart rehydration (TRK-001), location recording for the lifetime
 * of the ACTIVE capture (TRK-002), Finish (TRK-004) and automatic
 * candidate-validation/auto-finish (AUTO-001, `ACTION_AUTO_DETECT`).
 * Pause/Resume (TRK-003) is a separate task that extends this class, not
 * duplicated here.
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
    @Inject lateinit var activityTransitionBus: ActivityTransitionBus

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

    @VisibleForTesting
    var lastFinishResult: TrackingSessionCoordinator.FinishResult? = null
        private set

    @VisibleForTesting
    var lastAutoDetectionOutcome: TrackingSessionCoordinator.AutoDetectionOutcome? = null
        private set

    /**
     * TRK-002: the long-running location-collection loop, tracked
     * separately from [lastCommandJob] (which represents a single Start/
     * rehydrate command, not the ongoing stream it may kick off). Guards
     * against launching a second overlapping collector if `onStartCommand`
     * fires again (e.g. a duplicate Start tap) while one is already active.
     */
    @VisibleForTesting
    var locationRecordingJob: Job? = null
        private set

    /**
     * AUTO-001: mirrors [locationRecordingJob]'s dedupe guard - a second
     * `IN_VEHICLE ENTER` broadcast arriving while a candidate is still being
     * validated (nothing ACTIVE yet, so `ActivityTransitionReceiver` would
     * otherwise ask to start this again) must not spin up a second, racing
     * `runAutoDetection` collector against the same engines/location stream.
     */
    @VisibleForTesting
    var autoDetectionJob: Job? = null
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
        val initialNotification = if (intent?.action == ACTION_AUTO_DETECT) {
            notificationController.buildValidatingCandidateNotification()
        } else {
            notificationController.buildTrackingNotification()
        }
        startForeground(NOTIFICATION_ID, initialNotification)

        lastCommandJob = when (intent?.action) {
            ACTION_START -> serviceScope.launch {
                val result = coordinator.startManualCapture()
                lastStartResult = result
                ensureLocationRecording(result.captureId)
            }
            ACTION_FINISH -> serviceScope.launch { finishActiveCaptureAndStop() }
            ACTION_AUTO_DETECT -> ensureAutoDetection()
            else -> serviceScope.launch { rehydrateOrStop() }
        }

        return START_STICKY
    }

    /**
     * F0.10 §15.1's ordering: stop collecting new evidence (step 2 — no
     * buffer to flush per TRK-002's design, so nothing corresponds to step
     * 3) *before* running the Finish transaction, so `finishCapture`'s
     * TripPart doesn't race a location update landing after it already read
     * the capture's last `sequenceNumber`. `cancelAndJoin` (not plain
     * `cancel`) waits for that in-flight collection to actually stop instead
     * of racing it. A missing ACTIVE capture (already finished by a prior
     * command, or a stale/duplicate Finish intent) skips the coordinator
     * call entirely — REL-INV-007's idempotency is the coordinator's job
     * once there's a captureId to check against — but the service still
     * stops itself either way: whatever Finish was asked to do is already
     * done, so sitting in the foreground afterward has no reason to.
     */
    private suspend fun finishActiveCaptureAndStop() {
        val active = coordinator.findActiveCapture()
        if (active != null) {
            locationRecordingJob?.cancelAndJoin()
            lastFinishResult = coordinator.finishCapture(active.id)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * F0.10 §7.2/REL-002: a sticky restart (or any restart with an unknown/
     * null action) must rehydrate from Room, never assume the intent that
     * originally started it is still meaningful. If no capture is ACTIVE
     * anymore, there's nothing for this service to own — stop cleanly
     * instead of sitting in the foreground for no reason. If one is still
     * ACTIVE, resume recording into it (TRK-002) rather than leaving the
     * notification up without actually collecting anything.
     */
    private suspend fun rehydrateOrStop() {
        val active = coordinator.findActiveCapture()
        if (active == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            ensureLocationRecording(active.id)
        }
    }

    private fun ensureLocationRecording(captureId: String) {
        if (locationRecordingJob?.isActive == true) return
        locationRecordingJob = serviceScope.launch { coordinator.recordLocationUpdates(captureId) }
    }

    /**
     * AUTO-001: launches (or reuses) the single coroutine that owns
     * candidate-start validation through candidate-stop monitoring for one
     * automatic session - see `TrackingSessionCoordinator.runAutoDetection`.
     * `onCaptureStarted` swaps the notification from "validating a
     * candidate" to the normal tracking text at the exact moment a real
     * capture is confirmed (re-calling `startForeground` with the same ID
     * updates the existing notification in place).
     */
    private fun ensureAutoDetection(): Job {
        autoDetectionJob?.takeIf { it.isActive }?.let { return it }
        return serviceScope.launch { runAutoDetectionAndStop() }.also { autoDetectionJob = it }
    }

    private suspend fun runAutoDetectionAndStop() {
        val outcome = coordinator.runAutoDetection(
            activityEvents = activityTransitionBus.events,
            onCaptureStarted = {
                startForeground(NOTIFICATION_ID, notificationController.buildTrackingNotification())
            }
        )
        lastAutoDetectionOutcome = outcome
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TrackingFgService"
        const val ACTION_START = "com.mototriptracker.app.action.START_TRACKING"
        const val ACTION_FINISH = "com.mototriptracker.app.action.FINISH_TRACKING"
        const val ACTION_AUTO_DETECT = "com.mototriptracker.app.action.AUTO_DETECT"

        fun createStartIntent(context: Context): Intent =
            Intent(context, TrackingForegroundService::class.java).setAction(ACTION_START)

        fun createFinishIntent(context: Context): Intent =
            Intent(context, TrackingForegroundService::class.java).setAction(ACTION_FINISH)

        fun createAutoDetectIntent(context: Context): Intent =
            Intent(context, TrackingForegroundService::class.java).setAction(ACTION_AUTO_DETECT)
    }
}
