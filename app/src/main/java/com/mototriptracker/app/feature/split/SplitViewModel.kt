package com.mototriptracker.app.feature.split

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.database.dao.ProcessedTrackPointDao
import com.mototriptracker.app.core.database.dao.RawTrackPointDao
import com.mototriptracker.app.core.database.dao.TripDao
import com.mototriptracker.app.core.database.dao.TripPartDao
import com.mototriptracker.app.core.database.entity.ProcessedTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripPartEntity
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.domain.GeoPoint
import com.mototriptracker.app.domain.TripSplitPlanner
import com.mototriptracker.app.domain.haversineMeters
import com.mototriptracker.app.domain.processing.ProcessingEngine
import com.mototriptracker.app.domain.simplifyRoute
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import com.mototriptracker.app.worker.TripSplitter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * EDT-002/F0.9 §12 SPL-01/UX-10: previews both halves of a split *before*
 * anything is committed. Everything the slider needs (the Trip's processed
 * points, each one's raw `elapsedRealtimeNanos`, cumulative distance) is
 * loaded once by [load]; moving the cut only re-runs the cheap parts - the
 * same [TripSplitPlanner] the real split uses, so the previewed durations are
 * exactly what [TripSplitter] will commit, not a separate approximation.
 *
 * Same explicit-kick-off pattern as `TripDetailViewModel.load` (no Nav3
 * `SavedStateHandle` plumbing in this codebase, ADR-011).
 */
@HiltViewModel
class SplitViewModel @Inject constructor(
    private val tripDao: TripDao,
    private val tripPartDao: TripPartDao,
    private val rawTrackPointDao: RawTrackPointDao,
    private val processedTrackPointDao: ProcessedTrackPointDao,
    private val tripSplitter: TripSplitter
) : ViewModel() {

    private class LoadedTrip(
        val tripId: String,
        val parts: List<TripPartEntity>,
        val processed: List<ProcessedTrackPointEntity>,
        val elapsedByIndex: LongArray,
        val captureSequences: Map<String, List<Long>>,
        /** `cumulativeMeters[i]` = route length from the first processed point through point `i`, ADR-016 gap edges excluded like `TripMetricsCalculator`. */
        val cumulativeMeters: DoubleArray,
        val routePoints: List<GeoPoint>
    )

    private val _uiState = MutableStateFlow<SplitUiState>(SplitUiState.Loading)
    val uiState: StateFlow<SplitUiState> = _uiState.asStateFlow()

    private var loaded: LoadedTrip? = null
    private var cutIndex: Int = 0

    // Always reload on entry: these ViewModels outlive a single visit (Navigation 3 entries
    // here share the Activity's store), so a guard on "same tripId" made a cancelled
    // screen reopen with the previous slider position - found on-device.
    fun load(tripId: String) {
        loaded = null
        _uiState.value = SplitUiState.Loading
        viewModelScope.launch {
            _uiState.value = buildLoaded(tripId)?.let { trip ->
                loaded = trip
                cutIndex = (trip.processed.size / 2).coerceIn(MIN_CUT_INDEX, trip.processed.size - MIN_CUT_INDEX)
                readyState(trip, cutIndex) ?: SplitUiState.NotAvailable
            } ?: SplitUiState.NotAvailable
        }
    }

    fun onCutIndexChanged(index: Int) {
        val trip = loaded ?: return
        val clamped = index.coerceIn(MIN_CUT_INDEX, trip.processed.size - MIN_CUT_INDEX)
        if (clamped == cutIndex) return
        readyState(trip, clamped)?.let {
            cutIndex = clamped
            _uiState.value = it
        }
    }

    /** Suspend, awaited by the Screen: a failed split (ADR-015's precondition re-check) must not look like a success. */
    suspend fun split(): Boolean {
        val trip = loaded ?: return false
        val ready = _uiState.value as? SplitUiState.Ready ?: return false
        if (ready.isSplitting) return false
        _uiState.value = ready.copy(isSplitting = true)
        val point = trip.processed[cutIndex]
        val sequence = point.sourceSequenceNumber
        // A storage failure must not escape into the Screen's coroutine and crash
        // the app: report it like any other refused split and re-enable the buttons.
        val success = try {
            sequence != null && tripSplitter.split(trip.tripId, point.sourceCaptureId, sequence) is TripSplitter.Result.Success
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            false
        }
        if (!success) _uiState.value = ready.copy(isSplitting = false)
        return success
    }

    private suspend fun buildLoaded(tripId: String): LoadedTrip? {
        val trip = tripDao.findById(tripId) ?: return null
        if (trip.status != TripStatus.COMPLETED || trip.deletedAt != null) return null

        val processed = processedTrackPointDao.findAllByTripAndVersion(tripId, TripProcessingWorker.CURRENT_PROCESSING_VERSION)
        if (processed.size < 2 * MIN_CUT_INDEX) return null

        val parts = tripPartDao.findAllByTrip(tripId)
        // ADR-016: a part with no known end would make the duration unknown - never show it as 0.
        if (parts.isEmpty() || parts.any { it.endElapsedRealtimeNanos == null }) return null
        val rawByCapture = parts.map { it.captureId }.distinct().associateWith { rawTrackPointDao.findAllByCapture(it) }
        val elapsedBySource = rawByCapture.values.flatten().associate { (it.captureId to it.sequenceNumber) to it.elapsedRealtimeNanos }
        val elapsedByIndex = LongArray(processed.size)
        for ((index, point) in processed.withIndex()) {
            val sequence = point.sourceSequenceNumber ?: return null
            elapsedByIndex[index] = elapsedBySource[point.sourceCaptureId to sequence] ?: return null
        }

        val cumulative = DoubleArray(processed.size)
        for (i in 1 until processed.size) {
            val previous = processed[i - 1]
            val current = processed[i]
            val isGapBoundary = current.pointRole == ProcessingEngine.POINT_ROLE_GAP_BOUNDARY
            cumulative[i] = cumulative[i - 1] +
                if (isGapBoundary) 0.0 else haversineMeters(previous.latitude, previous.longitude, current.latitude, current.longitude)
        }

        return LoadedTrip(
            tripId = tripId,
            parts = parts,
            processed = processed,
            elapsedByIndex = elapsedByIndex,
            captureSequences = rawByCapture.mapValues { (_, points) -> points.map { it.sequenceNumber } },
            cumulativeMeters = cumulative,
            routePoints = simplifyRoute(processed.map { GeoPoint(it.latitude, it.longitude) })
        )
    }

    private fun readyState(trip: LoadedTrip, index: Int): SplitUiState.Ready? {
        val cutPoint = trip.processed[index]
        val sequence = cutPoint.sourceSequenceNumber ?: return null
        val plan = TripSplitPlanner.plan(
            parts = trip.parts,
            cut = TripSplitPlanner.Cut(cutPoint.sourceCaptureId, sequence, trip.elapsedByIndex[index]),
            captureSequences = trip.captureSequences[cutPoint.sourceCaptureId].orEmpty()
        ) ?: return null

        return SplitUiState.Ready(
            routePoints = trip.routePoints,
            cutPoint = GeoPoint(cutPoint.latitude, cutPoint.longitude),
            cutIndex = index,
            minCutIndex = MIN_CUT_INDEX,
            maxCutIndex = trip.processed.size - MIN_CUT_INDEX,
            // The one segment crossing the cut belongs to neither half
            // (TripSplitPlanner's class comment), so it is in neither number.
            first = SplitPartPreview(trip.cumulativeMeters[index - 1], plan.first.totalDurationMs()),
            second = SplitPartPreview(trip.cumulativeMeters.last() - trip.cumulativeMeters[index], plan.second.totalDurationMs())
        )
    }

    private fun List<TripPartEntity>.totalDurationMs(): Long = sumOf { part ->
        (checkNotNull(part.endElapsedRealtimeNanos) - part.startElapsedRealtimeNanos) / 1_000_000
    }

    private companion object {
        const val MIN_CUT_INDEX = TripSplitter.MIN_POINTS_PER_HALF
    }
}
