package com.mototriptracker.app.feature.active

/** F0.9 §6: TRP-01. [Loading] is the brief initial state before the first DB read lands - distinct from [NoActiveTrip] so the screen doesn't auto-navigate back before it has ever actually observed a trip. */
sealed interface ActiveTripUiState {
    data object Loading : ActiveTripUiState
    data object NoActiveTrip : ActiveTripUiState
    data class Active(
        val isPaused: Boolean,
        val distanceMeters: Double,
        val elapsedMs: Long,
        val pauseElapsedMs: Long?
    ) : ActiveTripUiState
}
