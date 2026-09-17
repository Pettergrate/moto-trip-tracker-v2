package com.mototriptracker.app.tracking.activityrecognition

import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.TransitionType

/**
 * DET-001: the GMS-vocabulary <-> domain-vocabulary mapping, kept as plain
 * functions over primitive ints/constants (not `ActivityTransitionEvent`
 * itself) specifically so it's testable without needing to construct a real
 * GMS event object.
 *
 * F0.4 §4.1: only these 6 activity types are the accepted vocabulary
 * ([ActivityType.UNKNOWN] is this project's own fallback, not a GMS value).
 */
internal fun mapActivityType(gmsActivityType: Int): ActivityType = when (gmsActivityType) {
    DetectedActivity.IN_VEHICLE -> ActivityType.IN_VEHICLE
    DetectedActivity.ON_FOOT -> ActivityType.ON_FOOT
    DetectedActivity.WALKING -> ActivityType.WALKING
    DetectedActivity.RUNNING -> ActivityType.RUNNING
    DetectedActivity.ON_BICYCLE -> ActivityType.ON_BICYCLE
    DetectedActivity.STILL -> ActivityType.STILL
    else -> ActivityType.UNKNOWN
}

/** `null` for anything other than ENTER/EXIT — the only two transitions this API models. */
internal fun mapTransitionType(gmsTransitionType: Int): TransitionType? = when (gmsTransitionType) {
    ActivityTransition.ACTIVITY_TRANSITION_ENTER -> TransitionType.ENTER
    ActivityTransition.ACTIVITY_TRANSITION_EXIT -> TransitionType.EXIT
    else -> null
}

/**
 * F0.5 §6.1: `elapsedRealtimeNanos` is the authoritative same-boot clock,
 * but `ActivityTransitionEvent` doesn't carry a wall-clock timestamp at all
 * — only elapsed. Reconstructs it from a single "now" reference pair
 * (both clocks read together, same instant) rather than reading the clock
 * again per-event, so multiple events in the same batch share a consistent
 * baseline. Assumes no reboot occurred between the event and now — true
 * within a single boot session, which is what `elapsedRealtimeNanos` means.
 */
internal fun reconstructWallTimeEpochMs(nowWallMillis: Long, nowElapsedRealtimeNanos: Long, eventElapsedRealtimeNanos: Long): Long =
    nowWallMillis - (nowElapsedRealtimeNanos - eventElapsedRealtimeNanos) / 1_000_000
