package com.mototriptracker.app.tracking.activityrecognition

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.mototriptracker.app.tracking.receiver.ActivityTransitionReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * DET-001/ADR-007: registers the Activity Recognition Transition API as the
 * passive detector trigger. F0.4 §4.5/AND-007: Google documents that this
 * registration does not survive reboot/app-update, so [register] is called
 * both from [com.mototriptracker.app.MotoTripApplication] (first run/normal
 * start) and from `BootReceiver` (`BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`) —
 * "restore" isn't a separate code path, it's the same idempotent call.
 *
 * Uses a `PendingIntent` targeting [ActivityTransitionReceiver], not a live
 * callback: that's what lets registration keep delivering transitions even
 * when this process isn't running, which is the entire point of a *passive*
 * trigger (ADR-007) — an active-Service-scoped callback (like
 * `FusedLocationGateway`'s) would only work while a capture is already
 * live, which is exactly what this is meant to help decide.
 *
 * A registration failure is logged (`Log.w`), same posture as
 * `FusedLocationGateway`'s dropped-point case — not a `DiagnosticEvent`,
 * since there's no consumer that surfaces those to a user yet (`DIA-002`)
 * and this would be the first thing in the app needing a shared
 * long-lived `CoroutineScope` just to log one rare failure.
 */
class ActivityRecognitionRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activityRecognitionClient: ActivityRecognitionClient
) {
    @SuppressLint("MissingPermission")
    fun register() {
        val request = ActivityTransitionRequest(buildTransitions())
        activityRecognitionClient.requestActivityTransitionUpdates(request, pendingIntent())
            .addOnFailureListener { error -> Log.w(TAG, "Activity Recognition registration failed", error) }
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, ActivityTransitionReceiver::class.java)
            .setAction(ActivityTransitionReceiver.ACTION_ACTIVITY_TRANSITION)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "ActivityRecognitionReg"
        private const val REQUEST_CODE = 1001

        /** F0.4 §4.1's 6-type vocabulary, both directions — 12 registrations in one request. */
        internal fun buildTransitions(): List<ActivityTransition> =
            listOf(
                DetectedActivity.IN_VEHICLE,
                DetectedActivity.ON_FOOT,
                DetectedActivity.WALKING,
                DetectedActivity.RUNNING,
                DetectedActivity.ON_BICYCLE,
                DetectedActivity.STILL
            ).flatMap { activityType ->
                listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map { transition ->
                    ActivityTransition.Builder()
                        .setActivityType(activityType)
                        .setActivityTransition(transition)
                        .build()
                }
            }
    }
}
