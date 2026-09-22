package com.mototriptracker.app.feature.tripdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.database.dao.ProcessedTrackPointDao
import com.mototriptracker.app.core.database.dao.TripDao
import com.mototriptracker.app.core.database.dao.TripStatisticsDao
import com.mototriptracker.app.domain.GeoPoint
import com.mototriptracker.app.domain.simplifyRoute
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * HIS-001/F0.9 §9: HIS-02. No Nav3 `SavedStateHandle`/route-args plumbing
 * exists in this codebase (ADR-011/UI-001 deliberately skipped
 * `lifecycle-viewmodel-navigation3`, which has no stable release) - [load]
 * is called from the screen's own `LaunchedEffect(tripId)`, the same
 * explicit-kick-off pattern `HomeViewModel.refreshCapabilityMode()` already
 * established, rather than inventing a second mechanism for this one case.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TripDetailViewModel @Inject constructor(
    private val tripDao: TripDao,
    private val tripStatisticsDao: TripStatisticsDao,
    private val processedTrackPointDao: ProcessedTrackPointDao,
    private val clock: Clock
) : ViewModel() {

    private val tripIdFlow = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TripDetailUiState> = tripIdFlow.flatMapLatest { tripId ->
        if (tripId == null) {
            flowOf(TripDetailUiState.Loading)
        } else {
            combine(
                tripDao.observeById(tripId),
                tripStatisticsDao.observeByTripAndVersion(tripId, TripProcessingWorker.CURRENT_PROCESSING_VERSION),
                processedTrackPointDao.observeAllByTripAndVersion(tripId, TripProcessingWorker.CURRENT_PROCESSING_VERSION)
            ) { trip, statistics, processedPoints ->
                if (trip == null) {
                    TripDetailUiState.NotFound
                } else {
                    // MAP-001: simplified once here, not per-recomposition -
                    // ADR-006 still holds, this never becomes a source of
                    // truth for distance/speed, only what the map draws.
                    val routePoints = simplifyRoute(processedPoints.map { GeoPoint(it.latitude, it.longitude) })
                    TripDetailUiState.Loaded(
                        tripId = trip.id,
                        displayName = trip.name ?: fallbackTripName(trip.createdAt),
                        isUserNamed = trip.name != null,
                        dateTimeLabel = formatDateTime(trip.createdAt),
                        isFavorite = trip.isFavorite,
                        distanceMeters = statistics?.distanceM,
                        totalDurationMs = statistics?.totalDurationMs,
                        movingDurationMs = statistics?.movingDurationMs,
                        stoppedDurationMs = statistics?.stoppedDurationMs,
                        manualPauseDurationMs = statistics?.manualPauseDurationMs,
                        maxSpeedMps = statistics?.maxSpeedMps,
                        averageSpeedMps = statistics?.averageSpeedMps,
                        averageMovingSpeedMps = statistics?.averageMovingSpeedMps,
                        minElevationM = statistics?.minElevationM,
                        maxElevationM = statistics?.maxElevationM,
                        ascentM = statistics?.ascentM,
                        descentM = statistics?.descentM,
                        routePoints = routePoints
                    )
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TripDetailUiState.Loading)

    fun load(tripId: String) {
        tripIdFlow.value = tripId
    }

    /** FR-HIS-004. A blank [newName] reverts to the generated fallback rather than persisting an empty string. */
    fun onRename(newName: String) {
        val tripId = tripIdFlow.value ?: return
        val trimmed = newName.trim()
        viewModelScope.launch {
            tripDao.rename(tripId, trimmed.ifEmpty { null }, clock.wallClockMillis())
        }
    }

    /** FAV-001/FR-FAV-001. */
    fun onToggleFavorite() {
        val tripId = tripIdFlow.value ?: return
        val currentIsFavorite = (uiState.value as? TripDetailUiState.Loaded)?.isFavorite ?: return
        viewModelScope.launch {
            tripDao.setFavorite(tripId, !currentIsFavorite, clock.wallClockMillis())
        }
    }

    /** TRS-001/FR-HIS-005/F0.9 §13: soft-delete only - the caller navigates back after this, since this Trip drops out of every normal list immediately. */
    fun onTrash() {
        val tripId = tripIdFlow.value ?: return
        viewModelScope.launch {
            tripDao.trash(tripId, deletedAt = clock.wallClockMillis(), updatedAt = clock.wallClockMillis())
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
