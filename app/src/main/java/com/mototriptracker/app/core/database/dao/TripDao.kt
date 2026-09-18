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
}
