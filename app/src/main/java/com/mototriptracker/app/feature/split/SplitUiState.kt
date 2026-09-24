package com.mototriptracker.app.feature.split

import com.mototriptracker.app.domain.GeoPoint

/** EDT-002/F0.9 §12 SPL-01: one half of the proposed split, as previewed before confirming (UX-10). */
data class SplitPartPreview(val distanceMeters: Double, val durationMs: Long)

sealed interface SplitUiState {
    data object Loading : SplitUiState

    /** Not splittable: unknown/trashed Trip, not processed yet, or too few points for two drawable halves. */
    data object NotAvailable : SplitUiState

    data class Ready(
        /** Already simplified for drawing (`domain.simplifyRoute`) - the cut marker uses the exact [cutPoint], not one of these. */
        val routePoints: List<GeoPoint>,
        val cutPoint: GeoPoint,
        /** Position of the cut within the Trip's processed points; the slider's value. */
        val cutIndex: Int,
        val minCutIndex: Int,
        val maxCutIndex: Int,
        val first: SplitPartPreview,
        val second: SplitPartPreview,
        val isSplitting: Boolean = false
    ) : SplitUiState
}
