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

    /**
     * Republishing the same (captureId, processingVersion) must not
     * duplicate rows — F0.10 §16.3. Scoped to the slice of the capture a
     * given TripPart covers (EDT-002): after a split, two Trips share one
     * capture, and reprocessing one of them must not wipe the assessments
     * the other just published for its own half.
     */
    @Query(
        """
        DELETE FROM point_assessment
        WHERE captureId = :captureId AND processingVersion = :processingVersion
          AND sequenceNumber BETWEEN :fromSequence AND :toSequence
        """
    )
    suspend fun deleteByCaptureVersionAndRange(captureId: String, processingVersion: ProcessingVersion, fromSequence: Long, toSequence: Long)

    @Query("SELECT * FROM point_assessment WHERE captureId = :captureId AND processingVersion = :processingVersion ORDER BY sequenceNumber ASC")
    suspend fun findAllByCaptureAndVersion(captureId: String, processingVersion: ProcessingVersion): List<PointAssessmentEntity>
}
