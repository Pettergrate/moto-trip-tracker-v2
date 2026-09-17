package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mototriptracker.app.core.database.entity.TripStatisticsEntity
import com.mototriptracker.app.core.model.ProcessingVersion

@Dao
interface TripStatisticsDao {

    /** One row per (tripId, processingVersion) — re-running processing for the same version replaces it, not duplicates it. */
    @Upsert
    suspend fun upsert(statistics: TripStatisticsEntity)

    @Query("SELECT * FROM trip_statistics WHERE tripId = :tripId AND processingVersion = :processingVersion")
    suspend fun findByTripAndVersion(tripId: String, processingVersion: ProcessingVersion): TripStatisticsEntity?
}
