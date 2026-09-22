package com.mototriptracker.app.feature.favorites

import com.mototriptracker.app.feature.common.TripSummaryUi

/** FAV-001/FR-FAV-002: the dedicated favorites list state. */
data class FavoritesUiState(val trips: List<TripSummaryUi> = emptyList())
