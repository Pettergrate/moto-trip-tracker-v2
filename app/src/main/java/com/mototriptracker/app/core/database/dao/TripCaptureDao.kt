package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.EndSource
import kotlinx.coroutines.flow.Flow

@Dao
interface TripCaptureDao {

    @Query("SELECT * FROM trip_capture WHERE status = :status LIMIT 1")
    suspend fun findByStatus(status: CaptureStatus = CaptureStatus.ACTIVE): TripCaptureEntity?

    /** UI-001: the reactive twin of [findByStatus] — Home/Active Trip observe this instead of polling. */
    @Query("SELECT * FROM trip_capture WHERE status = :status LIMIT 1")
    fun observeByStatus(status: CaptureStatus = CaptureStatus.ACTIVE): Flow<TripCaptureEntity?>

    @Query("SELECT * FROM trip_capture WHERE id = :id")
    suspend fun findById(id: String): TripCaptureEntity?

    @Query("SELECT COUNT(*) FROM trip_capture WHERE status = :status")
    suspend fun countByStatus(status: CaptureStatus): Int

    /**
     * DET-006/F0.3 §9: the reference point for post-Finish auto-start
     * suppression - whichever capture (COMPLETED or ABORTED) ended most
     * recently, regardless of how. Ordered by `endElapsedRealtimeNanos`
     * (GPS-004: elapsed-realtime is the authoritative clock for "how much
     * time has passed" math), not `endedAt`.
     */
    @Query("SELECT * FROM trip_capture WHERE status != :activeStatus ORDER BY endElapsedRealtimeNanos DESC LIMIT 1")
    suspend fun findMostRecentlyEnded(activeStatus: CaptureStatus = CaptureStatus.ACTIVE): TripCaptureEntity?

    /**
     * Do not call directly outside [startCaptureIfNoneActive]: inserting
     * here bypasses the ADR-020 single-active-capture guard. Kotlin doesn't
     * allow narrowing this below `public` on a Room `@Dao` interface member
     * (verified: `internal`/`private` on an abstract interface method fails
     * to compile), and ADR-012's single-module bootstrap means there is no
     * real compiler-enforced boundary to hide it behind either way. The
     * actual fix for a future caller that ignores this comment is a raw
     * partial-unique index (`CREATE UNIQUE INDEX ... WHERE status =
     * 'ACTIVE'`) added via `RoomDatabase.Callback.onCreate` — there is no
     * production `MotoTripDatabase` builder yet for that callback to live in
     * (FND-003 doesn't wire one; nothing needs it yet). Whichever task adds
     * that builder (FND-004 or TRK-001) should add that index.
     */
    @Insert
    suspend fun insert(capture: TripCaptureEntity)

    /**
     * Closes an ACTIVE capture. Status and end fields are always written
     * together — F0.7 §6.1/§15: a COMPLETED capture requires `endedAt` to be
     * defined, so there is no separate generic status setter that could
     * leave one without the other. The `WHERE status = 'ACTIVE'` guard makes
     * this idempotent: calling it again on an already-completed capture is a
     * no-op (ADR-015).
     */
    @Query(
        """
        UPDATE trip_capture
        SET status = :newStatus, endedAt = :endedAt, endElapsedRealtimeNanos = :endElapsedRealtimeNanos,
            endSource = :endSource, updatedAt = :updatedAt
        WHERE id = :id AND status = :expectedCurrentStatus
        """
    )
    suspend fun completeActiveCapture(
        id: String,
        endedAt: Long,
        endElapsedRealtimeNanos: Long,
        endSource: EndSource,
        updatedAt: Long,
        newStatus: CaptureStatus = CaptureStatus.COMPLETED,
        expectedCurrentStatus: CaptureStatus = CaptureStatus.ACTIVE
    )

    /**
     * REL-INV-001 / ADR-020: at most one ACTIVE capture at a time. This is
     * enforced here — inside a single Room transaction that re-checks before
     * inserting — rather than as a raw partial-unique-index, per FND-003's
     * acceptance criterion ("enforced at domain/transaction boundary") and
     * F0.10 §18.2 ("revalidar dentro de transacción"). Room serializes the
     * statements inside one `@Transaction`, so two genuinely concurrent
     * callers of this same method cannot both pass the check before either
     * inserts (verified with a real-thread concurrent test, not just
     * sequential calls — see TripCaptureDaoTest).
     *
     * @return true if [capture] was inserted, false if an ACTIVE capture
     * already existed and nothing was written.
     */
    @Transaction
    suspend fun startCaptureIfNoneActive(capture: TripCaptureEntity): Boolean {
        require(capture.status == CaptureStatus.ACTIVE) {
            "startCaptureIfNoneActive requires an ACTIVE capture, got ${capture.status}"
        }
        if (findByStatus(CaptureStatus.ACTIVE) != null) return false
        insert(capture)
        return true
    }
}
