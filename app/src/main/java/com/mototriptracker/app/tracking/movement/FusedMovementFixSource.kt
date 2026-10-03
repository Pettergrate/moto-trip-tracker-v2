package com.mototriptracker.app.tracking.movement

import android.annotation.SuppressLint
import android.location.Location
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.mototriptracker.app.core.common.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * DET-011: the position a movement watch is centred on - the cheapest one available. An already known position that is
 * recent enough costs nothing; otherwise one *balanced-power* fix (network/Wi-Fi/cell, no GPS) is asked for, for a few
 * seconds at most. Never a GPS fix: the watcher exists so that nothing power-hungry runs while the phone sits still.
 *
 * Any failure - no permission, no provider, a timeout - is a `null`, which the watcher records as `NO_FIX`.
 */
class FusedMovementFixSource @Inject constructor(
    private val client: FusedLocationProviderClient,
    private val clock: Clock
) : MovementFixSource {

    @SuppressLint("MissingPermission")
    override suspend fun currentFix(maxAgeMs: Long, timeoutMs: Long): MovementFix? = try {
        lastKnown(maxAgeMs) ?: current(timeoutMs)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.i(TAG, "no position for the movement watch (${error::class.simpleName})")
        null
    }

    @SuppressLint("MissingPermission")
    private suspend fun lastKnown(maxAgeMs: Long): MovementFix? {
        val location = client.lastLocation.awaitOrNull() ?: return null
        val ageMs = ageOf(location)
        return if (ageMs <= maxAgeMs) location.toFix(ageMs, "last") else null
    }

    @SuppressLint("MissingPermission")
    private suspend fun current(timeoutMs: Long): MovementFix? {
        val token = CancellationTokenSource()
        val location = withTimeoutOrNull(timeoutMs) {
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, token.token).awaitOrNull()
        }
        if (location == null) token.cancel()
        return location?.toFix(ageOf(location), "current")
    }

    private fun ageOf(location: Location): Long =
        ((clock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0L)

    private fun Location.toFix(ageMs: Long, source: String) = MovementFix(
        latitude = latitude, longitude = longitude, accuracyM = accuracy, ageMs = ageMs, source = source
    )

    private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
        addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
        addOnCanceledListener { if (continuation.isActive) continuation.resume(null) }
    }

    private companion object {
        const val TAG = "MovementFixSource"
    }
}
