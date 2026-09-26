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

    /**
     * REC-005 follow-up: the last point that is real evidence of a position - not one taken with only approximate
     * location allowed. A restarted recording seeds its signal watch from this, so an approximate fix cannot pass
     * for a live signal. Points from before schema v3 have an unknown marker (`NULL`) and count as usable, as they always did.
     */
    @Query(
        "SELECT * FROM raw_track_point WHERE captureId = :captureId AND (isApproximateLocation IS NULL OR isApproximateLocation = 0) " +
            "ORDER BY sequenceNumber DESC LIMIT 1"
    )
    suspend fun findLastUsableByCapture(captureId: String): RawTrackPointEntity?

    /** REC-004: the last recorded point without loading the capture's whole history - sealing now also runs at every process start, so it must stay cheap for a long trip. */
    @Query("SELECT * FROM raw_track_point WHERE captureId = :captureId ORDER BY sequenceNumber DESC LIMIT 1")
    suspend fun findLastByCapture(captureId: String): RawTrackPointEntity?

    @Query("SELECT COUNT(*) FROM raw_track_point WHERE captureId = :captureId")
    suspend fun countByCapture(captureId: String): Int
}
