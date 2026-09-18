package com.mototriptracker.app.feature.history

/** FR-HIS-008: the one sort order beyond default chronology this task implements. */
enum class SortOrder { NEWEST_FIRST, OLDEST_FIRST }

/** F0.9 §8: HIS-01's list state. */
data class HistoryUiState(
    val trips: List<HistoryTripUi> = emptyList(),
    val sortOrder: SortOrder = SortOrder.NEWEST_FIRST
)

/** F0.9 §8.1's minimum row fields: name, date/time, distance, duration, favorite. */
data class HistoryTripUi(
    val tripId: String,
    val displayName: String,
    val dateTimeLabel: String,
    val distanceMeters: Double?,
    val durationMs: Long?,
    val isFavorite: Boolean
)
