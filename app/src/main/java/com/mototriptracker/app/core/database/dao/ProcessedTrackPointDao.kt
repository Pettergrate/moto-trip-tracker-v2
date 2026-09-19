package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mototriptracker.app.core.database.entity.ProcessedTrackPointEntity
import com.mototriptracker.app.core.model.ProcessingVersion
import kotlinx.coroutines.flow.Flow

@Dao
interface ProcessedTrackPointDao {

    @Insert
    suspend fun insertAll(points: List<ProcessedTrackPointEntity>)

    /** Republishing the same (tripId, processingVersion) must not duplicate rows — F0.10 §16.3. */
    @Query("DELETE FROM processed_track_point WHERE tripId = :tripId AND processingVersion = :processingVersion")
    suspend fun deleteByTripAndVersion(tripId: String, processingVersion: ProcessingVersion)

    @Query("SELECT * FROM processed_track_point WHERE tripId = :tripId AND processingVersion = :processingVersion ORDER BY orderIndex ASC")
    suspend fun findAllByTripAndVersion(tripId: String, processingVersion: ProcessingVersion): List<ProcessedTrackPointEntity>

    /** MAP-001: Trip Detail's own reactive twin of [findAllByTripAndVersion]. */
    @Query("SELECT * FROM processed_track_point WHERE tripId = :tripId AND processingVersion = :processingVersion ORDER BY orderIndex ASC")
    fun observeAllByTripAndVersion(tripId: String, processingVersion: ProcessingVersion): Flow<List<ProcessedTrackPointEntity>>
}
