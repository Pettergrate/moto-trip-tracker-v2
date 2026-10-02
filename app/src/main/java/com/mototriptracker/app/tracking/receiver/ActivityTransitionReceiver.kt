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
import com.mototriptracker.app.core.model.EndSource
import com.mototriptracker.app.core.model.TransitionType
import com.mototriptracker.app.domain.capability.CapabilityResolver
import com.mototriptracker.app.domain.capability.DetectionListening
import com.mototriptracker.app.domain.detection.PostFinishSuppression
import com.mototriptracker.app.domain.detection.isVehicleLike
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
        val reading = readBroadcast(intent)

        // Even a broadcast with nothing usable in it is handled (and, while listening, recorded): one that arrives and leaves no
        // durable trace cannot be told apart from one that never came.
        // Nullable on purpose: the system always provides it, but a receiver driven directly (as the tests do) has none.
        val pendingResult: PendingResult? = goAsync()
        CoroutineScope(SupervisorJob() + dispatchers.default).launch {
            try {
                // One bad sample must not drop the rest of the batch (same
                // per-item resilience as TRK-002's recordLocationUpdates) —
                // logged via Log.w, not another DiagnosticEvent: the table
                // that would record the failure is the same one that just
                // failed to write to. The bus emit is non-suspending and
                // never throws (tryEmit), so it always runs regardless of
                // whether the DB write above it succeeded.
                if (reading.samples.isEmpty()) {
                    handleEmptyBroadcast(reading.emptyReason ?: ActivityTransitionRecorder.EMPTY_NO_USABLE_EVENTS)
                } else {
                    handleTransitions(context, reading.samples)
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    /**
     * The transitions inside a broadcast, or an empty list. Every way of coming out empty says why (no coordinates, no
     * personal data): a broadcast that arrives and leaves no trace is impossible to tell from one that never came, and
     * that is exactly what was missing when Auto Tracking recorded nothing for two weeks - the deliveries were arriving
     * empty (an immutable PendingIntent, see `ActivityRecognitionRegistrar`) and this returned without a word.
     */
    @VisibleForTesting
    internal fun samplesFrom(intent: Intent): List<ActivityTransitionSample> = readBroadcast(intent).samples

    /** What a broadcast held: the usable transitions, or - when there are none - the reason, in the vocabulary the diagnostic event uses. */
    internal class BroadcastReading(val samples: List<ActivityTransitionSample>, val emptyReason: String? = null)

    @VisibleForTesting
    internal fun readBroadcast(intent: Intent): BroadcastReading {
        if (!ActivityTransitionResult.hasResult(intent)) {
            Log.i(TAG, "Broadcast received with no transition result (action=${intent.action})")
            return BroadcastReading(emptyList(), ActivityTransitionRecorder.EMPTY_NO_RESULT)
        }
        val result = ActivityTransitionResult.extractResult(intent)
        if (result == null) {
            Log.w(TAG, "Broadcast said it had a transition result but it could not be read")
            return BroadcastReading(emptyList(), ActivityTransitionRecorder.EMPTY_UNREADABLE)
        }
        if (result.transitionEvents.isEmpty()) {
            Log.i(TAG, "Transition result with no events")
            return BroadcastReading(emptyList(), ActivityTransitionRecorder.EMPTY_NO_EVENTS)
        }
        val nowWallMillis = clock.wallClockMillis()
        val nowElapsedRealtimeNanos = clock.elapsedRealtimeNanos()
        val samples = result.transitionEvents.mapNotNull { it.toSampleOrNull(nowWallMillis, nowElapsedRealtimeNanos) }
        Log.i(TAG, "Transition result: ${result.transitionEvents.size} event(s), ${samples.size} usable")
        return if (samples.isEmpty()) BroadcastReading(emptyList(), ActivityTransitionRecorder.EMPTY_NO_USABLE_EVENTS) else BroadcastReading(samples)
    }

    /**
     * PERM-002: what is done with transitions that arrive. The listening rule is checked *here*, not only when
     * registering: if Google's side still delivers one after Auto Tracking was switched off (a removal that failed, a
     * delivery already in flight), it is dropped - not stored, not published, and it cannot start anything. "Off" has to
     * mean the app does not keep the movement data, whatever the platform does. When the read that decides this fails,
     * the transition is dropped too: not being sure is not a reason to keep collecting.
     */
    @VisibleForTesting
    internal suspend fun handleTransitions(context: Context, samples: List<ActivityTransitionSample>) {
        if (!isListening()) {
            Log.i(TAG, "Not listening (Auto Tracking off or no permission): dropped ${samples.size} activity transition(s)")
            return
        }
        for (sample in samples) {
            try {
                recorder.record(sample)
            } catch (error: Exception) {
                Log.w(TAG, "Failed to record activity transition $sample", error)
            }
        }
        // The bus emit is non-suspending and never throws (tryEmit), so it runs whatever happened to the writes above.
        for (sample in samples.entersFirstAtTheSameInstant()) activityTransitionBus.emit(sample)
        maybeStartAutoDetection(context, samples)
    }

    /**
     * DET-009 (`ADR-026`): Android reports a change of label as an EXIT of the old one and an ENTER of the new one with
     * the very same timestamp, EXIT first (read back from the phone: every pair, to the nanosecond). Handed to the
     * detector in that order, the EXIT of `IN_VEHICLE` would end a ride that was only being re-labelled `ON_BICYCLE`.
     * Putting the ENTER first - only between events of the same instant, everything else stays as delivered - lets the
     * engines see the new label as current before the old one's EXIT arrives.
     */
    @VisibleForTesting
    internal fun List<ActivityTransitionSample>.entersFirstAtTheSameInstant(): List<ActivityTransitionSample> {
        val ordered = ArrayList<ActivityTransitionSample>(size)
        var start = 0
        while (start < size) {
            var end = start
            while (end < size && this[end].elapsedRealtimeNanos == this[start].elapsedRealtimeNanos) end++
            val sameInstant = subList(start, end)
            ordered += sameInstant.filter { it.transitionType == TransitionType.ENTER }
            ordered += sameInstant.filter { it.transitionType != TransitionType.ENTER }
            start = end
        }
        return ordered
    }

    /**
     * A broadcast that arrived with nothing usable in it, while listening: recorded, so that a later look at the diagnostics
     * (the screen, the export) can tell "Google never called" from "Google called with nothing" - the distinction that took
     * a day of digging to make, because the log the phone keeps is gone in minutes. While *not* listening nothing is
     * recorded at all, not even that a broadcast came: off has to mean the app keeps no trace of movement.
     */
    @VisibleForTesting
    internal suspend fun handleEmptyBroadcast(reason: String) {
        if (!isListening()) return
        try {
            recorder.recordEmptyBroadcast(reason)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to record an empty activity broadcast ($reason)", error)
        }
    }

    /** The listening rule; a read that fails means "not listening" - not being sure is not a reason to keep collecting. */
    private suspend fun isListening(): Boolean = try {
        DetectionListening.shouldListen(capabilityInputsProvider.current())
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        false
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
     *
     * DET-006/F0.3 §9: also refuses to start while the most recently ended
     * capture is still inside [PostFinishSuppression]'s window — "a rider
     * may press Finish while the device is still moving; without
     * protection, the detector could immediately create a new candidate
     * Trip." A Manual Start is never gated by this at all, since it never
     * goes through this receiver (DP-005: manual intent wins regardless).
     */
    @VisibleForTesting
    internal suspend fun maybeStartAutoDetection(context: Context, samples: List<ActivityTransitionSample>) {
        // The *specific* sample matters now, not just whether one exists: it is handed to the service so the
        // candidate engine sees it even on a cold start (see TrackingForegroundService.seedFromIntent's KDoc).
        // DET-009 (ADR-026): `ON_BICYCLE` counts - Android gave the owner's motorcycle that label for a whole ride.
        val enteringVehicleSample = samples.firstOrNull {
            it.activityType.isVehicleLike() && it.transitionType == TransitionType.ENTER
        } ?: return

        // Every branch below decides out loud from here on (found on the phone: two real commutes, both classified
        // correctly, and no record of what this decided or why - see `ActivityTransitionRecorder.recordAutoDetectionDecision`).
        if (tripCaptureDao.findByStatus(CaptureStatus.ACTIVE) != null) {
            logDecision(ActivityTransitionRecorder.EVENT_AUTO_DETECTION_NOT_STARTED, ActivityTransitionRecorder.REASON_CAPTURE_ALREADY_ACTIVE)
            return
        }

        val mode = CapabilityResolver.resolve(capabilityInputsProvider.current())
        if (mode != CapabilityMode.FULL_AUTO && mode != CapabilityMode.ASSISTED_AUTO) {
            logDecision(ActivityTransitionRecorder.EVENT_AUTO_DETECTION_NOT_STARTED, ActivityTransitionRecorder.REASON_CAPABILITY_NOT_ELIGIBLE, stateAfter = mode.name)
            return
        }

        // DET-008: what the window protects against is a Finish somebody *asked for* while the device is still
        // moving (F0.3 §9: "post-manual-finish suppression"). A Finish the detector decided on itself is a different
        // case: it comes at least a grace period after the vehicle stopped, so an `IN_VEHICLE` ENTER right after it is
        // the ride continuing (Activity Recognition will not say it twice), and suppressing it would lose the rest.
        val lastEnded = tripCaptureDao.findMostRecentlyEnded()?.takeIf { it.endSource != EndSource.AUTO }
        if (PostFinishSuppression.isSuppressed(lastEnded?.endElapsedRealtimeNanos, clock.elapsedRealtimeNanos())) {
            logDecision(ActivityTransitionRecorder.EVENT_AUTO_DETECTION_NOT_STARTED, ActivityTransitionRecorder.REASON_POST_FINISH_SUPPRESSED)
            return
        }

        try {
            context.startForegroundService(TrackingForegroundService.createAutoDetectIntent(context, enteringVehicleSample))
            logDecision(
                ActivityTransitionRecorder.EVENT_AUTO_DETECTION_STARTED,
                if (enteringVehicleSample.activityType == ActivityType.ON_BICYCLE) {
                    ActivityTransitionRecorder.REASON_ON_BICYCLE_ENTER
                } else {
                    ActivityTransitionRecorder.REASON_IN_VEHICLE_ENTER
                }
            )
        } catch (error: Exception) {
            // Android 12+'s background-start restrictions can refuse a foreground service started from a broadcast
            // receiver's context (`ForegroundServiceStartNotAllowedException`); this is the one branch that is not a
            // quiet, deliberate early return, so the exception's class name is worth keeping (no message text: it could
            // echo call arguments this codebase does not otherwise log).
            Log.w(TAG, "startForegroundService for auto-detection failed", error)
            logDecision(ActivityTransitionRecorder.EVENT_AUTO_DETECTION_NOT_STARTED, ActivityTransitionRecorder.REASON_SERVICE_START_FAILED, stateAfter = error::class.simpleName)
        }
    }

    private suspend fun logDecision(eventType: String, reasonCode: String, stateAfter: String? = null) {
        try {
            recorder.recordAutoDetectionDecision(eventType, reasonCode, stateAfter)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to record an auto-detection decision ($eventType/$reasonCode)", error)
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
