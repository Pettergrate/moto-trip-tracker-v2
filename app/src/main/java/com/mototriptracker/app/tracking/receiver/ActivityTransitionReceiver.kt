package com.mototriptracker.app.tracking.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.DispatcherProvider
import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.tracking.activityrecognition.ActivityTransitionRecorder
import com.mototriptracker.app.tracking.activityrecognition.mapActivityType
import com.mototriptracker.app.tracking.activityrecognition.mapTransitionType
import com.mototriptracker.app.tracking.activityrecognition.reconstructWallTimeEpochMs
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
                // failed to write to.
                for (sample in samples) {
                    try {
                        recorder.record(sample)
                    } catch (error: Exception) {
                        Log.w(TAG, "Failed to record activity transition $sample", error)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
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
