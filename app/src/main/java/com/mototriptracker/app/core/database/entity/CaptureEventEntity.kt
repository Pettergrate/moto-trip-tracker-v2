package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * F0.7 §6.3. Chronological technical/lifecycle events for a capture.
 * eventType is an open, extensible vocabulary (F0.13 §5), so it stays a
 * String rather than a closed enum.
 */
@Entity(
    tableName = "capture_event",
    foreignKeys = [
        ForeignKey(
            entity = TripCaptureEntity::class,
            parentColumns = ["id"],
            childColumns = ["captureId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["captureId", "eventIndex"], unique = true)]
)
data class CaptureEventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val captureId: String,
    val eventIndex: Long,
    val timestamp: Long,
    val elapsedRealtimeNanos: Long,
    val eventType: String,
    val stateFrom: String?,
    val stateTo: String?,
    val reasonCode: String?,
    val source: String,
    val metadata: String?
)
