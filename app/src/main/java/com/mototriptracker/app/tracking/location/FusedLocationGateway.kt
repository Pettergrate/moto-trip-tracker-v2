package com.mototriptracker.app.tracking.location

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.model.LocationSample
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val profileSelector: LocationProfileSelector,
    @ApplicationContext private val context: Context
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

        fun register() {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
                // Never silent again: a refused request is the difference between recording and not (no coordinates here).
                .addOnFailureListener { error -> Log.w(TAG, "Location request refused: ${error::class.simpleName}") }
        }
        register()

        // REC-005 follow-up: a trip started while Location was off never received a fix after Location was turned
        // on - measured on the phone, the app held no request with the system for minutes, with the GPS idle. So a
        // request made while Location is off cannot be trusted to survive: ask again the moment it is switched on.
        // Asking again is harmless when the first request was fine (same callback, same request). One switch sends
        // several broadcasts (the mode one plus one per provider), so act on the off -> on transition, once.
        var wasEnabled = isLocationEnabled()
        val locationModeChanged = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val enabled = isLocationEnabled()
                val cameOn = enabled && !wasEnabled
                wasEnabled = enabled
                if (!cameOn) return
                fusedClient.removeLocationUpdates(callback)
                register()
            }
        }
        ContextCompat.registerReceiver(
            context,
            locationModeChanged,
            IntentFilter().apply {
                addAction(MODE_CHANGED_ACTION)
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        awaitClose {
            context.unregisterReceiver(locationModeChanged)
            fusedClient.removeLocationUpdates(callback)
        }
    }

    private fun isLocationEnabled(): Boolean =
        context.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false

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

        /** `LocationManager.MODE_CHANGED_ACTION` is public only from API 28 (minSdk is 26); the broadcast itself is older. */
        internal const val MODE_CHANGED_ACTION = "android.location.MODE_CHANGED"
    }
}
