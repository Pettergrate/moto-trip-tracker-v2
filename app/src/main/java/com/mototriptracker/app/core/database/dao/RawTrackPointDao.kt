package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity

@Dao
interface RawTrackPointDao {

    /**
     * ADR-006: Raw Track is append-only evidence — there is no update/delete
     * method on this DAO for a reason.
     */
    @Insert
    suspend fun insert(point: RawTrackPointEntity)

    @Query("SELECT MAX(sequenceNumber) FROM raw_track_point WHERE captureId = :captureId")
    suspend fun maxSequenceNumber(captureId: String): Long?

    @Query("SELECT * FROM raw_track_point WHERE captureId = :captureId ORDER BY sequenceNumber ASC")
    suspend fun findAllByCapture(captureId: String): List<RawTrackPointEntity>

    @Query("SELECT COUNT(*) FROM raw_track_point WHERE captureId = :captureId")
    suspend fun countByCapture(captureId: String): Int
}
