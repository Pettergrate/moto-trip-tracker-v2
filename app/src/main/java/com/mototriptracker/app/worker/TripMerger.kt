package com.mototriptracker.app.worker

import androidx.room.withTransaction
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.dao.TripCaptureDao
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
import com.mototriptracker.app.tracking.processing.ProcessingScheduler
import javax.inject.Inject

/**
 * EDT-001/domain-data-model.md §8.3: merges two already-COMPLETED Trips into
 * a new logical Trip. Never touches a RawTrackPoint (ADR-005) - it only
 * copies the source Trips' own TripParts into new rows on the new Trip, in
 * chronological order, referencing the same capture ranges. Lives here
 * rather than in `domain/` for the same reason [TripPurger] does: it needs
 * `MotoTripDatabase.withTransaction` directly, and ADR-013/`DomainBoundaryTest`
 * keep `domain/` free of any Android/Room dependency.
 *
 * Source Trips become `SUPERSEDED`, never deleted - their own rows
 * (including their original TripParts) stay fully intact, which is
 * deliberately as far as this task goes toward FR-EDT-006's "SHOULD preserve
 * enough provenance... for undo/recovery" (P1, "where practical"): enough
 * data survives for a future task to build real undo UI, but this task does
 * not build that UI itself (see the merge scoping note in the backlog).
 *
 * Chronological ordering (which source is "earlier") comes from each Trip's
 * first TripPart's own [TripCaptureDao]-backed `startedAt` (wall clock) -
 * never `elapsedRealtimeNanos`, which is only comparable within one boot
 * session and the two source Trips are, by definition, two separate capture
 * sessions.
 *
 * Statistics/processed track are never computed here - [TripProcessingWorker]
 * does that asynchronously afterwards, same as a normal Finish (ADR-010);
 * this method's own transaction commits the structural change first, per
 * ADR-015 ("derived processing se programa después del commit estructural").
 */
class TripMerger @Inject constructor(
    private val database: MotoTripDatabase,
    private val tripDao: TripDao,
    private val tripPartDao: TripPartDao,
    private val tripCaptureDao: TripCaptureDao,
    private val tripEditOperationDao: TripEditOperationDao,
    private val tripLineageLinkDao: TripLineageLinkDao,
    private val processingScheduler: ProcessingScheduler,
    private val clock: Clock,
    private val idGenerator: IdGenerator
) {
    sealed interface Result {
        data class Success(val mergedTripId: String) : Result

        /** ADR-015: revalidated inside the transaction - a source Trip that's gone or no longer COMPLETED (e.g. trashed by a racing action) aborts cleanly with no partial state, rather than throwing or corrupting anything. */
        data object PreconditionFailed : Result
    }

    suspend fun merge(firstTripId: String, secondTripId: String): Result {
        require(firstTripId != secondTripId) { "Cannot merge a Trip with itself" }

        var mergedTripId: String? = null
        var representativeCaptureId: String? = null

        database.withTransaction {
            val first = tripDao.findById(firstTripId)
            val second = tripDao.findById(secondTripId)
            if (first == null || second == null || first.status != TripStatus.COMPLETED || second.status != TripStatus.COMPLETED) {
                return@withTransaction
            }

            val firstParts = tripPartDao.findAllByTrip(firstTripId)
            val secondParts = tripPartDao.findAllByTrip(secondTripId)
            if (firstParts.isEmpty() || secondParts.isEmpty()) return@withTransaction

            val firstStartedAt = tripCaptureDao.findById(firstParts.first().captureId)?.startedAt
            val secondStartedAt = tripCaptureDao.findById(secondParts.first().captureId)?.startedAt
            if (firstStartedAt == null || secondStartedAt == null) return@withTransaction

            // EDT-002: two halves of one split share a capture, so
            // `startedAt` ties - fall back to where in that capture each
            // Trip's first part begins (elapsedRealtime is comparable
            // *within* one capture), then to the Trips' own createdAt.
            val firstIsEarlier = when {
                firstStartedAt != secondStartedAt -> firstStartedAt < secondStartedAt
                firstParts.first().startElapsedRealtimeNanos != secondParts.first().startElapsedRealtimeNanos ->
                    firstParts.first().startElapsedRealtimeNanos < secondParts.first().startElapsedRealtimeNanos
                else -> first.createdAt <= second.createdAt
            }
            val order = if (firstIsEarlier) {
                MergeOrder(first, firstParts, second, secondParts)
            } else {
                MergeOrder(second, secondParts, first, firstParts)
            }

            val now = clock.wallClockMillis()
            val newTripId = idGenerator.newId()
            tripDao.insert(
                TripEntity(
                    id = newTripId,
                    status = TripStatus.COMPLETED,
                    name = order.earlier.name ?: order.later.name,
                    isFavorite = order.earlier.isFavorite || order.later.isFavorite,
                    motorcycleId = order.earlier.motorcycleId ?: order.later.motorcycleId,
                    routeId = order.earlier.routeId ?: order.later.routeId,
                    notes = order.earlier.notes ?: order.later.notes,
                    createdAt = order.later.createdAt,
                    updatedAt = now,
                    deletedAt = null
                )
            )

            val newParts = (order.earlierParts + order.laterParts).mapIndexed { index, sourcePart ->
                TripPartEntity(
                    id = idGenerator.newId(),
                    tripId = newTripId,
                    captureId = sourcePart.captureId,
                    orderIndex = index,
                    startElapsedRealtimeNanos = sourcePart.startElapsedRealtimeNanos,
                    endElapsedRealtimeNanos = sourcePart.endElapsedRealtimeNanos,
                    startSequenceNumber = sourcePart.startSequenceNumber,
                    endSequenceNumber = sourcePart.endSequenceNumber
                )
            }
            tripPartDao.insertAll(newParts)

            tripDao.markSuperseded(order.earlier.id, now)
            tripDao.markSuperseded(order.later.id, now)

            val operationId = idGenerator.newId()
            tripEditOperationDao.insert(
                TripEditOperationEntity(id = operationId, type = EditOperationType.MERGE, createdAt = now, undoneAt = null, notes = null)
            )
            tripLineageLinkDao.insertAll(
                listOf(
                    TripLineageLinkEntity(operationId, order.earlier.id, LineageRole.INPUT),
                    TripLineageLinkEntity(operationId, order.later.id, LineageRole.INPUT),
                    TripLineageLinkEntity(operationId, newTripId, LineageRole.OUTPUT)
                )
            )

            mergedTripId = newTripId
            representativeCaptureId = newParts.first().captureId
        }

        val tripId = mergedTripId ?: return Result.PreconditionFailed
        processingScheduler.enqueueTripProcessing(tripId, representativeCaptureId!!)
        return Result.Success(tripId)
    }

    private data class MergeOrder(
        val earlier: TripEntity,
        val earlierParts: List<TripPartEntity>,
        val later: TripEntity,
        val laterParts: List<TripPartEntity>
    )
}
