package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity

/**
 * Deliberately minimal: insert plus the reads needed to prove persistence
 * works. Query needs for a Debug Screen (`DIA-002`) or export ZIP
 * (`DIA-003`) are added by those tasks, not pre-built here without a caller.
 */
@Dao
interface DiagnosticEventDao {

    @Insert
    suspend fun insert(event: DiagnosticEventEntity)

    @Query("SELECT * FROM diagnostic_event WHERE eventId = :eventId")
    suspend fun findById(eventId: String): DiagnosticEventEntity?

    @Query("SELECT COUNT(*) FROM diagnostic_event")
    suspend fun count(): Int

    @Query("SELECT * FROM diagnostic_event")
    suspend fun findAll(): List<DiagnosticEventEntity>
}
