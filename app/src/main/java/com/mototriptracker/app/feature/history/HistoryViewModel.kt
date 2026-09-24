package com.mototriptracker.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.database.dao.ProcessedTrackPointDao
import com.mototriptracker.app.core.database.dao.TripDao
import com.mototriptracker.app.core.database.dao.TripStatisticsDao
import com.mototriptracker.app.domain.GeoPoint
import com.mototriptracker.app.feature.common.TripSummaryUi
import com.mototriptracker.app.feature.common.fallbackTripName
import com.mototriptracker.app.feature.common.formatDateTime
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Calendar
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * HIS-001/HIS-002/F0.9 §8: HIS-01, the full chronological history (Home's
 * own list is only a 3-row preview - see `HomeViewModel.recentTripsFlow`'s
 * own KDoc). Deliberately reads DAOs directly, no repository layer - same
 * posture as every other ViewModel in this codebase.
 *
 * Search/filter/extra-sort (`FR-HIS-006/007`, the distance/duration halves
 * of `FR-HIS-008`) are all plain Kotlin operations over the one already-
 * loaded list, not additional DB queries - this app has no pagination and a
 * personal-use trip count, so filtering/sorting a few dozen-to-hundred
 * already-`combine`d rows in memory is simpler and just as fast as pushing
 * it into SQL, and search-by-display-name specifically *has* to happen here
 * rather than in SQL: a Trip with no custom name has `name = NULL` in the
 * DB and only gets its real, searchable text (`fallbackTripName`) computed
 * in Kotlin, so a `WHERE name LIKE ...` query would silently miss every
 * un-renamed Trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val tripDao: TripDao,
    private val tripStatisticsDao: TripStatisticsDao,
    private val processedTrackPointDao: ProcessedTrackPointDao,
    private val clock: Clock
) : ViewModel() {

    private val sortOrderFlow = MutableStateFlow(SortOrder.NEWEST_FIRST)
    private val searchQueryFlow = MutableStateFlow("")
    private val dateFilterFlow = MutableStateFlow(DateFilter.ALL_TIME)
    private val favoritesOnlyFlow = MutableStateFlow(false)

    val uiState: StateFlow<HistoryUiState> = combine(
        sortOrderFlow, searchQueryFlow, dateFilterFlow, favoritesOnlyFlow
    ) { sortOrder, searchQuery, dateFilter, favoritesOnly ->
        Filters(sortOrder, searchQuery, dateFilter, favoritesOnly)
    }.flatMapLatest { filters ->
        // Oldest-first is the one case that still needs its own DB ordering
        // (reversing a `NEWEST_FIRST`-loaded list client-side would work too,
        // but this keeps that one existing, already-tested query path
        // untouched rather than refactoring working code for its own sake).
        val tripsFlow = if (filters.sortOrder == SortOrder.OLDEST_FIRST) {
            tripDao.observeAllAscending()
        } else {
            tripDao.observeAllDescending()
        }
        tripsFlow.flatMapLatest { trips ->
            if (trips.isEmpty()) {
                flowOf(HistoryUiState(trips = emptyList(), sortOrder = filters.sortOrder, searchQuery = filters.searchQuery, dateFilter = filters.dateFilter, favoritesOnly = filters.favoritesOnly))
            } else {
                val tripIds = trips.map { it.id }
                combine(
                    combine(
                        trips.map { trip -> tripStatisticsDao.observeByTripAndVersion(trip.id, TripProcessingWorker.CURRENT_PROCESSING_VERSION) }
                    ) { it },
                    processedTrackPointDao.observeSampledByTrips(tripIds, TripProcessingWorker.CURRENT_PROCESSING_VERSION)
                ) { statisticsByTrip, sampledPoints ->
                    val pointsByTrip = sampledPoints.groupBy { it.tripId }
                    val rows = trips.mapIndexed { index, trip ->
                        val statistics = statisticsByTrip[index]
                        TripSummaryUi(
                            tripId = trip.id,
                            displayName = trip.name ?: fallbackTripName(trip.createdAt),
                            dateTimeLabel = formatDateTime(trip.createdAt),
                            distanceMeters = statistics?.distanceM,
                            durationMs = statistics?.totalDurationMs,
                            isFavorite = trip.isFavorite,
                            routePoints = pointsByTrip[trip.id]?.map { GeoPoint(it.latitude, it.longitude) } ?: emptyList()
                        )
                    }
                    val createdAtByTripId = trips.associate { it.id to it.createdAt }
                    val filtered = rows.filter { row ->
                        (!filters.favoritesOnly || row.isFavorite) &&
                            (filters.searchQuery.isBlank() || row.displayName.contains(filters.searchQuery, ignoreCase = true)) &&
                            matchesDateFilter(createdAtByTripId.getValue(row.tripId), filters.dateFilter, clock.wallClockMillis())
                    }
                    HistoryUiState(
                        trips = applySort(filtered, filters.sortOrder),
                        sortOrder = filters.sortOrder,
                        searchQuery = filters.searchQuery,
                        dateFilter = filters.dateFilter,
                        favoritesOnly = filters.favoritesOnly
                    )
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HistoryUiState())

    fun onSortOrderSelected(sortOrder: SortOrder) {
        sortOrderFlow.value = sortOrder
    }

    fun onSearchQueryChanged(query: String) {
        searchQueryFlow.value = query
    }

    fun onDateFilterSelected(dateFilter: DateFilter) {
        dateFilterFlow.value = dateFilter
    }

    fun onFavoritesOnlyToggled() {
        favoritesOnlyFlow.value = !favoritesOnlyFlow.value
    }

    /** FAV-001/FR-FAV-001. */
    fun onToggleFavorite(tripId: String, currentIsFavorite: Boolean) {
        viewModelScope.launch {
            tripDao.setFavorite(tripId, !currentIsFavorite, clock.wallClockMillis())
        }
    }

    /** DB order is left untouched for NEWEST_FIRST/OLDEST_FIRST; the distance/duration orders are the only ones actually re-sorted here. */
    private fun applySort(rows: List<TripSummaryUi>, sortOrder: SortOrder): List<TripSummaryUi> = when (sortOrder) {
        SortOrder.NEWEST_FIRST, SortOrder.OLDEST_FIRST -> rows
        SortOrder.LONGEST_DISTANCE -> rows.sortedByDescending { it.distanceMeters ?: Double.NEGATIVE_INFINITY }
        SortOrder.SHORTEST_DISTANCE -> rows.sortedBy { it.distanceMeters ?: Double.POSITIVE_INFINITY }
        SortOrder.LONGEST_DURATION -> rows.sortedByDescending { it.durationMs ?: Long.MIN_VALUE }
        SortOrder.SHORTEST_DURATION -> rows.sortedBy { it.durationMs ?: Long.MAX_VALUE }
    }

    private fun matchesDateFilter(createdAt: Long, dateFilter: DateFilter, nowMillis: Long): Boolean = when (dateFilter) {
        DateFilter.ALL_TIME -> true
        DateFilter.THIS_WEEK -> createdAt >= startOfWeekMillis(nowMillis)
        DateFilter.THIS_MONTH -> createdAt >= startOfMonthMillis(nowMillis)
    }

    private fun startOfWeekMillis(nowMillis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        clearTimeOfDay()
    }.timeInMillis

    private fun startOfMonthMillis(nowMillis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.DAY_OF_MONTH, 1)
        clearTimeOfDay()
    }.timeInMillis

    private fun Calendar.clearTimeOfDay() {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private data class Filters(val sortOrder: SortOrder, val searchQuery: String, val dateFilter: DateFilter, val favoritesOnly: Boolean)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
