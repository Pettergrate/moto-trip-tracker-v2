package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mototriptracker.app.core.database.entity.CaptureEventEntity

@Dao
interface CaptureEventDao {

    @Insert
    suspend fun insert(event: CaptureEventEntity)

    @Query("SELECT MAX(eventIndex) FROM capture_event WHERE captureId = :captureId")
    suspend fun maxEventIndex(captureId: String): Long?

    @Query("SELECT * FROM capture_event WHERE captureId = :captureId ORDER BY eventIndex ASC")
    suspend fun findAllByCapture(captureId: String): List<CaptureEventEntity>
}
