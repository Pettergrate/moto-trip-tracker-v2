package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * F0.7 §11. Multiple motorcycles is Post-Core, but Trip.motorcycleId is
 * already optional so this doesn't block that later association.
 */
@Entity(tableName = "motorcycle")
data class MotorcycleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val make: String?,
    val model: String?,
    val year: Int?,
    val isArchived: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)
