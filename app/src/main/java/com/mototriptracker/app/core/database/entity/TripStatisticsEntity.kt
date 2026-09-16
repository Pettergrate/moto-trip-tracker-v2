package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.mototriptracker.app.core.model.ProcessingVersion

/**
 * F0.7 §9.3. Derived, regenerable Trip metrics for a given processingVersion.
 * Unknown metrics stay null, never 0 (F0.7 §2.5) — max speed in particular
 * must come from processed evidence, never a raw max() (ADR-006).
 *
 * [movingDurationMs]/[stoppedDurationMs] are nullable even though F0.7 §9.3's
 * field list doesn't mark them with `?` (unlike e.g. [maxSpeedMps]): a Trip
 * can have a known [totalDurationMs] (wall clock between capture start/end)
 * with no reliable location data at all, making the moving/stopped split
 * genuinely unknown rather than zero. This applies F0.7's own general rule
 * for this entity ("una métrica desconocida queda nula, no cero") to two
 * fields the concise field list happened not to mark — a defensible reading,
 * not a literal contradiction of the spec.
 */
@Entity(
    tableName = "trip_statistics",
    primaryKeys = ["tripId", "processingVersion"],
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["tripId"])]
)
data class TripStatisticsEntity(
    val tripId: String,
    val processingVersion: ProcessingVersion,
    val computedAt: Long,
    val distanceM: Double,
    val totalDurationMs: Long,
    val movingDurationMs: Long?,
    val stoppedDurationMs: Long?,
    val manualPauseDurationMs: Long,
    val maxSpeedMps: Double?,
    val averageSpeedMps: Double?,
    val averageMovingSpeedMps: Double?,
    val minElevationM: Double?,
    val maxElevationM: Double?,
    val ascentM: Double?,
    val descentM: Double?,
    val validPointCount: Int,
    val suspectPointCount: Int,
    val rejectedPointCount: Int,
    val gapCount: Int
)
