package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.EndSource
import com.mototriptracker.app.core.model.StartSource

/**
 * Room entity for TripCapture, docs/03-architecture/domain-data-model.md (F0.7) §6.1.
 * Source of truth for a physical tracking session (ADR-005). At most one row
 * with [status] == ACTIVE may exist; enforced at the transaction boundary by
 * TripCaptureDao.startCaptureIfNoneActive (ADR-020), not by a DB constraint —
 * see that DAO for why.
 */
@Entity(
    tableName = "trip_capture",
    indices = [
        Index(value = ["status"]),
        Index(value = ["startedAt"])
    ]
)
data class TripCaptureEntity(
    @PrimaryKey val id: String,
    val status: CaptureStatus,
    val startedAt: Long,
    val endedAt: Long?,
    val startElapsedRealtimeNanos: Long,
    val endElapsedRealtimeNanos: Long?,
    val localTimeZoneId: String,
    val startSource: StartSource,
    val endSource: EndSource?,
    val detectorVersion: Int,
    val locationProfileVersion: Int,
    val createdAt: Long,
    val updatedAt: Long
)
