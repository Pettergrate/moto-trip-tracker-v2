package com.mototriptracker.app.feature.history

import com.mototriptracker.app.feature.common.TripSummaryUi

/**
 * HIS-001/HIS-002/FR-HIS-008: "additional sorting beyond default chronology
 * where useful." NEWEST_FIRST/OLDEST_FIRST are the DB's own two orderings
 * (`TripDao.observeAllDescending`/`observeAllAscending`); the distance/
 * duration orders are a client-side re-sort of that same already-loaded,
 * personal-scale list - not a third/fourth DB query - with an unknown
 * (statistics not computed yet) metric always sorting last regardless of
 * direction, never treated as zero.
 */
enum class SortOrder { NEWEST_FIRST, OLDEST_FIRST, LONGEST_DISTANCE, SHORTEST_DISTANCE, LONGEST_DURATION, SHORTEST_DURATION }

/** HIS-002/FR-HIS-007: the one filterable date dimension this task implements - "this week"/"this month" are the common, low-effort quick filters; a full custom date-range picker is left for whoever finds real demand for it. */
enum class DateFilter { ALL_TIME, THIS_WEEK, THIS_MONTH }

/** F0.9 §8: HIS-01's list state. */
data class HistoryUiState(
    val trips: List<TripSummaryUi> = emptyList(),
    val sortOrder: SortOrder = SortOrder.NEWEST_FIRST,
    val searchQuery: String = "",
    val dateFilter: DateFilter = DateFilter.ALL_TIME,
    val favoritesOnly: Boolean = false
)
