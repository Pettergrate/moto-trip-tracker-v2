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

    /**
     * HIS-002: history-row route thumbnails. One query across every visible
     * Trip, never one query per Trip and never the full point list for any
     * of them (the owner-approved scope note's own two constraints) -
     * `orderIndex % :sampleStep = 0` bounds each Trip to roughly
     * `totalPoints / sampleStep` rows (always including index 0) regardless
     * of how many thousands of points a long ride has.
     *
     * Deliberately not a SQL window-function-based *even* per-Trip sample
     * (`ROW_NUMBER() OVER (PARTITION BY ...)`, which would need a real
     * per-Trip step computed from each Trip's own point count): window
     * functions need SQLite 3.25+, but this project's `minSdk = 26` ships
     * Android versions whose bundled SQLite predates that (no custom
     * bundled-SQLite driver is used here - see `DatabaseModule`/`FND-003`'s
     * own note on why). A flat step is a real accuracy trade-off for very
     * short Trips (few sampled points) - acceptable for a small illustrative
     * thumbnail, not the detail map `TripRouteMap` already renders in full.
     */
    @Query("SELECT * FROM processed_track_point WHERE tripId IN (:tripIds) AND processingVersion = :processingVersion AND orderIndex % :sampleStep = 0 ORDER BY tripId ASC, orderIndex ASC")
    fun observeSampledByTrips(tripIds: List<String>, processingVersion: ProcessingVersion, sampleStep: Int = 10): Flow<List<ProcessedTrackPointEntity>>
}
