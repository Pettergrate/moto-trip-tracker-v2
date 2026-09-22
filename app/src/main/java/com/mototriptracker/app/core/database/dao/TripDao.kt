package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mototriptracker.app.core.database.entity.TripEntity
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
}
