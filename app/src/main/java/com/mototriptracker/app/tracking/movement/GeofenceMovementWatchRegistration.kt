package com.mototriptracker.app.tracking.movement

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.tasks.Task
import com.mototriptracker.app.tracking.activityrecognition.ActivityRecognitionRegistrar
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * DET-011: the movement watch as a Play Services geofence with only an EXIT transition, delivered to
 * [MovementWatchReceiver] through a `PendingIntent` - so, like the Activity Recognition registration, it keeps working
 * when this process is not running. It does not survive a reboot or an app update (the same documented limit), which is
 * why `AutoTrackingDetection.sync` (app start, `BootReceiver`) arms it again.
 *
 * One geofence, always the same id: arming again replaces the earlier one, so the watch follows the phone instead of
 * piling up circles.
 */
class GeofenceMovementWatchRegistration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geofencingClient: GeofencingClient
) : MovementWatchRegistration {

    @SuppressLint("MissingPermission")
    override suspend fun arm(latitude: Double, longitude: Double, radiusMeters: Float) {
        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(latitude, longitude, radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        // No initial trigger: arming while already outside (or inside) must not report anything by itself.
        val request = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(geofence).build()
        geofencingClient.addGeofences(request, pendingIntent()).awaitResult()
    }

    override suspend fun disarm() {
        try {
            geofencingClient.removeGeofences(pendingIntent()).awaitResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Nothing registered is the common case here; never worth stopping anyone for.
            Log.i(TAG, "movement watch removal reported ${error::class.simpleName}")
        }
    }

    private fun watchIntent(): Intent =
        Intent(context, MovementWatchReceiver::class.java).setAction(MovementWatchReceiver.ACTION_MOVEMENT_WATCH)

    /**
     * **Mutable on purpose**, for the same reason as the Activity Recognition one: Play Services delivers the transition by
     * *adding it to the intent as an extra*, and an immutable PendingIntent silently refuses that - every delivery would
     * arrive empty (that bug cost two weeks of Auto Tracking). Explicit intent naming a non-exported receiver, so a holder
     * can add extras but cannot redirect it. Its own request code, distinct from the Activity Recognition one.
     */
    internal fun pendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE, watchIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or ActivityRecognitionRegistrar.mutableFlag()
        )

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> continuation.resume(result) }
        addOnFailureListener { error -> continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }

    companion object {
        private const val TAG = "MovementWatchReg"
        internal const val REQUEST_CODE = 1003
        internal const val GEOFENCE_ID = "movement-watch"
    }
}
