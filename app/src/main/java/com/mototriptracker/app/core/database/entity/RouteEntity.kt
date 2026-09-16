package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** F0.7 §10.1. A repeatable logical route, distinct from any one Trip execution. */
@Entity(tableName = "route")
data class RouteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val isFavorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)
