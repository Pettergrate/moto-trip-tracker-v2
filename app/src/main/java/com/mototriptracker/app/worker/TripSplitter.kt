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
import com.mototriptracker.app.core.database.entity.TripPartEntity
import com.mototriptracker.app.core.model.EditOperationType
import com.mototriptracker.app.core.model.LineageRole
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.domain.TripSplitPlanner
import com.mototriptracker.app.tracking.processing.ProcessingScheduler
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import javax.inject.Inject

/**
 * EDT-002/domain-data-model.md §8.4: splits one COMPLETED Trip in two at a
 * chosen raw point. Like [TripMerger] it never touches a RawTrackPoint
 * (ADR-005) - both halves get new TripPart rows over the *same* capture
 * ranges - and lives here rather than in `domain/` for the same reason
 * (needs `MotoTripDatabase.withTransaction`; ADR-013). The pure "which parts
 * go where" logic is [TripSplitPlanner], shared with the Split preview.
 *
 * The source Trip becomes `SUPERSEDED` (never deleted), with its lineage
 * recorded - the same data-only-toward-undo posture as [TripMerger].
 * Statistics are recomputed asynchronously for *both* new Trips by the
 * existing [TripProcessingWorker], after this transaction commits (ADR-015).
 *
 * The cut is identified by the raw point's `(captureId, sequenceNumber)`,
 * not by a list index: it is a stable reference that survives reprocessing,
 * unlike a position in a derived list. It must be an *accepted* (processed)
 * point of the Trip with at least [MIN_POINTS_PER_HALF] processed points on
 * each side, so neither half is left without a drawable route.
 */
class TripSplitter @Inject constructor(
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
        data class Success(val firstTripId: String, val secondTripId: String) : Result

        /** ADR-015: revalidated inside the transaction - source no longer COMPLETED, not yet processed, or the cut no longer valid; nothing was written. */
        data object PreconditionFailed : Result
    }

    suspend fun split(tripId: String, cutCaptureId: String, cutSequenceNumber: Long): Result {
        var firstTripId: String? = null
        var secondTripId: String? = null
        var firstRepresentativeCapture: String? = null
        var secondRepresentativeCapture: String? = null

        database.withTransaction {
            val source = tripDao.findById(tripId)
            if (source == null || source.status != TripStatus.COMPLETED || source.deletedAt != null) return@withTransaction

            val processed = processedTrackPointDao.findAllByTripAndVersion(tripId, TripProcessingWorker.CURRENT_PROCESSING_VERSION)
            val cutIndex = processed.indexOfFirst {
                it.sourceCaptureId == cutCaptureId && it.sourceSequenceNumber == cutSequenceNumber
            }
            if (cutIndex < MIN_POINTS_PER_HALF || processed.size - cutIndex < MIN_POINTS_PER_HALF) return@withTransaction

            val captureRawPoints = rawTrackPointDao.findAllByCapture(cutCaptureId)
            val cutRawPoint = captureRawPoints.firstOrNull { it.sequenceNumber == cutSequenceNumber } ?: return@withTransaction

            val sourceParts = tripPartDao.findAllByTrip(tripId)
            val plan = TripSplitPlanner.plan(
                parts = sourceParts,
                cut = TripSplitPlanner.Cut(cutCaptureId, cutSequenceNumber, cutRawPoint.elapsedRealtimeNanos),
                captureSequences = captureRawPoints.map { it.sequenceNumber }
            ) ?: return@withTransaction

            val now = clock.wallClockMillis()
            val firstId = idGenerator.newId()
            val secondId = idGenerator.newId()

            // The first half "ended" at the cut, the second where the source
            // did - keeps History's newest-first ordering (and Merge's
            // adjacency lookup) consistent with when each half's ride ended.
            tripDao.insert(
                TripEntity(
                    id = firstId, status = TripStatus.COMPLETED,
                    name = source.name, isFavorite = source.isFavorite,
                    motorcycleId = source.motorcycleId, routeId = source.routeId, notes = source.notes,
                    createdAt = cutRawPoint.capturedAt, updatedAt = now, deletedAt = null
                )
            )
            // A custom name/notes are free text this task can't sensibly
            // divide, so they stay with the first half only (the second
            // falls back to its generated name); favorite/motorcycle/route
            // are facts about the whole ride and stay on both.
            tripDao.insert(
                TripEntity(
                    id = secondId, status = TripStatus.COMPLETED,
                    name = null, isFavorite = source.isFavorite,
                    motorcycleId = source.motorcycleId, routeId = source.routeId, notes = null,
                    createdAt = source.createdAt, updatedAt = now, deletedAt = null
                )
            )

            tripPartDao.insertAll(plan.first.toNewParts(firstId))
            tripPartDao.insertAll(plan.second.toNewParts(secondId))

            tripDao.markSuperseded(source.id, now)

            val operationId = idGenerator.newId()
            tripEditOperationDao.insert(
                TripEditOperationEntity(id = operationId, type = EditOperationType.SPLIT, createdAt = now, undoneAt = null, notes = null)
            )
            tripLineageLinkDao.insertAll(
                listOf(
                    TripLineageLinkEntity(operationId, source.id, LineageRole.INPUT),
                    TripLineageLinkEntity(operationId, firstId, LineageRole.OUTPUT),
                    TripLineageLinkEntity(operationId, secondId, LineageRole.OUTPUT)
                )
            )

            firstTripId = firstId
            secondTripId = secondId
            firstRepresentativeCapture = plan.first.first().captureId
            secondRepresentativeCapture = plan.second.first().captureId
        }

        val first = firstTripId ?: return Result.PreconditionFailed
        val second = secondTripId ?: return Result.PreconditionFailed
        processingScheduler.enqueueTripProcessing(first, firstRepresentativeCapture!!)
        processingScheduler.enqueueTripProcessing(second, secondRepresentativeCapture!!)
        return Result.Success(first, second)
    }

    private fun List<TripPartEntity>.toNewParts(newTripId: String): List<TripPartEntity> = mapIndexed { index, part ->
        part.copy(id = idGenerator.newId(), tripId = newTripId, orderIndex = index)
    }

    companion object {
        /** Each half needs a drawable route (at least two processed points) - see the class comment. */
        const val MIN_POINTS_PER_HALF = 2
    }
}
