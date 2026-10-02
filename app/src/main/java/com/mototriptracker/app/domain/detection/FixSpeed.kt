package com.mototriptracker.app.domain.detection

import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.domain.haversineMeters

/**
 * DET-008: the one place that says how fast a location fix says the phone is moving, so
 * [CandidateStartEngine] and [CandidateStopEngine] agree on it.
 *
 * The reported Doppler speed is preferred - on a GPS fix it is far steadier than a speed computed from two
 * positions. When a fix carries none (or an unusably uncertain one), the speed between two consecutive fixes stands
 * in, but only if the phone moved by more than the two fixes' own accuracy: jitter around a standing point must
 * never read as movement (DP-001 - one noisy number is not evidence).
 */
object FixSpeed {
    /** A fix this uncertain says nothing about speed (a coarse network fix, a cold-start guess). */
    const val MAX_USABLE_ACCURACY_M = 100f
    const val MAX_USABLE_SPEED_ACCURACY_MPS = 3f
    const val MIN_DERIVED_INTERVAL_MS = 1_000L
    const val MAX_DERIVED_INTERVAL_MS = 15_000L

    /** `null` = this fix (with its predecessor) cannot say how fast the phone is moving. */
    fun effectiveSpeedMps(previous: LocationSample?, current: LocationSample): Float? {
        if (current.horizontalAccuracyM > MAX_USABLE_ACCURACY_M) return null

        val reported = current.speedMps
        if (reported != null && reported >= 0f) {
            val accuracy = current.speedAccuracyMps
            if (accuracy == null || accuracy <= MAX_USABLE_SPEED_ACCURACY_MPS) return reported
        }

        if (previous == null || previous.horizontalAccuracyM > MAX_USABLE_ACCURACY_M) return null
        val intervalMs = (current.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000
        if (intervalMs < MIN_DERIVED_INTERVAL_MS || intervalMs > MAX_DERIVED_INTERVAL_MS) return null
        val distanceM = haversineMeters(previous.latitude, previous.longitude, current.latitude, current.longitude)
        if (distanceM <= previous.horizontalAccuracyM + current.horizontalAccuracyM) return null
        return (distanceM / (intervalMs / 1000.0)).toFloat()
    }
}
