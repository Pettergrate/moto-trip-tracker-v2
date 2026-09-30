package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mototriptracker.app.core.model.VehicleType

/**
 * F0.7 §11/MOTO-001/`ADR-024`. Multiple motorcycles was Post-Core when this entity was first added; `MOTO-001`
 * builds it out for real. `vehicleType` (schema v4, `MIGRATION_3_4`) is what `MAP-006` draws as the map's
 * current-position marker when a Trip is assigned to this motorcycle.
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
    val updatedAt: Long,
    val vehicleType: VehicleType = VehicleType.MOTORCYCLE
)
