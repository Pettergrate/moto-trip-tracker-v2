package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for RawTrackPoint, F0.7 §6.2 / F0.5 §5. Append-only source
 * evidence (ADR-006) — never mutated or "corrected" after insert. Uses a
 * surrogate Long PK plus a unique (captureId, sequenceNumber) logical key,
 * per F0.8 §8.1's explicit exception to UUID-per-row for this table.
 * Absent optional fields stay null; never coerced to zero (F0.7 §2.5).
 *
 * [receivedAtElapsedRealtimeNanos] is deliberately nullable even though F0.7
 * §6.2's field list doesn't mark it with `?`: F0.5 §5, which F0.7 §6.2 says
 * this contract follows, explicitly lists the same field (`receivedAtElapsed?`)
 * as optional — "medir latencia de entrega si se necesita". That is a real
 * inconsistency between F0.5 and F0.7 (found reviewing this entity against
 * both), not resolved here; this follows F0.5's more specific rationale
 * rather than silently picking a side without flagging it. See FND-003's
 * report in docs/05-roadmap/phase1-backlog.md.
 */
@Entity(
    tableName = "raw_track_point",
    foreignKeys = [
        ForeignKey(
            entity = TripCaptureEntity::class,
            parentColumns = ["id"],
            childColumns = ["captureId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["captureId", "sequenceNumber"], unique = true),
        Index(value = ["captureId", "elapsedRealtimeNanos"])
    ]
)
data class RawTrackPointEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val captureId: String,
    val sequenceNumber: Long,
    val capturedAt: Long,
    val elapsedRealtimeNanos: Long,
    val receivedAtElapsedRealtimeNanos: Long?,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyM: Float,
    val altitudeEllipsoidM: Double?,
    val altitudeMslM: Double?,
    val verticalAccuracyM: Float?,
    val speedMps: Float?,
    val speedAccuracyMps: Float?,
    val bearingDeg: Float?,
    val bearingAccuracyDeg: Float?,
    val provider: String?,
    val isMock: Boolean?,
    val requestProfileId: String,
    val callbackBatchId: String?,
    val detectorStateSnapshot: String,
    /**
     * REC-005 follow-up (ADR-016): whether only *approximate* location was allowed when this fix was
     * received. `null` = unknown (every point recorded before schema v3 - never guessed as `false`),
     * `false` = precise location was granted, `true` = only approximate. Android hands an
     * approximate-only app fixes rounded to a ~2 km block (`horizontalAccuracyM` 2000), so the fix is
     * real raw data (ADR-006: kept) but useless as a route: processing rejects it and the live
     * distance skips it, instead of counting a jump of a kilometre while the phone sits still.
     */
    val isApproximateLocation: Boolean? = null
)
