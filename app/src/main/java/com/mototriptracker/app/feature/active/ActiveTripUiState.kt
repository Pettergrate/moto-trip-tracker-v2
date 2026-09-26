package com.mototriptracker.app.feature.active

import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import com.mototriptracker.app.tracking.persistence.PersistenceState

/** F0.9 §6: TRP-01. [Loading] is the brief initial state before the first DB read lands - distinct from [NoActiveTrip] so the screen doesn't auto-navigate back before it has ever actually observed a trip. */
sealed interface ActiveTripUiState {
    data object Loading : ActiveTripUiState
    data object NoActiveTrip : ActiveTripUiState
    data class Active(
        val isPaused: Boolean,
        val distanceMeters: Double,
        val elapsedMs: Long,
        val pauseElapsedMs: Long?,
        val signal: ActiveTripSignal = ActiveTripSignal.OK,
        val persistence: PersistenceState = PersistenceState.HEALTHY
    ) : ActiveTripUiState
}

/**
 * REC-005 / F0.10 §21: the DEGRADED state's location half, as the rider should see it. The trip is
 * still recording in every case - none of these ends or pauses it (§13.2).
 */
enum class ActiveTripSignal {
    OK,

    /** No fix has arrived yet: nothing has been lost, the recording is just waiting for its first one. */
    SEARCHING,

    /** Fixes stopped arriving past the gap threshold with Location Services on (tunnel, garage, OEM battery policy...). */
    LOST_NO_FIX,

    /** Fixes stopped arriving and Location Services are switched off. */
    LOST_LOCATION_SERVICES_OFF
}

/**
 * A paused trip is asked for no route evidence, so it never reads as a loss; otherwise an open
 * location gap wins over the initial wait, and "no points yet" is only ever "searching".
 */
internal fun activeTripSignal(isPaused: Boolean, pointCount: Int, openGapReason: String?): ActiveTripSignal = when {
    isPaused -> ActiveTripSignal.OK
    openGapReason == TrackingSessionCoordinator.REASON_LOCATION_SERVICES_OFF -> ActiveTripSignal.LOST_LOCATION_SERVICES_OFF
    openGapReason != null -> ActiveTripSignal.LOST_NO_FIX
    pointCount == 0 -> ActiveTripSignal.SEARCHING
    else -> ActiveTripSignal.OK
}
