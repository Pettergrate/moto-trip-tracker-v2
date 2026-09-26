package com.mototriptracker.app.tracking.service

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.mototriptracker.app.core.common.DispatcherProvider
import com.mototriptracker.app.core.notification.TrackingNotificationController
import com.mototriptracker.app.core.notification.TrackingNotificationController.Companion.NOTIFICATION_ID
import com.mototriptracker.app.tracking.activityrecognition.ActivityTransitionBus
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import com.mototriptracker.app.tracking.persistence.PersistenceHealthBus
import com.mototriptracker.app.tracking.persistence.PersistenceState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.annotation.VisibleForTesting

/**
 * ADR-004: the Android owner of an active capture. Manual Start,
 * sticky-restart rehydration (TRK-001), location recording for the lifetime
 * of the ACTIVE capture (TRK-002), Finish (TRK-004), Pause/Resume (TRK-003,
 * `ACTION_PAUSE`/`ACTION_RESUME`) and automatic candidate-validation/
 * auto-finish (AUTO-001, `ACTION_AUTO_DETECT`).
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
    @Inject lateinit var persistenceHealthBus: PersistenceHealthBus

    private lateinit var serviceScope: CoroutineScope

    /**
     * REC-005: the recording's last reported location-signal state, only ever used to word the
     * notification (the diagnostic events are the persisted record). A restarted service
     * begins at "restored" and learns otherwise at the next gap transition.
     */
    @Volatile
    private var locationSignal = TrackingSessionCoordinator.LocationSignalReport.RESTORED

    /** REC-006: the recording last reported persistence state, for the notification; the screen reads the same from [persistenceHealthBus]. */
    @Volatile
    private var persistence = PersistenceState.HEALTHY

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

    @VisibleForTesting
    var lastPauseResult: TrackingSessionCoordinator.PauseResult? = null
        private set

    @VisibleForTesting
    var lastResumeResult: TrackingSessionCoordinator.ResumeResult? = null
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
     * NOT-001: keeps the notification's live distance/duration text
     * reasonably fresh without recomputing it on every single location
     * sample (`currentTrackingSnapshot` rescans the capture's whole raw
     * point history - fine at this cadence, wasteful at TRK-002's ~2s
     * sample rate). State-changing moments (Start, rehydrate, Pause,
     * Resume) refresh immediately instead of waiting for the next tick.
     */
    @VisibleForTesting
    var notificationRefreshJob: Job? = null
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
        val action = intent?.action

        // REC-002/F0.10 §7.3: a restart with no command (sticky / `Intent == null`)
        // must validate before rehydrating. Entering the foreground with the
        // location type while the permission is gone throws `SecurityException` -
        // the exact crash a revoked permission used to cause here. Declare the
        // recording degraded instead of faking a healthy one, and don't ask to be
        // restarted again (that would just loop).
        if (action !in COMMAND_ACTIONS && !hasLocationPermission()) {
            lastCommandJob = serviceScope.launch { handleRestartWithoutLocationPermission() }
            return START_NOT_STICKY
        }

        val initialNotification = if (action == ACTION_AUTO_DETECT) {
            notificationController.buildValidatingCandidateNotification()
        } else {
            notificationController.buildTrackingNotification()
        }
        // Even with the permission present the platform can still refuse the
        // foreground (e.g. revoked between the check and this call). A Finish
        // only touches Room, so it still runs - the user can always close their
        // trip; every other command needs a live foreground service to mean
        // anything, so it stops.
        if (!tryStartForeground(initialNotification) && action != ACTION_FINISH) {
            stopSelf()
            return START_NOT_STICKY
        }

        lastCommandJob = when (action) {
            ACTION_START -> serviceScope.launch {
                val result = coordinator.startManualCapture()
                lastStartResult = result
                ensureLocationRecording(result.captureId)
                ensureNotificationRefreshTicker(result.captureId)
                refreshNotification(result.captureId)
            }
            ACTION_FINISH -> serviceScope.launch { finishActiveCaptureAndStop() }
            ACTION_PAUSE -> serviceScope.launch {
                val result = coordinator.pauseCapture()
                lastPauseResult = result
                pausedCaptureId(result)?.let { refreshNotification(it) }
            }
            ACTION_RESUME -> serviceScope.launch {
                val result = coordinator.resumeCapture()
                lastResumeResult = result
                resumedCaptureId(result)?.let { refreshNotification(it) }
            }
            ACTION_AUTO_DETECT -> ensureAutoDetection()
            else -> serviceScope.launch { rehydrateOrStop() }
        }

        return START_STICKY
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** `false` if the platform refused the foreground (see [onStartCommand]); never throws. */
    private fun tryStartForeground(notification: Notification): Boolean = try {
        startForeground(NOTIFICATION_ID, notification)
        true
    } catch (refused: RuntimeException) {
        Log.w(TAG, "startForeground refused", refused)
        false
    }

    private suspend fun handleRestartWithoutLocationPermission() {
        if (coordinator.markRecoveryDegraded(REASON_LOCATION_PERMISSION_MISSING) != null) {
            notificationController.postRecoveryDegradedAlert()
        }
        stopSelf()
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
     * F0.10 §7.2/REL-002/REC-001: a sticky restart (or any restart with an
     * unknown/null action) must rehydrate from Room, never assume the intent
     * that originally started it is still meaningful.
     * [TrackingSessionCoordinator.recoverActiveCaptureIfAny] does the actual
     * same-boot-vs-reboot decision (F0.10 §7.1/§10.1); this method only acts
     * on its answer. No capture, or one just aborted because a reboot broke
     * elapsedRealtime continuity - either way there's nothing left for this
     * service to own, so it stops cleanly rather than sitting in the
     * foreground for no reason. Still `ACTIVE` (a genuine same-boot process
     * death) resumes recording into it (TRK-002) instead of leaving the
     * notification up without actually collecting anything.
     */
    private suspend fun rehydrateOrStop() {
        when (val outcome = coordinator.recoverActiveCaptureIfAny()) {
            TrackingSessionCoordinator.RecoveryOutcome.NoActiveCapture,
            is TrackingSessionCoordinator.RecoveryOutcome.AbortedAfterReboot -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            is TrackingSessionCoordinator.RecoveryOutcome.Resumed -> {
                ensureLocationRecording(outcome.captureId)
                ensureNotificationRefreshTicker(outcome.captureId)
                refreshNotification(outcome.captureId)
            }
        }
    }

    private fun ensureLocationRecording(captureId: String) {
        if (locationRecordingJob?.isActive == true) return
        locationRecordingJob = serviceScope.launch {
            coordinator.recordLocationUpdates(
                captureId,
                onForgottenPauseWarning = { notificationController.postForgottenPauseReminder() },
                onForgottenFinishWarning = { notificationController.postForgottenFinishReminder() },
                locationServicesEnabled = ::isLocationServicesEnabled,
                onLocationSignalChanged = { report -> onLocationSignalChanged(captureId, report) },
                onPersistenceStateChanged = { state -> onPersistenceStateChanged(captureId, state) }
            )
        }
    }

    private fun pausedCaptureId(result: TrackingSessionCoordinator.PauseResult): String? = when (result) {
        is TrackingSessionCoordinator.PauseResult.Paused -> result.captureId
        is TrackingSessionCoordinator.PauseResult.AlreadyPaused -> result.captureId
        TrackingSessionCoordinator.PauseResult.NoActiveCapture -> null
    }

    private fun resumedCaptureId(result: TrackingSessionCoordinator.ResumeResult): String? = when (result) {
        is TrackingSessionCoordinator.ResumeResult.Resumed -> result.captureId
        is TrackingSessionCoordinator.ResumeResult.AlreadyResumed -> result.captureId
        TrackingSessionCoordinator.ResumeResult.NoActiveCapture -> null
    }

    private fun isLocationServicesEnabled(): Boolean =
        getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false

    /** REC-006: shown at once, and never depends on the database being readable (see [refreshNotification]). */
    private suspend fun onPersistenceStateChanged(captureId: String, state: PersistenceState) {
        persistence = state
        persistenceHealthBus.publish(state)
        refreshNotification(captureId)
    }

    /** REC-005: word the notification honestly the moment the signal changes instead of waiting for the next periodic refresh. */
    private suspend fun onLocationSignalChanged(captureId: String, report: TrackingSessionCoordinator.LocationSignalReport) {
        locationSignal = report
        refreshNotification(captureId)
    }

    /** NOT-001: started once a capture is confirmed active; not order-sensitive like [locationRecordingJob], so `onDestroy`'s `serviceScope.cancel()` cleaning it up on Finish/stop is enough - no explicit cancel needed here. */
    private fun ensureNotificationRefreshTicker(captureId: String) {
        if (notificationRefreshJob?.isActive == true) return
        notificationRefreshJob = serviceScope.launch {
            while (isActive) {
                delay(NOTIFICATION_REFRESH_INTERVAL_MS)
                refreshNotification(captureId)
            }
        }
    }

    /** Re-calling `startForeground` with the same ID updates the existing notification in place - the same mechanism `runAutoDetectionAndStop`'s own `onCaptureStarted` swap already relies on. */
    private suspend fun refreshNotification(captureId: String) {
        // REC-006: when the database is what is failing, reading the figures may fail too. The
        // warning must still reach the rider, so fall back to a notification without figures.
        val snapshot = try {
            coordinator.currentTrackingSnapshot(captureId) ?: return
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "notification figures unavailable (${error::class.simpleName}); showing state only")
            null
        }
        val notification = when {
            snapshot == null -> notificationController.buildTrackingNotification(signal = locationSignal, persistence = persistence.level)
            snapshot.isPaused -> notificationController.buildPausedTrackingNotification(snapshot.elapsedMs)
            else -> notificationController.buildTrackingNotification(snapshot.distanceMeters, snapshot.elapsedMs, locationSignal, persistence.level)
        }
        startForeground(NOTIFICATION_ID, notification)
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
            onCaptureStarted = { captureId ->
                startForeground(NOTIFICATION_ID, notificationController.buildTrackingNotification())
                ensureNotificationRefreshTicker(captureId)
                refreshNotification(captureId)
            },
            onForgottenPauseWarning = { notificationController.postForgottenPauseReminder() },
            locationServicesEnabled = ::isLocationServicesEnabled,
            onLocationSignalChanged = { report -> coordinator.findActiveCapture()?.let { onLocationSignalChanged(it.id, report) } },
            onPersistenceStateChanged = { state -> coordinator.findActiveCapture()?.let { onPersistenceStateChanged(it.id, state) } }
        )
        lastAutoDetectionOutcome = outcome
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        persistenceHealthBus.reset()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TrackingFgService"
        // NOT-001/ADR-018-style placeholder: not validated against real
        // battery/UX field data, just a reasonable middle ground between
        // "stale for a while" and rescanning the whole raw-point history
        // needlessly often.
        private const val NOTIFICATION_REFRESH_INTERVAL_MS = 30_000L
        const val ACTION_START = "com.mototriptracker.app.action.START_TRACKING"
        const val ACTION_FINISH = "com.mototriptracker.app.action.FINISH_TRACKING"
        const val ACTION_PAUSE = "com.mototriptracker.app.action.PAUSE_TRACKING"
        const val ACTION_RESUME = "com.mototriptracker.app.action.RESUME_TRACKING"
        const val ACTION_AUTO_DETECT = "com.mototriptracker.app.action.AUTO_DETECT"
        private val COMMAND_ACTIONS = setOf(ACTION_START, ACTION_FINISH, ACTION_PAUSE, ACTION_RESUME, ACTION_AUTO_DETECT)
        const val REASON_LOCATION_PERMISSION_MISSING = "LOCATION_PERMISSION_MISSING"

        fun createStartIntent(context: Context): Intent =
            Intent(context, TrackingForegroundService::class.java).setAction(ACTION_START)

        fun createFinishIntent(context: Context): Intent =
            Intent(context, TrackingForegroundService::class.java).setAction(ACTION_FINISH)

        fun createPauseIntent(context: Context): Intent =
            Intent(context, TrackingForegroundService::class.java).setAction(ACTION_PAUSE)

        fun createResumeIntent(context: Context): Intent =
            Intent(context, TrackingForegroundService::class.java).setAction(ACTION_RESUME)

        fun createAutoDetectIntent(context: Context): Intent =
            Intent(context, TrackingForegroundService::class.java).setAction(ACTION_AUTO_DETECT)
    }
}
