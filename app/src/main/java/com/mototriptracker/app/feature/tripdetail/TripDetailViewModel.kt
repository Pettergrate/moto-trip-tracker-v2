package com.mototriptracker.app.feature.tripdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.database.dao.ProcessedTrackPointDao
import com.mototriptracker.app.core.database.dao.TripDao
import com.mototriptracker.app.core.database.dao.TripStatisticsDao
import com.mototriptracker.app.domain.GeoPoint
import com.mototriptracker.app.domain.simplifyRoute
import com.mototriptracker.app.feature.common.buildDataQualityNote
import com.mototriptracker.app.feature.common.fallbackTripName
import com.mototriptracker.app.feature.common.formatDateTime
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import com.mototriptracker.app.worker.TripBoundaryEditor
import com.mototriptracker.app.worker.TripMerger
import com.mototriptracker.app.worker.TripSplitter
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
    private val tripMerger: TripMerger,
    private val clock: Clock
) : ViewModel() {

    private val tripIdFlow = MutableStateFlow<String?>(null)

    /**
     * EDT-001: a one-shot suspend lookup rather than a third reactive Flow -
     * adjacency depends on *other* Trips' rows, which [tripDao.observeById]
     * won't react to, and staleness here (another Trip changing status while
     * this exact screen is open) is an acceptable, low-likelihood edge case
     * in a single-user app, the same tradeoff [onRename]/[onTrash] already
     * make by being one-shot suspend calls rather than Flows.
     */
    private val adjacentTripsFlow = MutableStateFlow(AdjacentTrips(null, null))

    val uiState: StateFlow<TripDetailUiState> = tripIdFlow.flatMapLatest { tripId ->
        if (tripId == null) {
            flowOf(TripDetailUiState.Loading)
        } else {
            combine(
                tripDao.observeById(tripId),
                tripStatisticsDao.observeByTripAndVersion(tripId, TripProcessingWorker.CURRENT_PROCESSING_VERSION),
                processedTrackPointDao.observeAllByTripAndVersion(tripId, TripProcessingWorker.CURRENT_PROCESSING_VERSION),
                adjacentTripsFlow
            ) { trip, statistics, processedPoints, adjacent ->
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
                        startElevationM = statistics?.startElevationM,
                        endElevationM = statistics?.endElevationM,
                        ascentM = statistics?.ascentM,
                        descentM = statistics?.descentM,
                        calculatedAtLabel = statistics?.let { formatDateTime(it.computedAt) },
                        qualityNote = statistics?.let { buildDataQualityNote(it.rejectedPointCount, it.gapCount) },
                        routePoints = routePoints,
                        previousTripCandidate = adjacent.previous,
                        nextTripCandidate = adjacent.next,
                        canSplit = processedPoints.size >= 2 * TripSplitter.MIN_POINTS_PER_HALF,
                        canTrim = processedPoints.size >= TripBoundaryEditor.MIN_POINTS + 1
                    )
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TripDetailUiState.Loading)

    fun load(tripId: String) {
        tripIdFlow.value = tripId
        viewModelScope.launch {
            val trip = tripDao.findById(tripId) ?: return@launch
            val previous = tripDao.findPreviousCompleted(trip.createdAt)
            val next = tripDao.findNextCompleted(trip.createdAt)
            adjacentTripsFlow.value = AdjacentTrips(
                previous = previous?.let { candidate -> MergeCandidate(candidate.id, candidate.name ?: fallbackTripName(candidate.createdAt)) },
                next = next?.let { candidate -> MergeCandidate(candidate.id, candidate.name ?: fallbackTripName(candidate.createdAt)) }
            )
        }
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

    /**
     * EDT-001. Unlike [onTrash], this is a suspend function the caller
     * awaits rather than fire-and-forget: [TripMerger] can genuinely fail
     * its precondition check (ADR-015), and navigating back as if it
     * succeeded when it didn't would silently hide that from the user. The
     * caller (the Screen's own coroutine scope) only navigates back when
     * this returns true.
     */
    suspend fun mergeWithPrevious(): Boolean = mergeWithAdjacent(previousOrNext = true)

    suspend fun mergeWithNext(): Boolean = mergeWithAdjacent(previousOrNext = false)

    private suspend fun mergeWithAdjacent(previousOrNext: Boolean): Boolean {
        val tripId = tripIdFlow.value ?: return false
        val candidate = if (previousOrNext) adjacentTripsFlow.value.previous else adjacentTripsFlow.value.next
        val otherTripId = candidate?.tripId ?: return false
        return tripMerger.merge(tripId, otherTripId) is TripMerger.Result.Success
    }

    private data class AdjacentTrips(val previous: MergeCandidate?, val next: MergeCandidate?)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
