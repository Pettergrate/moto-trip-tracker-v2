package com.mototriptracker.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.mototriptracker.app.tracking.receiver.ActivityTransitionReceiver

/**
 * DET-001 follow-up, debug builds only: sends a real broadcast to [ActivityTransitionReceiver], through the exact
 * dispatch path Play Services uses, with a synthetic transition - so the decision logic that follows a real one
 * (recording, `maybeStartAutoDetection`'s branches) can be exercised on the one device available without waiting for
 * an actual ride. Nothing about location or the trip database is faked or bypassed: from the receiver's `onReceive`
 * onward this is indistinguishable from what Google would have delivered.
 *
 * Triggered from an adb shell already on the machine, never remotely:
 * ```
 * adb shell am broadcast -a com.mototriptracker.app.debug.action.SIMULATE_TRANSITION \
 *   -n com.mototriptracker.app.debug/com.mototriptracker.app.debug.DebugActivityTransitionTrigger \
 *   --es activityType IN_VEHICLE --es transitionType ENTER
 * ```
 * `activityType` (default `IN_VEHICLE`) and `transitionType` (default `ENTER`) match [DetectedActivity]/[ActivityTransition]'s
 * own vocabulary. No implementation of this exists outside the `debug` source set, and its own manifest entry lives only
 * in `src/debug/AndroidManifest.xml` - a release build carries neither.
 */
class DebugActivityTransitionTrigger : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val activityType = ACTIVITY_TYPES[intent.getStringExtra(EXTRA_ACTIVITY_TYPE) ?: "IN_VEHICLE"]
        val transitionType = TRANSITION_TYPES[intent.getStringExtra(EXTRA_TRANSITION_TYPE) ?: "ENTER"]
        if (activityType == null || transitionType == null) {
            Log.w(TAG, "Unknown activityType/transitionType extra; known: ${ACTIVITY_TYPES.keys} / ${TRANSITION_TYPES.keys}")
            return
        }

        val event = ActivityTransitionEvent(activityType, transitionType, SystemClock.elapsedRealtimeNanos())
        val result = ActivityTransitionResult(listOf(event))
        val transitionIntent = Intent(ActivityTransitionReceiver.ACTION_ACTIVITY_TRANSITION)
            .setClassName(context, ActivityTransitionReceiver::class.java.name)
        SafeParcelableSerializer.serializeToIntentExtra(result, transitionIntent, EXTRA_ACTIVITY_TRANSITION_RESULT)

        Log.i(TAG, "Simulating a transition: activityType=${intent.getStringExtra(EXTRA_ACTIVITY_TYPE)}, transitionType=${intent.getStringExtra(EXTRA_TRANSITION_TYPE)}")
        context.sendBroadcast(transitionIntent)
    }

    companion object {
        private const val TAG = "DebugTransitionTrigger"

        /** Play Services' own internal extra key - the same one `ActivityTransitionReceiver` reads for real. */
        private const val EXTRA_ACTIVITY_TRANSITION_RESULT = "com.google.android.location.internal.EXTRA_ACTIVITY_TRANSITION_RESULT"
        private const val EXTRA_ACTIVITY_TYPE = "activityType"
        private const val EXTRA_TRANSITION_TYPE = "transitionType"

        private val ACTIVITY_TYPES = mapOf(
            "IN_VEHICLE" to DetectedActivity.IN_VEHICLE,
            "ON_FOOT" to DetectedActivity.ON_FOOT,
            "WALKING" to DetectedActivity.WALKING,
            "RUNNING" to DetectedActivity.RUNNING,
            "ON_BICYCLE" to DetectedActivity.ON_BICYCLE,
            "STILL" to DetectedActivity.STILL
        )
        private val TRANSITION_TYPES = mapOf(
            "ENTER" to ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            "EXIT" to ActivityTransition.ACTIVITY_TRANSITION_EXIT
        )
    }
}
