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
 * F0.5 §3.1/§8.1's TRACKING profile: PRIORITY_HIGH_ACCURACY always; interval/
 * min-distance/batching come from [profileSelector] (EXP-003), defaulting to
 * `ExperimentLocationProfiles.DEFAULT` (F0.5 §8.3's own 1-2s hypothesis
 * range's midpoint, 2s, `minUpdateDistance=0`, no batching — F0.5 §9's own
 * reasoning: a nonzero baseline risks losing curves/slow movement, and no
 * field-tested value existed yet) whenever no field-test session has
 * selected an experiment profile. [LocationSample.requestProfileId] is
 * stamped from whatever profile was actually resolved for this specific
 * capture, not a fixed constant — EXP-003's whole point is comparing real
 * campaigns, so a Raw Track point's own profile provenance has to be honest.
 */
class FusedLocationGateway @Inject constructor(
    private val fusedClient: FusedLocationProviderClient,
    private val clock: Clock,
    private val profileSelector: LocationProfileSelector
) : LocationGateway {

    @SuppressLint("MissingPermission")
    override fun locationUpdates(): Flow<LocationSample> = callbackFlow {
        // Resolved once per capture, not per-sample: F0.6's campaigns fix a
        // profile for a whole ride, never change it mid-ride.
        val profile = profileSelector.current()
        val request = LocationRequest.Builder(profile.intervalMillis)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateDistanceMeters(profile.minUpdateDistanceMeters)
            .apply { if (profile.maxUpdateDelayMillis > 0) setMaxUpdateDelayMillis(profile.maxUpdateDelayMillis) }
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val receivedAtElapsedRealtimeNanos = clock.elapsedRealtimeNanos()
                for (location in result.locations) {
                    val sample = location.toSampleOrNull(receivedAtElapsedRealtimeNanos, profile.id)
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

        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { fusedClient.removeLocationUpdates(callback) }
    }

    private fun Location.toSampleOrNull(receivedAtElapsedRealtimeNanos: Long, requestProfileId: String): LocationSample? {
        if (!hasAccuracy()) return null
        return LocationSample(
            wallTimeEpochMs = time,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            receivedAtElapsedRealtimeNanos = receivedAtElapsedRealtimeNanos,
            latitude = latitude,
            longitude = longitude,
            horizontalAccuracyM = accuracy,
            requestProfileId = requestProfileId,
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
    }
}
