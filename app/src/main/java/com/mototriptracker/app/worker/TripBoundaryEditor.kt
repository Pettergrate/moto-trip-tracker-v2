package com.mototriptracker.app.worker

import androidx.room.withTransaction
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.dao.ProcessedTrackPointDao
import com.mototriptracker.app.core.database.dao.RawTrackPointDao
import com.mototriptracker.app.core.database.dao.TripDao
import com.mototriptracker.app.core.database.dao.TripEditOperationDao
import com.mototriptracker.app.core.database.dao.TripLineageLinkDao
import com.mototriptracker.app.core.database.dao.TripPartDao
import com.mototriptracker.app.core.database.entity.TripEditOperationEntity
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.database.entity.TripLineageLinkEntity
import com.mototriptracker.app.core.model.EditOperationType
import com.mototriptracker.app.core.model.LineageRole
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.domain.TripBoundaryPlanner
import com.mototriptracker.app.tracking.processing.ProcessingScheduler
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import javax.inject.Inject

/**
 * EDT-003/domain-data-model.md §8.5 + §8.2 (`BOUNDARY_EDIT`: one input -> one
 * revised output): trims a COMPLETED Trip's start and/or end inward. Like
 * [TripMerger]/[TripSplitter] it never touches a RawTrackPoint (ADR-005;
 * §8.5: excluded points stay in the capture), lives in `worker/` for the same
 * `withTransaction`/ADR-013 reason, re-validates inside the transaction
 * (ADR-015) and enqueues reprocessing only after commit.
 *
 * The result is a *new* Trip (the source becomes `SUPERSEDED`, lineage
 * recorded) rather than an in-place edit of the source's parts - the same
 * data-only-toward-undo posture as merge/split, and it keeps `TripProcessingWorker`'s
 * `KEEP`-by-tripId scheduling correct (a brand-new id is never "already
 * processed"). Everything about the ride other than its extent - name,
 * notes, favorite, motorcycle, route - is carried over unchanged.
 *
 * Bounds are `(captureId, sequenceNumber)` of *processed* points, inclusive,
 * with at least [MIN_POINTS] kept and something actually removed.
 */
class TripBoundaryEditor @Inject constructor(
    private val database: MotoTripDatabase,
    private val tripDao: TripDao,
    private val tripPartDao: TripPartDao,
    private val rawTrackPointDao: RawTrackPointDao,
    private val processedTrackPointDao: ProcessedTrackPointDao,
    private val tripEditOperationDao: TripEditOperationDao,
    private val tripLineageLinkDao: TripLineageLinkDao,
    private val processingScheduler: ProcessingScheduler,
    private val clock: Clock,
    private val idGenerator: IdGenerator
) {
    sealed interface Result {
        data class Success(val newTripId: String) : Result

        /** ADR-015: source no longer COMPLETED, not processed, or the bounds invalid/unchanged; nothing was written. */
        data object PreconditionFailed : Result
    }

    data class PointRef(val captureId: String, val sequenceNumber: Long)

    suspend fun trim(tripId: String, start: PointRef, end: PointRef): Result {
        var newTripId: String? = null
        var representativeCapture: String? = null

        database.withTransaction {
            val source = tripDao.findById(tripId)
            if (source == null || source.status != TripStatus.COMPLETED || source.deletedAt != null) return@withTransaction

            val processed = processedTrackPointDao.findAllByTripAndVersion(tripId, TripProcessingWorker.CURRENT_PROCESSING_VERSION)
            val startIndex = processed.indexOfFirst { it.sourceCaptureId == start.captureId && it.sourceSequenceNumber == start.sequenceNumber }
            val endIndex = processed.indexOfFirst { it.sourceCaptureId == end.captureId && it.sourceSequenceNumber == end.sequenceNumber }
            if (startIndex < 0 || endIndex < 0 || endIndex - startIndex + 1 < MIN_POINTS) return@withTransaction
            if (startIndex == 0 && endIndex == processed.size - 1) return@withTransaction

            val rawBySource = listOf(start.captureId, end.captureId).distinct()
                .flatMap { rawTrackPointDao.findAllByCapture(it) }
                .associateBy { it.captureId to it.sequenceNumber }
            val startRaw = rawBySource[start.captureId to start.sequenceNumber] ?: return@withTransaction
            val endRaw = rawBySource[end.captureId to end.sequenceNumber] ?: return@withTransaction

            val newParts = TripBoundaryPlanner.plan(
                parts = tripPartDao.findAllByTrip(tripId),
                // An untouched side keeps the source's own edge (see TripBoundaryPlanner).
                start = if (startIndex > 0) TripBoundaryPlanner.Bound(start.captureId, start.sequenceNumber, startRaw.elapsedRealtimeNanos) else null,
                end = if (endIndex < processed.size - 1) TripBoundaryPlanner.Bound(end.captureId, end.sequenceNumber, endRaw.elapsedRealtimeNanos) else null
            ) ?: return@withTransaction

            val now = clock.wallClockMillis()
            val id = idGenerator.newId()
            tripDao.insert(
                source.copy(
                    id = id,
                    // "When the ride ended" (every Trip's createdAt): moves to
                    // the new last point only if the end was actually trimmed.
                    createdAt = if (endIndex < processed.size - 1) endRaw.capturedAt else source.createdAt,
                    updatedAt = now
                )
            )
            tripPartDao.insertAll(newParts.mapIndexed { index, part -> part.copy(id = idGenerator.newId(), tripId = id, orderIndex = index) })
            tripDao.markSuperseded(source.id, now)

            val operationId = idGenerator.newId()
            tripEditOperationDao.insert(
                TripEditOperationEntity(id = operationId, type = EditOperationType.BOUNDARY_EDIT, createdAt = now, undoneAt = null, notes = null)
            )
            tripLineageLinkDao.insertAll(
                listOf(
                    TripLineageLinkEntity(operationId, source.id, LineageRole.INPUT),
                    TripLineageLinkEntity(operationId, id, LineageRole.OUTPUT)
                )
            )

            newTripId = id
            representativeCapture = newParts.first().captureId
        }

        val id = newTripId ?: return Result.PreconditionFailed
        processingScheduler.enqueueTripProcessing(id, representativeCapture!!)
        return Result.Success(id)
    }

    companion object {
        /** A trimmed Trip still needs a drawable route: at least two processed points. */
        const val MIN_POINTS = 2
    }
}
