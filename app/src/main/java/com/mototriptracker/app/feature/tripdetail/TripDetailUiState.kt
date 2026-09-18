package com.mototriptracker.app.feature.tripdetail

/** F0.9 §9.1: HIS-02's own state - loading/not-found are real cases (a deep link or a deleted-elsewhere Trip), not just placeholders. */
sealed interface TripDetailUiState {
    data object Loading : TripDetailUiState
    data object NotFound : TripDetailUiState
    data class Loaded(
        val tripId: String,
        val displayName: String,
        /** Whether [displayName] is a user-given name or the generated fallback - the rename dialog should start blank, not pre-filled with the fallback text, when this is false. */
        val isUserNamed: Boolean,
        val dateTimeLabel: String,
        val isFavorite: Boolean,
        val distanceMeters: Double?,
        val totalDurationMs: Long?,
        val movingDurationMs: Long?,
        val stoppedDurationMs: Long?,
        val manualPauseDurationMs: Long?,
        val maxSpeedMps: Double?,
        val averageSpeedMps: Double?,
        val averageMovingSpeedMps: Double?,
        val minElevationM: Double?,
        val maxElevationM: Double?,
        val ascentM: Double?,
        val descentM: Double?
    ) : TripDetailUiState
}
