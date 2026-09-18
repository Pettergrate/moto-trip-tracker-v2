package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import kotlinx.coroutines.flow.Flow

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

    /**
     * UI-001: a live, best-effort distance/route preview for an ACTIVE
     * capture — re-emits on every insert. Deliberately not the authoritative
     * number: that's PRC-001/PRC-002's job once the capture finishes and
     * Processing runs its real gap/assessment pipeline. A realistic ride's
     * point count (low thousands at most) makes resumming the whole list on
     * every emission trivial; revisit only if real evidence says otherwise.
     */
    @Query("SELECT * FROM raw_track_point WHERE captureId = :captureId ORDER BY sequenceNumber ASC")
    fun observeAllByCapture(captureId: String): Flow<List<RawTrackPointEntity>>

    @Query("SELECT COUNT(*) FROM raw_track_point WHERE captureId = :captureId")
    suspend fun countByCapture(captureId: String): Int
}
