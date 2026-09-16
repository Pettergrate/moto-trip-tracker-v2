package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mototriptracker.app.core.model.ProcessingVersion

/**
 * F0.7 §9.4 / ADR-016. An explicit, derived discontinuity — never
 * interpolated as observed distance/route.
 */
@Entity(
    tableName = "location_gap",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["tripId", "processingVersion"])]
)
data class LocationGapEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val processingVersion: ProcessingVersion,
    val startedAt: Long,
    val endedAt: Long,
    val startSourceRef: String?,
    val endSourceRef: String?,
    val durationMs: Long,
    val reasonCode: String?
)
