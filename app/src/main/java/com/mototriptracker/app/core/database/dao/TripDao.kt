package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.core.model.TripStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {

    @Insert
    suspend fun insert(trip: TripEntity)

    @Query("SELECT * FROM trip WHERE id = :id")
    suspend fun findById(id: String): TripEntity?

    /**
     * UI-001/F0.9 §5.1's Home "Recientes" preview - HIS-001 owns the real,
     * full History list. `createdAt` stands in for "when the ride ended"
     * (TRK-004 only ever creates a Trip row at Finish time, never ahead of
     * it), so ordering by it is equivalent to ordering by finish time
     * without needing a join to TripPart/TripCapture for this simple case.
     */
    @Query("SELECT * FROM trip WHERE status = :status AND deletedAt IS NULL ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int, status: TripStatus = TripStatus.COMPLETED): Flow<List<TripEntity>>

    /** HIS-001/FR-HIS-003: the full chronological history, not Home's 3-row preview - default newest-first per F0.9 §8.1. */
    @Query("SELECT * FROM trip WHERE status = :status AND deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeAllDescending(status: TripStatus = TripStatus.COMPLETED): Flow<List<TripEntity>>

    /** HIS-001/FR-HIS-008: the one sort order beyond default chronology this task implements - oldest-first. */
    @Query("SELECT * FROM trip WHERE status = :status AND deletedAt IS NULL ORDER BY createdAt ASC")
    fun observeAllAscending(status: TripStatus = TripStatus.COMPLETED): Flow<List<TripEntity>>

    /** FAV-001/FR-FAV-002: the dedicated favorites list, same ordering/filters as [observeAllDescending]. */
    @Query("SELECT * FROM trip WHERE status = :status AND deletedAt IS NULL AND isFavorite = 1 ORDER BY createdAt DESC")
    fun observeFavoritesDescending(status: TripStatus = TripStatus.COMPLETED): Flow<List<TripEntity>>

    /** HIS-001/F0.9 §9: Trip Detail observes this directly so a rename made elsewhere is reflected without a manual refresh. */
    @Query("SELECT * FROM trip WHERE id = :id")
    fun observeById(id: String): Flow<TripEntity?>

    /**
     * HIS-001/FR-HIS-004. A `null`/blank [name] reverts to the generated
     * fallback (F0.9 §8.2) rather than persisting an empty string - `name`
     * is nullable precisely so "no custom name" has one honest
     * representation, not two (`null` and `""`).
     */
    @Query("UPDATE trip SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, name: String?, updatedAt: Long)

    /** FAV-001/FR-FAV-001: mark/unmark a Trip as favorite - does not affect metrics/processing (domain-data-model.md). */
    @Query("UPDATE trip SET isFavorite = :isFavorite, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean, updatedAt: Long)

    /** TRS-001/FR-HIS-005: soft-delete - a TRASHED Trip keeps every row (own, TripPart, statistics) untouched, just hidden from normal browsing. */
    @Query("UPDATE trip SET status = :newStatus, deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun trash(id: String, deletedAt: Long, updatedAt: Long, newStatus: TripStatus = TripStatus.TRASHED)

    /**
     * TRS-001/FR-HIS-005. Always restores to `COMPLETED` - the only status a
     * Trip can currently be trashed *from* (`EDT`-family `SUPERSEDED` trips
     * don't exist yet; `EDT-001..004` will need to revisit this if a
     * superseded Trip ever becomes trashable too).
     */
    @Query("UPDATE trip SET status = :restoredStatus, deletedAt = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun restore(id: String, updatedAt: Long, restoredStatus: TripStatus = TripStatus.COMPLETED)

    /** TRS-001/F0.9 §14: the dedicated Trash list, most-recently-trashed first. */
    @Query("SELECT * FROM trip WHERE status = :status ORDER BY deletedAt DESC")
    fun observeTrashedDescending(status: TripStatus = TripStatus.TRASHED): Flow<List<TripEntity>>

    /** TRS-001/PRIV-013: trashed past the retention window, eligible for [TrashPurgeWorker] to physically delete. */
    @Query("SELECT * FROM trip WHERE status = :status AND deletedAt <= :cutoff")
    suspend fun findEligibleForPurge(cutoff: Long, status: TripStatus = TripStatus.TRASHED): List<TripEntity>

    /** TRS-001: physical delete - cascades to this Trip's own TripPart/TripStatistics/ProcessedTrackPoint/etc. rows (see their FKs), never to RawTrackPoint (ADR-006, keyed off captureId only). */
    @Query("DELETE FROM trip WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * EDT-004: COMPLETED Trips with no `trip_statistics` row for [version] - the
     * "derived data is missing/invalidated, recompute it" set (a crash between a
     * structural edit's commit and its processing being enqueued, a failed
     * worker, or a future `processingVersion` bump). SUPERSEDED/TRASHED Trips are
     * excluded on purpose: they aren't shown, so nothing needs their numbers.
     */
    @Query(
        """
        SELECT * FROM trip t
        WHERE t.status = :status AND t.deletedAt IS NULL
          AND NOT EXISTS (SELECT 1 FROM trip_statistics s WHERE s.tripId = t.id AND s.processingVersion = :version)
        """
    )
    suspend fun findCompletedWithoutStatistics(version: ProcessingVersion, status: TripStatus = TripStatus.COMPLETED): List<TripEntity>

    /** EDT-001: the chronologically-previous COMPLETED, non-trashed Trip - Trip Detail's "Merge with previous" candidate. Excludes SUPERSEDED Trips by construction (only ever COMPLETED is queried). */
    @Query("SELECT * FROM trip WHERE status = :status AND deletedAt IS NULL AND createdAt < :createdAt ORDER BY createdAt DESC LIMIT 1")
    suspend fun findPreviousCompleted(createdAt: Long, status: TripStatus = TripStatus.COMPLETED): TripEntity?

    /** EDT-001: the chronologically-next COMPLETED, non-trashed Trip - Trip Detail's "Merge with next" candidate. */
    @Query("SELECT * FROM trip WHERE status = :status AND deletedAt IS NULL AND createdAt > :createdAt ORDER BY createdAt ASC LIMIT 1")
    suspend fun findNextCompleted(createdAt: Long, status: TripStatus = TripStatus.COMPLETED): TripEntity?

    /**
     * EDT-001/domain-data-model.md §8.3: marks a merge/split input Trip
     * SUPERSEDED rather than deleting it - its own rows (including its
     * TripParts) stay intact for a future undo, and it simply disappears
     * from every existing list query, all of which already filter to
     * `status = COMPLETED` (no new WHERE clause needed anywhere else).
     */
    @Query("UPDATE trip SET status = :newStatus, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markSuperseded(id: String, updatedAt: Long, newStatus: TripStatus = TripStatus.SUPERSEDED)
}
