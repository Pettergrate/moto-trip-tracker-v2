package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** F0.7 §10.2. Free-form trip classification, independent of Route. */
@Entity(
    tableName = "tag",
    indices = [Index(value = ["name"], unique = true)]
)
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String
)
