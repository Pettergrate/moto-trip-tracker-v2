package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mototriptracker.app.core.model.EditOperationType

/** F0.7 §8.1. Records a merge/split/boundary-edit/restore operation. */
@Entity(tableName = "trip_edit_operation")
data class TripEditOperationEntity(
    @PrimaryKey val id: String,
    val type: EditOperationType,
    val createdAt: Long,
    val undoneAt: Long?,
    val notes: String?
)
