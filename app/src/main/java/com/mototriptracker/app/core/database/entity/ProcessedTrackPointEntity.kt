package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * F0.7 §9.1. Regenerable geometry cache for maps/metrics (ADR-006) — never a
 * source of truth. Keyed by (tripId, processingVersion, orderIndex) so a
 * reprocess can coexist with/replace a prior version before the old one is
 * pruned.
 */
@Entity(
    tableName = "processed_track_point",
    primaryKeys = ["tripId", "processingVersion", "orderIndex"],
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
data class ProcessedTrackPointEntity(
    val tripId: String,
    val processingVersion: Int,
    val orderIndex: Int,
    val latitude: Double,
    val longitude: Double,
    val sourceCaptureId: String,
    val sourceSequenceNumber: Long?,
    val pointRole: String?
)
