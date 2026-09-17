package com.mototriptracker.app.tracking.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.DispatcherProvider
import com.mototriptracker.app.core.database.dao.TripCaptureDao
import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.CapabilityMode
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.TransitionType
import com.mototriptracker.app.domain.capability.CapabilityResolver
import com.mototriptracker.app.tracking.activityrecognition.ActivityTransitionBus
import com.mototriptracker.app.tracking.activityrecognition.ActivityTransitionRecorder
import com.mototriptracker.app.tracking.activityrecognition.mapActivityType
import com.mototriptracker.app.tracking.activityrecognition.mapTransitionType
import com.mototriptracker.app.tracking.activityrecognition.reconstructWallTimeEpochMs
import com.mototriptracker.app.tracking.capability.CapabilityInputsProvider
import com.mototriptracker.app.tracking.service.TrackingForegroundService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DET-001: the target of [com.mototriptracker.app.tracking.activityrecognition.ActivityRecognitionRegistrar]'s
 * `PendingIntent` — invoked by Play Services regardless of whether this
 * app's process is currently running (ADR-007's "passive trigger").
 *
 * `onReceive` must stay fast (Android can kill a receiver that blocks too
 * long) — `goAsync()` extends its lifetime just enough to finish the DB
 * write on a background dispatcher, mirroring why `TrackingForegroundService`
 * needs `startForeground()` first: respecting a platform timing contract,
 * not just style.
 */
@AndroidEntryPoint
class ActivityTransitionReceiver : BroadcastReceiver() {

    @Inject lateinit var recorder: ActivityTransitionRecorder
    @Inject lateinit var clock: Clock
    @Inject lateinit var dispatchers: DispatcherProvider
    @Inject lateinit var activityTransitionBus: ActivityTransitionBus
    @Inject lateinit var tripCaptureDao: TripCaptureDao
    @Inject lateinit var capabilityInputsProvider: CapabilityInputsProvider

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        if (result.transitionEvents.isEmpty()) return

        val nowWallMillis = clock.wallClockMillis()
        val nowElapsedRealtimeNanos = clock.elapsedRealtimeNanos()
        val samples = result.transitionEvents.mapNotNull { it.toSampleOrNull(nowWallMillis, nowElapsedRealtimeNanos) }
        if (samples.isEmpty()) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + dispatchers.default).launch {
            try {
                // One bad sample must not drop the rest of the batch (same
                // per-item resilience as TRK-002's recordLocationUpdates) —
                // logged via Log.w, not another DiagnosticEvent: the table
                // that would record the failure is the same one that just
                // failed to write to. The bus emit is non-suspending and
                // never throws (tryEmit), so it always runs regardless of
                // whether the DB write above it succeeded.
                for (sample in samples) {
                    try {
                        recorder.record(sample)
                    } catch (error: Exception) {
                        Log.w(TAG, "Failed to record activity transition $sample", error)
                    }
                    activityTransitionBus.emit(sample)
                }
                maybeStartAutoDetection(context, samples)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * AUTO-001: the one place that decides whether to *start* automatic
     * detection — everything after that (validating the candidate, and
     * later watching for an automatic stop) runs inside
     * `TrackingSessionCoordinator.runAutoDetection`, fed by [activityTransitionBus]
     * from here on. Deliberately stateless per invocation: a plain DAO check
     * ("is a capture already active?") plus a fresh [CapabilityResolver]
     * read, not a flag this receiver remembers across calls — the service it
     * starts is the one that owns any actual state.
     */
    @VisibleForTesting
    internal suspend fun maybeStartAutoDetection(context: Context, samples: List<ActivityTransitionSample>) {
        val enteringVehicle = samples.any {
            it.activityType == ActivityType.IN_VEHICLE && it.transitionType == TransitionType.ENTER
        }
        if (!enteringVehicle) return
        if (tripCaptureDao.findByStatus(CaptureStatus.ACTIVE) != null) return

        val mode = CapabilityResolver.resolve(capabilityInputsProvider.current())
        if (mode != CapabilityMode.FULL_AUTO && mode != CapabilityMode.ASSISTED_AUTO) return

        context.startForegroundService(TrackingForegroundService.createAutoDetectIntent(context))
    }

    private fun ActivityTransitionEvent.toSampleOrNull(nowWallMillis: Long, nowElapsedRealtimeNanos: Long): ActivityTransitionSample? {
        val transitionType = mapTransitionType(transitionType) ?: return null
        return ActivityTransitionSample(
            activityType = mapActivityType(activityType),
            transitionType = transitionType,
            elapsedRealtimeNanos = elapsedRealTimeNanos,
            wallTimeEpochMs = reconstructWallTimeEpochMs(nowWallMillis, nowElapsedRealtimeNanos, elapsedRealTimeNanos),
            source = "activity-transition-receiver"
        )
    }

    companion object {
        private const val TAG = "ActivityTransitionRcvr"
        const val ACTION_ACTIVITY_TRANSITION = "com.mototriptracker.app.action.ACTIVITY_TRANSITION"
    }
}
