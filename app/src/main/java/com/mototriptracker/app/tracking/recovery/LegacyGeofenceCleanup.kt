package com.mototriptracker.app.tracking.recovery

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.mototriptracker.app.tracking.activityrecognition.ActivityRecognitionRegistrar
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * DET-011 / `ADR-030` registered a geofence with Play Services, which keeps it across app updates and delivers its EXIT to a
 * `PendingIntent`. The measurement showed it was no use as a trigger (`DET-012`, `ADR-031`) and the code that owned it was
 * removed - but a geofence registered by that build is still there on any phone that ran it, pointing at a receiver that no
 * longer exists. This removes it, once: it looks for the `PendingIntent` the old registration created (found by the same
 * request code, action and receiver class name, which no longer exist as code) and, only if it is there, asks Play Services
 * to drop the geofence and then cancels the `PendingIntent`. On every later start there is nothing to find and it costs one
 * local lookup.
 *
 * Delete this class (and its call in `MotoTripApplication`) once the owner's phone has run a build that contains it.
 */
class LegacyGeofenceCleanup internal constructor(
    private val context: Context,
    private val remove: suspend (PendingIntent) -> Unit
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, { pendingIntent ->
        LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent).awaitUnit()
    })

    /** @return whether a leftover registration was found and removed. Never throws. */
    suspend fun removeIfPresent(): Boolean {
        val legacy = existingLegacyPendingIntent() ?: return false
        return try {
            remove(legacy)
            legacy.cancel()
            Log.i(TAG, "removed the geofence left by the removed movement watch")
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Left in place on purpose: the next start looks again.
            Log.w(TAG, "could not remove the leftover geofence (${error::class.simpleName}); will retry at the next start")
            false
        }
    }

    /** The `PendingIntent` the removed registration created, only if it still exists (`FLAG_NO_CREATE`: never makes one). */
    internal fun existingLegacyPendingIntent(): PendingIntent? =
        PendingIntent.getBroadcast(
            context, LEGACY_REQUEST_CODE,
            Intent().setClassName(context.packageName, LEGACY_RECEIVER_CLASS).setAction(LEGACY_ACTION),
            PendingIntent.FLAG_NO_CREATE or ActivityRecognitionRegistrar.mutableFlag()
        )

    internal companion object {
        private const val TAG = "LegacyGeofenceCleanup"
        const val LEGACY_REQUEST_CODE = 1003
        const val LEGACY_RECEIVER_CLASS = "com.mototriptracker.app.tracking.movement.MovementWatchReceiver"
        const val LEGACY_ACTION = "com.mototriptracker.app.action.MOVEMENT_WATCH"
    }
}

private suspend fun Task<Void>.awaitUnit() = suspendCancellableCoroutine<Unit> { continuation ->
    addOnSuccessListener { continuation.resume(Unit) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
