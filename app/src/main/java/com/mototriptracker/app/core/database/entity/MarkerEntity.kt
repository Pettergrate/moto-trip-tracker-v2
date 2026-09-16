package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** F0.7 §10.3. User-created context point (viewpoint, fuel stop, etc.). */
@Entity(
    tableName = "marker",
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
data class MarkerEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val type: String,
    val label: String?,
    val note: String?,
    val latitude: Double?,
    val longitude: Double?,
    val timestamp: Long?,
    val sourceCaptureId: String?,
    val sourceSequenceNumber: Long?,
    val createdAt: Long
)
