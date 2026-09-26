package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mototriptracker.app.core.database.entity.TripPartEntity

@Dao
interface TripPartDao {

    @Insert
    suspend fun insert(part: TripPartEntity)

    /** EDT-001: bulk-inserts a merged Trip's copied TripParts in one call rather than one `insert` per row. */
    @Insert
    suspend fun insertAll(parts: List<TripPartEntity>)

    /**
     * ADR-005: a TripPart is what links a captureId to its owning Trip. Used
     * both to build TRK-004's initial part and, on an idempotent Finish
     * retry, to find the Trip a since-completed capture already belongs to
     * without creating another one (REL-INV-007).
     */
    @Query("SELECT * FROM trip_part WHERE captureId = :captureId LIMIT 1")
    suspend fun findByCaptureId(captureId: String): TripPartEntity?

    /** REC-004: how many Trips reference a capture - a sealed capture must have exactly one partial Trip, however many reconciliations overlapped. */
    @Query("SELECT COUNT(*) FROM trip_part WHERE captureId = :captureId")
    suspend fun countByCaptureId(captureId: String): Int

    @Query("SELECT * FROM trip_part WHERE tripId = :tripId ORDER BY orderIndex ASC")
    suspend fun findAllByTrip(tripId: String): List<TripPartEntity>
}
