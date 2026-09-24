package com.mototriptracker.app.domain

import com.mototriptracker.app.core.database.entity.TripPartEntity

/**
 * EDT-002/domain-data-model.md §8.4: pure planning of *which TripParts* end
 * up in each half of a split - no database, no Android (ADR-013). The
 * caller ([com.mototriptracker.app.worker.TripSplitter]) turns the result
 * into real rows; the Split preview uses the very same plan to show
 * durations, so what the user previews is what gets committed.
 *
 * The cut sits *between* two raw points: the first half keeps everything
 * up to `cutSequence - 1`, the second half starts at `cutSequence`. The two
 * halves never share a raw point - F0.7 §7.2 forbids two parts of one Trip
 * counting the same capture range twice, and a shared boundary point would
 * do exactly that the moment a user merged the halves back together. The
 * one segment that crosses the cut belongs to neither half.
 */
object TripSplitPlanner {

    data class Cut(
        val captureId: String,
        val sequenceNumber: Long,
        /** The raw point at [sequenceNumber]'s own `elapsedRealtimeNanos` - the shared time boundary, so the two halves' durations add up to the original's. */
        val elapsedRealtimeNanos: Long
    )

    /**
     * Parts are the source's own, copied with adjusted ranges - `id`,
     * `tripId` and `orderIndex` are still the *source's* and must be
     * reassigned by the caller.
     */
    data class Plan(val first: List<TripPartEntity>, val second: List<TripPartEntity>)

    /**
     * @param parts the source Trip's parts, ordered by `orderIndex`.
     * @param captureSequences every raw `sequenceNumber` of [Cut.captureId],
     * ascending - needed to tell whether the cut is the very first raw point
     * of its part (then the whole part goes to the second half rather than
     * leaving the first half an empty slice of it).
     * @return `null` if the cut isn't inside any part, or would leave one
     * half with no part at all.
     */
    fun plan(parts: List<TripPartEntity>, cut: Cut, captureSequences: List<Long>): Plan? {
        val cutPartIndex = parts.indexOfFirst { it.contains(cut.captureId, cut.sequenceNumber) }
        if (cutPartIndex < 0) return null
        val cutPart = parts[cutPartIndex]

        val firstSequenceInPart = captureSequences.firstOrNull { cutPart.contains(cut.captureId, it) }
        val cutIsFirstPointOfItsPart = firstSequenceInPart == cut.sequenceNumber

        val first = parts.subList(0, cutPartIndex).toMutableList()
        val second = parts.subList(cutPartIndex + 1, parts.size).toMutableList()

        if (cutIsFirstPointOfItsPart) {
            second.add(0, cutPart)
        } else {
            first += cutPart.copy(
                endElapsedRealtimeNanos = cut.elapsedRealtimeNanos,
                endSequenceNumber = cut.sequenceNumber - 1
            )
            second.add(
                0,
                cutPart.copy(
                    startElapsedRealtimeNanos = cut.elapsedRealtimeNanos,
                    startSequenceNumber = cut.sequenceNumber
                )
            )
        }

        if (first.isEmpty() || second.isEmpty()) return null
        return Plan(first, second)
    }

    private fun TripPartEntity.contains(captureId: String, sequenceNumber: Long): Boolean =
        this.captureId == captureId &&
            (startSequenceNumber?.let { sequenceNumber >= it } ?: true) &&
            (endSequenceNumber?.let { sequenceNumber <= it } ?: true)
}
