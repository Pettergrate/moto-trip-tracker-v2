package com.mototriptracker.app.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.database.dao.TripDao
import com.mototriptracker.app.core.database.dao.TripStatisticsDao
import com.mototriptracker.app.feature.common.fallbackTripName
import com.mototriptracker.app.feature.common.formatDateTime
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import com.mototriptracker.app.worker.TrashPurgeWorker
import com.mototriptracker.app.worker.TripPurger
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
 * TRS-001/F0.9 §14: the dedicated Trash list, reached from Settings. Same
 * DAOs-direct/per-trip-statistics-Flow shape as `HistoryViewModel`/
 * `FavoritesViewModel`, for the same Room-invalidation reason.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val tripDao: TripDao,
    private val tripStatisticsDao: TripStatisticsDao,
    private val tripPurger: TripPurger,
    private val clock: Clock
) : ViewModel() {

    val uiState: StateFlow<TrashUiState> = tripDao.observeTrashedDescending().flatMapLatest { trips ->
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
                    val trashedAt = requireNotNull(trip.deletedAt) { "a TRASHED Trip must have deletedAt set" }
                    TrashTripUi(
                        tripId = trip.id,
                        displayName = trip.name ?: fallbackTripName(trip.createdAt),
                        dateTimeLabel = formatDateTime(trip.createdAt),
                        distanceMeters = statistics?.distanceM,
                        durationMs = statistics?.totalDurationMs,
                        purgeDateLabel = formatDateTime(trashedAt + TrashPurgeWorker.RETENTION_MS)
                    )
                }
            }
        }
    }.map { tripsUi -> TrashUiState(trips = tripsUi) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TrashUiState())

    /** FR-HIS-005. Reverses [com.mototriptracker.app.feature.tripdetail.TripDetailViewModel.onTrash] - nothing was ever destroyed, so this is a plain status/deletedAt flip back. */
    fun onRestore(tripId: String) {
        viewModelScope.launch {
            tripDao.restore(tripId, updatedAt = clock.wallClockMillis())
        }
    }

    /** FR-EDT-006/UX-11: an explicit, separately-confirmed action - never the default outcome of a single tap. */
    fun onDeleteForever(tripId: String) {
        viewModelScope.launch {
            tripPurger.purge(tripId)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
