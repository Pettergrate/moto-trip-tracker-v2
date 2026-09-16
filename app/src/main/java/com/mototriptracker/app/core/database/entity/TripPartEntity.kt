package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * F0.7 §7.2/§8. Links a logical Trip to a range of a physical TripCapture —
 * this is what makes merge/split non-destructive (ADR-005). RESTRICT on the
 * capture FK enforces F0.7 §15's rule that a still-referenced capture cannot
 * be purged; CASCADE on the trip FK because a TripPart has no meaning once
 * its owning Trip row is actually gone.
 */
@Entity(
    tableName = "trip_part",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TripCaptureEntity::class,
            parentColumns = ["id"],
            childColumns = ["captureId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["tripId", "orderIndex"], unique = true),
        Index(value = ["captureId"])
    ]
)
data class TripPartEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val captureId: String,
    val orderIndex: Int,
    val startElapsedRealtimeNanos: Long,
    val endElapsedRealtimeNanos: Long?,
    val startSequenceNumber: Long?,
    val endSequenceNumber: Long?
)
