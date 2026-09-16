package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mototriptracker.app.core.database.entity.LocationGapEntity
import com.mototriptracker.app.core.model.ProcessingVersion

@Dao
interface LocationGapDao {

    @Insert
    suspend fun insertAll(gaps: List<LocationGapEntity>)

    /** Republishing the same (tripId, processingVersion) must not duplicate rows — F0.10 §16.3. */
    @Query("DELETE FROM location_gap WHERE tripId = :tripId AND processingVersion = :processingVersion")
    suspend fun deleteByTripAndVersion(tripId: String, processingVersion: ProcessingVersion)

    @Query("SELECT * FROM location_gap WHERE tripId = :tripId AND processingVersion = :processingVersion ORDER BY startedAt ASC")
    suspend fun findAllByTripAndVersion(tripId: String, processingVersion: ProcessingVersion): List<LocationGapEntity>
}
