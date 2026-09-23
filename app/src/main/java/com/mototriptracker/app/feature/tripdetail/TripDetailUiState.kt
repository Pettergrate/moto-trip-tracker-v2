package com.mototriptracker.app.feature.tripdetail

import com.mototriptracker.app.domain.GeoPoint

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
        /** MET-001/FR-MET-009. */
        val startElevationM: Double?,
        val endElevationM: Double?,
        val ascentM: Double?,
        val descentM: Double?,
        /** MET-001: null until statistics exist at all - "Calculated {date}" only shows once there's something to date. */
        val calculatedAtLabel: String?,
        /** MET-001: null when there's nothing real to report - see `buildDataQualityNote`. */
        val qualityNote: String?,
        /** MAP-001: already simplified (`domain.simplifyRoute`); empty for a Trip whose processing hasn't produced points yet, which `TripRouteMap` itself renders as the honest "Map not available yet" placeholder. */
        val routePoints: List<GeoPoint> = emptyList()
    ) : TripDetailUiState
}
