package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import kotlinx.coroutines.flow.Flow

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

    /** REC-002: lets a recovery decision be recorded once per capture (F0.10 §25 step 8: "emitir evento diagnóstico una sola vez"). */
    @Query("SELECT COUNT(*) FROM diagnostic_event WHERE captureId = :captureId AND eventType = :eventType")
    suspend fun countByCaptureAndType(captureId: String, eventType: String): Int

    /**
     * REC-005: emits the reason of the capture's currently *open* location gap, or null when
     * there is none - "open" meaning more [startedType] than [endedType] events. Counting, not
     * "latest event wins": a fix that ends one gap and is itself the last before the next gap
     * shares an elapsed-realtime stamp with it, so no ordering of the two is reliable.
     */
    @Query(
        "SELECT reasonCode FROM diagnostic_event WHERE captureId = :captureId AND eventType = :startedType " +
            "AND (SELECT COUNT(*) FROM diagnostic_event WHERE captureId = :captureId AND eventType = :startedType) > " +
            "(SELECT COUNT(*) FROM diagnostic_event WHERE captureId = :captureId AND eventType = :endedType) " +
            "ORDER BY elapsedRealtimeNanos DESC LIMIT 1"
    )
    fun observeOpenGapReason(captureId: String, startedType: String, endedType: String): Flow<String?>

    @Query("SELECT * FROM diagnostic_event")
    suspend fun findAll(): List<DiagnosticEventEntity>
}
