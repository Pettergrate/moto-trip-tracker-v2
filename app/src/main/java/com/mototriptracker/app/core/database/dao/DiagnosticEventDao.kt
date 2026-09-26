package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.model.DiagnosticCategory
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

    /**
     * DIA-004: for evidence that has a natural, stable identity (a process exit is identified by its pid and
     * timestamp): writing it twice must not duplicate it, whatever happened to the cursor that normally
     * prevents that. Returns the new row id, or -1 when the event was already there.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(event: DiagnosticEventEntity): Long

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

    /** DIA-002: the timeline the debug screen shows, newest first and bounded (the table itself is bounded by the purge). */
    @Query("SELECT * FROM diagnostic_event ORDER BY occurredAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DiagnosticEventEntity>>

    /** DIA-002/DIA-003: the newest events of one type, e.g. the last `PROCESS_EXIT`. */
    @Query("SELECT * FROM diagnostic_event WHERE eventType = :eventType ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun findLatestByType(eventType: String, limit: Int): List<DiagnosticEventEntity>

    /** DIA-002: the newest events of a category (the detector section is "the last things the detector said"). */
    @Query("SELECT * FROM diagnostic_event WHERE category = :category ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun findLatestByCategory(category: DiagnosticCategory, limit: Int): List<DiagnosticEventEntity>

    /** DIA-002: the last thing recovery *did* (not the process exits, which are their own row). */
    @Query(
        "SELECT * FROM diagnostic_event WHERE category = :category AND eventType != :excludedType " +
            "ORDER BY occurredAt DESC LIMIT 1"
    )
    suspend fun findLatestInCategoryExcluding(category: DiagnosticCategory, excludedType: String): DiagnosticEventEntity?

    /** The one-shot twin of [observeOpenGapReason]: the reason of the capture's open gap/state, or null. */
    @Query(
        "SELECT reasonCode FROM diagnostic_event WHERE captureId = :captureId AND eventType = :startedType " +
            "AND (SELECT COUNT(*) FROM diagnostic_event WHERE captureId = :captureId AND eventType = :startedType) > " +
            "(SELECT COUNT(*) FROM diagnostic_event WHERE captureId = :captureId AND eventType = :endedType) " +
            "ORDER BY elapsedRealtimeNanos DESC LIMIT 1"
    )
    suspend fun findOpenGapReason(captureId: String, startedType: String, endedType: String): String?

    /**
     * DIA-004/F0.13 §12.2: retention. Deletes diagnostic rows only - never a Trip, a capture or raw points
     * (there is no foreign key between them by design). [keepTypes] are exempt: see `DiagnosticPurger`.
     */
    @Query("DELETE FROM diagnostic_event WHERE occurredAt < :cutoff AND eventType NOT IN (:keepTypes)")
    suspend fun deleteOlderThan(cutoff: Long, keepTypes: List<String>): Int

    /** The `n` oldest purgeable rows, for the capacity limit (F0.13 §12.2: "purgar por antigüedad y luego por capacidad"). */
    @Query(
        "DELETE FROM diagnostic_event WHERE eventId IN (SELECT eventId FROM diagnostic_event " +
            "WHERE eventType NOT IN (:keepTypes) ORDER BY occurredAt ASC LIMIT :n)"
    )
    suspend fun deleteOldest(n: Int, keepTypes: List<String>): Int

    @Query("SELECT * FROM diagnostic_event")
    suspend fun findAll(): List<DiagnosticEventEntity>
}
