package com.mototriptracker.app.tracking.activityrecognition

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
) : ActivityTransitionRegistration {
    @SuppressLint("MissingPermission")
    override fun register() {
        removeLegacyRegistration()
        val request = ActivityTransitionRequest(buildTransitions())
        activityRecognitionClient.requestActivityTransitionUpdates(request, pendingIntent())
            .addOnSuccessListener { Log.i(TAG, "Activity Recognition registered") }
            .addOnFailureListener { error -> Log.w(TAG, "Activity Recognition registration failed", error) }
    }

    /** PERM-002: stops the delivery - what "Auto Tracking is off" has to mean. Logged either way, since a silent failure here would leave the app listening. */
    @SuppressLint("MissingPermission")
    override fun unregister() {
        removeLegacyRegistration()
        activityRecognitionClient.removeActivityTransitionUpdates(pendingIntent())
            .addOnSuccessListener { Log.i(TAG, "Activity Recognition unregistered") }
            .addOnFailureListener { error -> Log.w(TAG, "Activity Recognition unregistration failed", error) }
    }

    private fun transitionIntent(): Intent =
        Intent(context, ActivityTransitionReceiver::class.java).setAction(ActivityTransitionReceiver.ACTION_ACTIVITY_TRANSITION)

    /**
     * **Mutable on purpose.** Play Services delivers a transition by *adding it to the intent* as an extra when it sends
     * this PendingIntent; an immutable one silently refuses that, so every delivery arrived as an empty broadcast and
     * [ActivityTransitionReceiver] threw it away - Auto Tracking could never detect anything. Google's own guidance for
     * activity recognition and geofencing on Android 12+ is `FLAG_MUTABLE`. It is safe here because the intent is explicit
     * (it names the receiver, which is not exported), so a holder of the PendingIntent can add extras but cannot redirect it.
     *
     * A new request code, not just a new flag: a PendingIntent that already exists keeps the mutability it was created
     * with, so `FLAG_MUTABLE` on code 1001 would have changed nothing on any phone that already ran an earlier build.
     */
    internal fun pendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(context, REQUEST_CODE, transitionIntent(), PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag())

    /**
     * Earlier builds registered with an immutable PendingIntent (request code 1001), which Play Services could never deliver
     * a result through. Removed once it is found, so it neither lingers nor delivers empty broadcasts alongside the new one.
     */
    @SuppressLint("MissingPermission")
    private fun removeLegacyRegistration() {
        val legacy = PendingIntent.getBroadcast(
            context, LEGACY_IMMUTABLE_REQUEST_CODE, transitionIntent(), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        activityRecognitionClient.removeActivityTransitionUpdates(legacy)
            .addOnCompleteListener { legacy.cancel() }
    }

    companion object {
        private const val TAG = "ActivityRecognitionReg"
        private const val REQUEST_CODE = 1002
        private const val LEGACY_IMMUTABLE_REQUEST_CODE = 1001

        /** `FLAG_MUTABLE` exists from Android 12; before it a PendingIntent is mutable by default. */
        internal fun mutableFlag(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

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
