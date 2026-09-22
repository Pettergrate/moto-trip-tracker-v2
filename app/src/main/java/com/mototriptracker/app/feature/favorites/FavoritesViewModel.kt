package com.mototriptracker.app.feature.favorites

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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * FAV-001/FR-FAV-002: the dedicated favorites list. Mirrors
 * `HistoryViewModel`'s own shape (deliberately reads DAOs directly, no
 * repository layer, a per-trip statistics Flow rather than a one-shot fetch
 * for the same Room-invalidation reason documented there) filtered to
 * `Trip.isFavorite`, with no separate sort-order control - FR-FAV-002 only
 * asks for a way to browse favorites, not a second copy of HIS-01's sorting UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val tripDao: TripDao,
    private val tripStatisticsDao: TripStatisticsDao,
    private val clock: Clock
) : ViewModel() {

    val uiState: StateFlow<FavoritesUiState> = tripDao.observeFavoritesDescending().flatMapLatest { trips ->
        if (trips.isEmpty()) {
            flowOf(emptyList())
        } else {
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
    }.map { tripsUi -> FavoritesUiState(trips = tripsUi) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), FavoritesUiState())

    /** FR-FAV-001. Unfavoriting from this screen removes the row reactively once Room re-queries. */
    fun onToggleFavorite(tripId: String, currentIsFavorite: Boolean) {
        viewModelScope.launch {
            tripDao.setFavorite(tripId, !currentIsFavorite, clock.wallClockMillis())
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
