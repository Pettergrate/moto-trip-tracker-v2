package com.mototriptracker.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.database.dao.TripDao
import com.mototriptracker.app.core.database.dao.TripStatisticsDao
import com.mototriptracker.app.feature.common.TripSummaryUi
import com.mototriptracker.app.feature.common.fallbackTripName
import com.mototriptracker.app.feature.common.formatDateTime
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * HIS-001/F0.9 §8: HIS-01, the full chronological history (Home's own list
 * is only a 3-row preview - see `HomeViewModel.recentTripsFlow`'s own KDoc).
 * Deliberately reads DAOs directly, no repository layer - same posture as
 * every other ViewModel in this codebase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val tripDao: TripDao,
    private val tripStatisticsDao: TripStatisticsDao,
    private val clock: Clock
) : ViewModel() {

    private val sortOrderFlow = MutableStateFlow(SortOrder.NEWEST_FIRST)

    val uiState: StateFlow<HistoryUiState> = sortOrderFlow.flatMapLatest { sortOrder ->
        val tripsFlow = when (sortOrder) {
            SortOrder.NEWEST_FIRST -> tripDao.observeAllDescending()
            SortOrder.OLDEST_FIRST -> tripDao.observeAllAscending()
        }
        tripsFlow.flatMapLatest { trips ->
            if (trips.isEmpty()) {
                flowOf(emptyList())
            } else {
                // A Flow per trip's statistics, not a one-shot suspend fetch:
                // UI-001's own `recentTripsFlow` shipped with exactly that bug
                // first - Room only invalidates `observeAllDescending`'s Flow
                // when the `trip` table changes, never when `trip_statistics`
                // does, so a freshly-finished trip's numbers would never
                // appear without this.
                combine(
                    trips.map { trip ->
                        tripStatisticsDao.observeByTripAndVersion(trip.id, TripProcessingWorker.CURRENT_PROCESSING_VERSION)
                    }
                ) { statisticsByTrip ->
                    trips.mapIndexed { index, trip ->
                        val statistics = statisticsByTrip[index]
                        TripSummaryUi(
                            tripId = trip.id,
                            displayName = trip.name ?: fallbackTripName(trip.createdAt),
                            dateTimeLabel = formatDateTime(trip.createdAt),
                            distanceMeters = statistics?.distanceM,
                            durationMs = statistics?.totalDurationMs,
                            isFavorite = trip.isFavorite
                        )
                    }
                }
            }
        }.map { tripsUi -> HistoryUiState(trips = tripsUi, sortOrder = sortOrder) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HistoryUiState())

    fun onToggleSortOrder() {
        sortOrderFlow.value = when (sortOrderFlow.value) {
            SortOrder.NEWEST_FIRST -> SortOrder.OLDEST_FIRST
            SortOrder.OLDEST_FIRST -> SortOrder.NEWEST_FIRST
        }
    }

    /** FAV-001/FR-FAV-001. */
    fun onToggleFavorite(tripId: String, currentIsFavorite: Boolean) {
        viewModelScope.launch {
            tripDao.setFavorite(tripId, !currentIsFavorite, clock.wallClockMillis())
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
