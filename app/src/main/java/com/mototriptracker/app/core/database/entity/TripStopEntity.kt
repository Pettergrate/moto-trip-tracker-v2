package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mototriptracker.app.core.model.StopOrigin

/**
 * F0.7 §9.5. A meaningful stop, distinct from a traffic hold and from a
 * ManualPauseInterval. A user-locked stop must not be silently overwritten
 * by reprocessing (F0.7 §9.5 rule) — that rule is enforced by whichever
 * repository writes this table, not by a DB constraint.
 */
@Entity(
    tableName = "trip_stop",
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
data class TripStopEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val origin: StopOrigin,
    val startedAt: Long,
    val endedAt: Long,
    val centroidLat: Double?,
    val centroidLon: Double?,
    val durationMs: Long,
    val label: String?,
    val lockedByUser: Boolean,
    val processingVersion: Int?
)
