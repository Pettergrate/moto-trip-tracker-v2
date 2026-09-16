package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mototriptracker.app.core.model.LineageRole

/**
 * F0.7 §8.2. Relates a TripEditOperation to the Trips it consumed (INPUT) or
 * produced (OUTPUT) — supports N inputs → 1 output (merge) and 1 input → N
 * outputs (split) alike.
 */
@Entity(
    tableName = "trip_lineage_link",
    primaryKeys = ["operationId", "tripId", "role"],
    foreignKeys = [
        ForeignKey(
            entity = TripEditOperationEntity::class,
            parentColumns = ["id"],
            childColumns = ["operationId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["tripId"])]
)
data class TripLineageLinkEntity(
    val operationId: String,
    val tripId: String,
    val role: LineageRole
)
