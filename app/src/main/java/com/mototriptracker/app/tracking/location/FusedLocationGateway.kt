package com.mototriptracker.app.tracking.location

import android.annotation.SuppressLint
import android.location.Location
import android.os.Build
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.model.LocationSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/**
 * TRK-002: the only class that touches `com.google.android.gms.location.*`
 * (ADR-013's spirit — [LocationGateway] itself stays framework-free).
 *
 * F0.5 §3.1/§8.1's TRACKING profile: PRIORITY_HIGH_ACCURACY, no
 * `setMaxUpdateDelayMillis` (GPS-012 — no aggressive batching as baseline),
 * `minUpdateDistance=0` (F0.5 §9 — a nonzero baseline risks losing curves/
 * slow movement, and no field-tested value exists yet). The 2s interval is
 * F0.5 §8.3's own hypothesis range (1-2s), not a closed value — like TRK-001's
 * `DetectorVersion(0)`/`LocationProfileVersion(0)`, this is an explicit,
 * documented placeholder for the one profile this task needs (manual-start
 * TRACKING only); F0.6 field tests close the real value, and DET/AUTO tasks
 * add the other F0.5 §8.1 profiles (CANDIDATE_START burst, relaxed IDLE,
 * etc.) when they exist.
 */
class FusedLocationGateway @Inject constructor(
    private val fusedClient: FusedLocationProviderClient,
    private val clock: Clock
) : LocationGateway {

    @SuppressLint("MissingPermission")
    override fun locationUpdates(): Flow<LocationSample> = callbackFlow {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val receivedAtElapsedRealtimeNanos = clock.elapsedRealtimeNanos()
                for (location in result.locations) {
                    val sample = location.toSampleOrNull(receivedAtElapsedRealtimeNanos)
                    if (sample != null) {
                        trySend(sample)
                    } else {
                        Log.w(
                            TAG,
                            "Dropping a location fix with no horizontal accuracy - " +
                                "ADR-016 forbids fabricating one to make it fit Raw Track's schema"
                        )
                    }
                }
            }
        }

        fusedClient.requestLocationUpdates(TRACKING_REQUEST, callback, Looper.getMainLooper())
        awaitClose { fusedClient.removeLocationUpdates(callback) }
    }

    private fun Location.toSampleOrNull(receivedAtElapsedRealtimeNanos: Long): LocationSample? {
        if (!hasAccuracy()) return null
        return LocationSample(
            wallTimeEpochMs = time,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            receivedAtElapsedRealtimeNanos = receivedAtElapsedRealtimeNanos,
            latitude = latitude,
            longitude = longitude,
            horizontalAccuracyM = accuracy,
            requestProfileId = TRACKING_PROFILE_ID,
            altitudeEllipsoidM = if (hasAltitude()) altitude else null,
            altitudeMslM = if (Build.VERSION.SDK_INT >= 34 && hasMslAltitude()) mslAltitudeMeters else null,
            verticalAccuracyM = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
            speedMps = if (hasSpeed()) speed else null,
            speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
            bearingDeg = if (hasBearing()) bearing else null,
            bearingAccuracyDeg = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
            provider = provider,
            isMock = if (Build.VERSION.SDK_INT >= 31) isMock else @Suppress("DEPRECATION") isFromMockProvider
        )
    }

    companion object {
        private const val TAG = "FusedLocationGateway"
        const val TRACKING_PROFILE_ID = "tracking-manual-v0"
        private const val TRACKING_INTERVAL_MS = 2000L

        private val TRACKING_REQUEST: LocationRequest =
            LocationRequest.Builder(TRACKING_INTERVAL_MS)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMinUpdateDistanceMeters(0f)
                .build()
    }
}
