package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.core.model.TrackPointDecision

/**
 * F0.7 §9.2. Explains why a raw point was accepted/suspect/rejected for a
 * given processingVersion. Derived and regenerable; never mutates
 * RawTrackPointEntity itself.
 */
@Entity(
    tableName = "point_assessment",
    primaryKeys = ["captureId", "sequenceNumber", "processingVersion"],
    foreignKeys = [
        ForeignKey(
            entity = TripCaptureEntity::class,
            parentColumns = ["id"],
            childColumns = ["captureId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["captureId", "processingVersion"])]
)
data class PointAssessmentEntity(
    val captureId: String,
    val sequenceNumber: Long,
    val processingVersion: ProcessingVersion,
    val decision: TrackPointDecision,
    val reasonCodes: String
)
