package com.mototriptracker.app.tracking.movement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.mototriptracker.app.core.common.DispatcherProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DET-011: the `PendingIntent` target of the movement watch's geofence. Play Services invokes it - starting the process
 * if it is not running - when the phone leaves the circle. Same discipline as [com.mototriptracker.app.tracking.receiver.ActivityTransitionReceiver]:
 * `goAsync()` to finish the work off the main thread, and a broadcast that holds nothing usable is *recorded*, not
 * dropped - the empty-delivery bug that silenced Auto Tracking for two weeks is exactly what a silent return hides.
 *
 * Observation mode (`ADR-030`): an EXIT is only logged, by [MovementWatching.onExit].
 */
@AndroidEntryPoint
class MovementWatchReceiver : BroadcastReceiver() {

    @Inject lateinit var movementWatch: MovementWatching
    @Inject lateinit var dispatchers: DispatcherProvider

    /** What a broadcast held: an EXIT, another transition, or an error. `null` when there was no geofencing event at all. */
    internal class Reading(val hasError: Boolean, val errorCode: Int, val isExit: Boolean)

    override fun onReceive(context: Context, intent: Intent) {
        val reading = readBroadcast(intent)
        val pendingResult: PendingResult? = goAsync()
        CoroutineScope(SupervisorJob() + dispatchers.default).launch {
            try {
                handle(reading)
            } catch (error: Exception) {
                Log.w(TAG, "movement watch broadcast not handled (${error::class.simpleName})")
            } finally {
                pendingResult?.finish()
            }
        }
    }

    @VisibleForTesting
    internal fun readBroadcast(intent: Intent): Reading? {
        val event = GeofencingEvent.fromIntent(intent) ?: return null
        return Reading(
            hasError = event.hasError(),
            errorCode = event.errorCode,
            isExit = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT
        )
    }

    /** The decision, apart from reading the platform's wire format (which cannot be fabricated in a unit test). */
    @VisibleForTesting
    internal suspend fun handle(reading: Reading?) {
        when {
            reading == null -> movementWatch.onEmptyBroadcast(REASON_NO_EVENT)
            reading.hasError -> movementWatch.onEmptyBroadcast("ERROR_${reading.errorCode}")
            reading.isExit -> movementWatch.onExit()
            else -> movementWatch.onEmptyBroadcast(REASON_OTHER_TRANSITION)
        }
    }

    companion object {
        private const val TAG = "MovementWatchRcvr"
        const val ACTION_MOVEMENT_WATCH = "com.mototriptracker.app.action.MOVEMENT_WATCH"
        const val REASON_NO_EVENT = "NO_EVENT"
        const val REASON_OTHER_TRANSITION = "OTHER_TRANSITION"
    }
}
