package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mototriptracker.app.core.database.entity.PointAssessmentEntity
import com.mototriptracker.app.core.model.ProcessingVersion

@Dao
interface PointAssessmentDao {

    @Insert
    suspend fun insertAll(assessments: List<PointAssessmentEntity>)

    /** Republishing the same (captureId, processingVersion) must not duplicate rows — F0.10 §16.3. */
    @Query("DELETE FROM point_assessment WHERE captureId = :captureId AND processingVersion = :processingVersion")
    suspend fun deleteByCaptureAndVersion(captureId: String, processingVersion: ProcessingVersion)

    @Query("SELECT * FROM point_assessment WHERE captureId = :captureId AND processingVersion = :processingVersion ORDER BY sequenceNumber ASC")
    suspend fun findAllByCaptureAndVersion(captureId: String, processingVersion: ProcessingVersion): List<PointAssessmentEntity>
}
