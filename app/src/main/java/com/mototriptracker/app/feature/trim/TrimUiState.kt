package com.mototriptracker.app.feature.trim

import com.mototriptracker.app.domain.GeoPoint

/** EDT-003: what the trimmed Trip would be, previewed before anything is committed (same UX-10 spirit as Split). */
data class TrimPreview(val distanceMeters: Double, val durationMs: Long, val removedDurationMs: Long)

sealed interface TrimUiState {
    data object Loading : TrimUiState

    /** Unknown/trashed Trip, not processed yet, or too few points to trim. */
    data object NotAvailable : TrimUiState

    data class Ready(
        /** Already simplified for drawing; the start/end markers use the exact points below, not these. */
        val routePoints: List<GeoPoint>,
        val startPoint: GeoPoint,
        val endPoint: GeoPoint,
        /** Positions within the Trip's processed points - the RangeSlider's two values. */
        val startIndex: Int,
        val endIndex: Int,
        val maxIndex: Int,
        val kept: TrimPreview,
        /** False while the range still covers the whole Trip - nothing to save. */
        val hasChanges: Boolean,
        val isSaving: Boolean = false
    ) : TrimUiState
}
