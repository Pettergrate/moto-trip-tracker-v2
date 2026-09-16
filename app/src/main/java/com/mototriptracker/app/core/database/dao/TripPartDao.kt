package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mototriptracker.app.core.database.entity.TripPartEntity

@Dao
interface TripPartDao {

    @Insert
    suspend fun insert(part: TripPartEntity)

    /**
     * ADR-005: a TripPart is what links a captureId to its owning Trip. Used
     * both to build TRK-004's initial part and, on an idempotent Finish
     * retry, to find the Trip a since-completed capture already belongs to
     * without creating another one (REL-INV-007).
     */
    @Query("SELECT * FROM trip_part WHERE captureId = :captureId LIMIT 1")
    suspend fun findByCaptureId(captureId: String): TripPartEntity?
}
