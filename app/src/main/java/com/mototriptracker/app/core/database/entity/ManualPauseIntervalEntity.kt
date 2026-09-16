package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** F0.7 §6.4. An open pause may exist only while its capture is ACTIVE. */
@Entity(
    tableName = "manual_pause_interval",
    foreignKeys = [
        ForeignKey(
            entity = TripCaptureEntity::class,
            parentColumns = ["id"],
            childColumns = ["captureId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["captureId"])]
)
data class ManualPauseIntervalEntity(
    @PrimaryKey val id: String,
    val captureId: String,
    val startedAt: Long,
    val endedAt: Long?,
    val startElapsedRealtimeNanos: Long,
    val endElapsedRealtimeNanos: Long?,
    val startReason: String?,
    val endReason: String?
)
