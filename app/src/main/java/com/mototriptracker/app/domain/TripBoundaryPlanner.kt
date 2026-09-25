package com.mototriptracker.app.domain

import com.mototriptracker.app.core.database.entity.TripPartEntity

/**
 * EDT-003/domain-data-model.md §8.5: pure planning of a boundary correction
 * ("trim") - which TripParts, with which adjusted ranges, make up the Trip
 * once its start and/or end are moved inward. No database, no Android
 * (ADR-013); shared by the Trim preview and the real edit
 * ([com.mototriptracker.app.worker.TripBoundaryEditor]).
 *
 * Both bounds are *inclusive* raw points ("the first/last point this Trip
 * keeps") - unlike a split's cut, which sits between two points. Points
 * outside stay in the capture untouched (§8.5: "no se borran los puntos
 * excluidos"); the Trip simply stops referencing them.
 */
object TripBoundaryPlanner {

    data class Bound(val captureId: String, val sequenceNumber: Long, val elapsedRealtimeNanos: Long)

    /**
     * @param parts the source Trip's parts, ordered by `orderIndex`.
     * @param start the new first kept point, or `null` to leave the start
     * exactly as the source had it; likewise [end]. An untouched side must not
     * silently lose the second or two between Start and the first fix (or the
     * last fix and Finish) - only a genuine trim removes time.
     * @return the new part list (source ids/`tripId`/`orderIndex` still
     * present - the caller reassigns them), or `null` if a bound isn't inside
     * any part or the bounds are out of order.
     */
    fun plan(parts: List<TripPartEntity>, start: Bound?, end: Bound?): List<TripPartEntity>? {
        val startIndex = if (start == null) 0 else parts.indexOfFirst { it.covers(start.captureId, start.sequenceNumber) }
        val endIndex = if (end == null) parts.lastIndex else parts.indexOfFirst { it.covers(end.captureId, end.sequenceNumber) }
        if (startIndex < 0 || endIndex < 0 || startIndex > endIndex) return null
        if (start != null && end != null && startIndex == endIndex &&
            (start.sequenceNumber > end.sequenceNumber || start.elapsedRealtimeNanos > end.elapsedRealtimeNanos)
        ) {
            return null
        }

        return parts.subList(startIndex, endIndex + 1).mapIndexed { index, part ->
            var adjusted = part
            if (start != null && index == 0) {
                adjusted = adjusted.copy(startElapsedRealtimeNanos = start.elapsedRealtimeNanos, startSequenceNumber = start.sequenceNumber)
            }
            if (end != null && index == endIndex - startIndex) {
                adjusted = adjusted.copy(endElapsedRealtimeNanos = end.elapsedRealtimeNanos, endSequenceNumber = end.sequenceNumber)
            }
            adjusted
        }
    }
}
