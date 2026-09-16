package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mototriptracker.app.core.model.TripStatus

/**
 * F0.7 §7.1. The logical trip the user sees in History (ADR-005). Deleting
 * the Motorcycle/Route a Trip points to must not delete the Trip's history
 * (F0.7 §11/§15), hence SET_NULL rather than CASCADE on those two.
 */
@Entity(
    tableName = "trip",
    foreignKeys = [
        ForeignKey(
            entity = MotorcycleEntity::class,
            parentColumns = ["id"],
            childColumns = ["motorcycleId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = RouteEntity::class,
            parentColumns = ["id"],
            childColumns = ["routeId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["status"]),
        Index(value = ["createdAt"]),
        Index(value = ["motorcycleId"]),
        Index(value = ["routeId"])
    ]
)
data class TripEntity(
    @PrimaryKey val id: String,
    val status: TripStatus,
    val name: String?,
    val isFavorite: Boolean,
    val motorcycleId: String?,
    val routeId: String?,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?
)
