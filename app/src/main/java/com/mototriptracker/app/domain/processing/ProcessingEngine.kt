package com.mototriptracker.app.domain.processing

import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.database.entity.LocationGapEntity
import com.mototriptracker.app.core.database.entity.PointAssessmentEntity
import com.mototriptracker.app.core.database.entity.ProcessedTrackPointEntity
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripPartEntity
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.core.model.TrackPointDecision
import javax.inject.Inject

/**
 * PRC-001: F0.5 §16's pipeline (`RAW LOCATION → VALIDATE/ORDER → QUALITY
 * CHECK → GAP DETECTION → PROCESSED TRACK`), scoped to what F0.5 §7.1
 * actually licenses *without* field-tested thresholds: "no existirá
 * inicialmente una regla global como si accuracy > X → borrar punto". So
 * this v1 rejects only what's unambiguous with zero invented numbers —
 * non-monotonic `elapsedRealtimeNanos` (F0.5 §6.1) — and otherwise accepts
 * every point (ADR-006: raw evidence isn't filtered away just for being
 * mediocre). Speed-spike/teleport/accuracy-cutoff rejection needs F0.6 field
 * data before any threshold here would be defensible; that's a later
 * `processingVersion`, not this one pretending to have evidence it doesn't.
 *
 * No Android dependency (ADR-013) — [RawTrackPointEntity] et al. are plain
 * Kotlin data (their Room annotations live in `core.database.entity`, not
 * imported here).
 */
class ProcessingEngine @Inject constructor(
    private val idGenerator: IdGenerator
) {
    data class Result(
        val assessments: List<PointAssessmentEntity>,
        val processedPoints: List<ProcessedTrackPointEntity>,
        val gaps: List<LocationGapEntity>
    )

    /**
     * [parts] must already be ordered by [TripPartEntity.orderIndex];
     * each capture's points in [rawPointsByCapture] must already be ordered
     * by `sequenceNumber` ascending (both true of what the DAOs return).
     * `processedPoints`' `orderIndex` runs continuously across every part,
     * matching F0.7 §9.1 ("geometría... del Trip", not per-capture).
     */
    fun process(
        tripId: String,
        processingVersion: ProcessingVersion,
        parts: List<TripPartEntity>,
        rawPointsByCapture: Map<String, List<RawTrackPointEntity>>
    ): Result {
        val assessments = mutableListOf<PointAssessmentEntity>()
        val processedPoints = mutableListOf<ProcessedTrackPointEntity>()
        val gaps = mutableListOf<LocationGapEntity>()
        var lastAccepted: RawTrackPointEntity? = null
        var nextOrderIndex = 0

        for (part in parts) {
            val pointsInPart = (rawPointsByCapture[part.captureId] ?: emptyList()).filter { point ->
                (part.startSequenceNumber?.let { point.sequenceNumber >= it } ?: true) &&
                    (part.endSequenceNumber?.let { point.sequenceNumber <= it } ?: true)
            }

            for (point in pointsInPart) {
                val (decision, reasonCode) = assess(point, lastAccepted)
                assessments += PointAssessmentEntity(
                    captureId = point.captureId,
                    sequenceNumber = point.sequenceNumber,
                    processingVersion = processingVersion,
                    decision = decision,
                    reasonCodes = reasonCode
                )
                if (decision != TrackPointDecision.ACCEPTED) continue

                val previous = lastAccepted
                val gap = if (previous != null) detectGap(tripId, processingVersion, previous, point) else null
                if (gap != null) gaps += gap

                processedPoints += ProcessedTrackPointEntity(
                    tripId = tripId,
                    processingVersion = processingVersion,
                    orderIndex = nextOrderIndex++,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    sourceCaptureId = point.captureId,
                    sourceSequenceNumber = point.sequenceNumber,
                    pointRole = if (gap != null) POINT_ROLE_GAP_BOUNDARY else null
                )
                lastAccepted = point
            }
        }

        return Result(assessments, processedPoints, gaps)
    }

    /** Compares against the last *accepted* point, not the previous raw one — a rejected point must not poison the baseline for the next comparison. */
    private fun assess(point: RawTrackPointEntity, lastAccepted: RawTrackPointEntity?): Pair<TrackPointDecision, String> {
        if (lastAccepted == null) return TrackPointDecision.ACCEPTED to REASON_ACCEPTED
        return when {
            point.elapsedRealtimeNanos < lastAccepted.elapsedRealtimeNanos ->
                TrackPointDecision.REJECTED to REASON_OUT_OF_ORDER
            point.elapsedRealtimeNanos == lastAccepted.elapsedRealtimeNanos ->
                TrackPointDecision.REJECTED to REASON_DUPLICATE
            else -> TrackPointDecision.ACCEPTED to REASON_ACCEPTED
        }
    }

    private fun detectGap(
        tripId: String,
        processingVersion: ProcessingVersion,
        previous: RawTrackPointEntity,
        current: RawTrackPointEntity
    ): LocationGapEntity? {
        val elapsedMs = (current.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000
        if (elapsedMs < GAP_THRESHOLD_MS) return null
        return LocationGapEntity(
            id = idGenerator.newId(),
            tripId = tripId,
            processingVersion = processingVersion,
            startedAt = previous.capturedAt,
            endedAt = current.capturedAt,
            startSourceRef = "${previous.captureId}:${previous.sequenceNumber}",
            endSourceRef = "${current.captureId}:${current.sequenceNumber}",
            durationMs = elapsedMs,
            reasonCode = REASON_GAP_NO_FIX
        )
    }

    companion object {
        /**
         * F0.5 §8.3's own hypothesis interval for TRACKING is 1-2s; a gap
         * meaningfully longer than that is a loss of evidence, not ordinary
         * jitter. No F0.6 field data validates this exact number yet —
         * explicit, documented placeholder, same posture as TRK-001/TRK-002's
         * version-0 stamps. Revisit once EXP-003 closes a real value.
         */
        const val GAP_THRESHOLD_MS = 30_000L

        const val REASON_ACCEPTED = "ACCEPTED"
        const val REASON_OUT_OF_ORDER = "REJECTED_OUT_OF_ORDER"
        const val REASON_DUPLICATE = "REJECTED_DUPLICATE"
        const val REASON_GAP_NO_FIX = "GAP_NO_FIX"
        const val POINT_ROLE_GAP_BOUNDARY = "GAP_BOUNDARY"
    }
}
