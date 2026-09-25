package com.mototriptracker.app.feature.trim

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
import com.mototriptracker.app.domain.TripBoundaryPlanner
import com.mototriptracker.app.domain.haversineMeters
import com.mototriptracker.app.domain.processing.ProcessingEngine
import com.mototriptracker.app.domain.simplifyRoute
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import com.mototriptracker.app.worker.TripBoundaryEditor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * EDT-003: the Trim screen's state. Same shape as `SplitViewModel` (load the
 * Trip's processed points + each one's raw `elapsedRealtimeNanos` once, keep
 * cumulative distance, re-run only the cheap parts as the sliders move) - and
 * the preview's duration comes from the very same [TripBoundaryPlanner] the
 * real edit uses, so what's shown is what gets committed.
 */
@HiltViewModel
class TrimViewModel @Inject constructor(
    private val tripDao: TripDao,
    private val tripPartDao: TripPartDao,
    private val rawTrackPointDao: RawTrackPointDao,
    private val processedTrackPointDao: ProcessedTrackPointDao,
    private val tripBoundaryEditor: TripBoundaryEditor
) : ViewModel() {

    private class LoadedTrip(
        val tripId: String,
        val parts: List<TripPartEntity>,
        val processed: List<ProcessedTrackPointEntity>,
        val elapsedByIndex: LongArray,
        val cumulativeMeters: DoubleArray,
        val originalDurationMs: Long,
        val routePoints: List<GeoPoint>
    )

    private val _uiState = MutableStateFlow<TrimUiState>(TrimUiState.Loading)
    val uiState: StateFlow<TrimUiState> = _uiState.asStateFlow()

    private var loaded: LoadedTrip? = null
    private var startIndex = 0
    private var endIndex = 0

    // Always reload on entry: these ViewModels outlive a single visit (Navigation 3 entries
    // here share the Activity's store), so a guard on "same tripId" made a cancelled
    // screen reopen with the previous handles - found on-device.
    fun load(tripId: String) {
        loaded = null
        _uiState.value = TrimUiState.Loading
        viewModelScope.launch {
            _uiState.value = buildLoaded(tripId)?.let { trip ->
                loaded = trip
                startIndex = 0
                endIndex = trip.processed.size - 1
                readyState(trip, startIndex, endIndex) ?: TrimUiState.NotAvailable
            } ?: TrimUiState.NotAvailable
        }
    }

    fun onRangeChanged(start: Int, end: Int) {
        val trip = loaded ?: return
        val last = trip.processed.size - 1
        val newStart = start.coerceIn(0, last - 1)
        val newEnd = end.coerceIn(newStart + 1, last)
        if (newStart == startIndex && newEnd == endIndex) return
        readyState(trip, newStart, newEnd)?.let {
            startIndex = newStart
            endIndex = newEnd
            _uiState.value = it
        }
    }

    /** Suspend, awaited by the Screen: a failed edit (ADR-015's precondition re-check) must not look like a success. */
    suspend fun save(): Boolean {
        val trip = loaded ?: return false
        val ready = _uiState.value as? TrimUiState.Ready ?: return false
        if (ready.isSaving || !ready.hasChanges) return false
        _uiState.value = ready.copy(isSaving = true)
        val start = trip.processed[startIndex]
        val end = trip.processed[endIndex]
        val startSequence = start.sourceSequenceNumber
        val endSequence = end.sourceSequenceNumber
        // A storage failure must not escape into the Screen's coroutine and crash
        // the app: report it like any other refused edit and re-enable the buttons.
        val success = try {
            startSequence != null && endSequence != null &&
                tripBoundaryEditor.trim(
                    trip.tripId,
                    TripBoundaryEditor.PointRef(start.sourceCaptureId, startSequence),
                    TripBoundaryEditor.PointRef(end.sourceCaptureId, endSequence)
                ) is TripBoundaryEditor.Result.Success
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            false
        }
        if (!success) _uiState.value = ready.copy(isSaving = false)
        return success
    }

    private suspend fun buildLoaded(tripId: String): LoadedTrip? {
        val trip = tripDao.findById(tripId) ?: return null
        if (trip.status != TripStatus.COMPLETED || trip.deletedAt != null) return null

        val processed = processedTrackPointDao.findAllByTripAndVersion(tripId, TripProcessingWorker.CURRENT_PROCESSING_VERSION)
        if (processed.size < MIN_TRIMMABLE_POINTS) return null

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
            val isGapBoundary = processed[i].pointRole == ProcessingEngine.POINT_ROLE_GAP_BOUNDARY
            cumulative[i] = cumulative[i - 1] + if (isGapBoundary) {
                0.0
            } else {
                haversineMeters(processed[i - 1].latitude, processed[i - 1].longitude, processed[i].latitude, processed[i].longitude)
            }
        }

        return LoadedTrip(
            tripId = tripId,
            parts = parts,
            processed = processed,
            elapsedByIndex = elapsedByIndex,
            cumulativeMeters = cumulative,
            originalDurationMs = parts.durationMs(),
            routePoints = simplifyRoute(processed.map { GeoPoint(it.latitude, it.longitude) })
        )
    }

    private fun readyState(trip: LoadedTrip, start: Int, end: Int): TrimUiState.Ready? {
        val startPoint = trip.processed[start]
        val endPoint = trip.processed[end]
        val startSequence = startPoint.sourceSequenceNumber ?: return null
        val endSequence = endPoint.sourceSequenceNumber ?: return null
        val keptParts = TripBoundaryPlanner.plan(
            parts = trip.parts,
            start = if (start > 0) TripBoundaryPlanner.Bound(startPoint.sourceCaptureId, startSequence, trip.elapsedByIndex[start]) else null,
            end = if (end < trip.processed.size - 1) TripBoundaryPlanner.Bound(endPoint.sourceCaptureId, endSequence, trip.elapsedByIndex[end]) else null
        ) ?: return null

        val keptDurationMs = keptParts.durationMs()
        return TrimUiState.Ready(
            routePoints = trip.routePoints,
            startPoint = GeoPoint(startPoint.latitude, startPoint.longitude),
            endPoint = GeoPoint(endPoint.latitude, endPoint.longitude),
            startIndex = start,
            endIndex = end,
            maxIndex = trip.processed.size - 1,
            kept = TrimPreview(
                distanceMeters = trip.cumulativeMeters[end] - trip.cumulativeMeters[start],
                durationMs = keptDurationMs,
                removedDurationMs = (trip.originalDurationMs - keptDurationMs).coerceAtLeast(0L)
            ),
            hasChanges = start > 0 || end < trip.processed.size - 1
        )
    }

    private fun List<TripPartEntity>.durationMs(): Long = sumOf { part ->
        (checkNotNull(part.endElapsedRealtimeNanos) - part.startElapsedRealtimeNanos) / 1_000_000
    }

    companion object {
        /** Keeping >=2 points and removing at least one needs >=3 processed points. */
        const val MIN_TRIMMABLE_POINTS = TripBoundaryEditor.MIN_POINTS + 1
    }
}
