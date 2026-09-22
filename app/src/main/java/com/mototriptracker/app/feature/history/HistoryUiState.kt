package com.mototriptracker.app.feature.history

import com.mototriptracker.app.feature.common.TripSummaryUi

/** FR-HIS-008: the one sort order beyond default chronology this task implements. */
enum class SortOrder { NEWEST_FIRST, OLDEST_FIRST }

/** F0.9 §8: HIS-01's list state. */
data class HistoryUiState(
    val trips: List<TripSummaryUi> = emptyList(),
    val sortOrder: SortOrder = SortOrder.NEWEST_FIRST
)
