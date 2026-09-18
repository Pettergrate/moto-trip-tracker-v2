package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mototriptracker.app.core.database.entity.ManualPauseIntervalEntity

@Dao
interface ManualPauseIntervalDao {

    @Insert
    suspend fun insert(pause: ManualPauseIntervalEntity)

    /** F0.7 §6.4: "an open pause may exist only while its capture is ACTIVE" - `endedAt IS NULL` is that open pause, if any. */
    @Query("SELECT * FROM manual_pause_interval WHERE captureId = :captureId AND endedAt IS NULL LIMIT 1")
    suspend fun findOpenByCapture(captureId: String): ManualPauseIntervalEntity?

    @Query(
        "UPDATE manual_pause_interval SET endedAt = :endedAt, endElapsedRealtimeNanos = :endElapsedRealtimeNanos, endReason = :endReason WHERE id = :id"
    )
    suspend fun closePause(id: String, endedAt: Long, endElapsedRealtimeNanos: Long, endReason: String?)

    @Query("SELECT * FROM manual_pause_interval WHERE captureId = :captureId ORDER BY startedAt ASC")
    suspend fun findAllByCapture(captureId: String): List<ManualPauseIntervalEntity>
}
