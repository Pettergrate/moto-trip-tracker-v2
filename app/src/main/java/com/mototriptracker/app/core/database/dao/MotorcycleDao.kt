package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mototriptracker.app.core.database.entity.MotorcycleEntity
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.core.model.VehicleType
import kotlinx.coroutines.flow.Flow

/** MOTO-001/`FR-MOTO-001/002/003`/`ADR-024`. Never a hard delete (`setArchived`) - matches the entity's own field and F0.7 §11's "archivar no debe volver ilegible el historial". */
@Dao
interface MotorcycleDao {

    @Insert
    suspend fun insert(motorcycle: MotorcycleEntity)

    @Query("SELECT * FROM motorcycle WHERE id = :id")
    suspend fun findById(id: String): MotorcycleEntity?

    @Query("SELECT * FROM motorcycle WHERE id = :id")
    fun observeById(id: String): Flow<MotorcycleEntity?>

    /** Newest-added-last is deliberately not the order - alphabetical is what a picker/list needs. */
    @Query("SELECT * FROM motorcycle WHERE isArchived = 0 ORDER BY name ASC")
    fun observeActive(): Flow<List<MotorcycleEntity>>

    /** The full Motorcycles screen: active first, archived after, both alphabetical within their group. */
    @Query("SELECT * FROM motorcycle ORDER BY isArchived ASC, name ASC")
    fun observeAll(): Flow<List<MotorcycleEntity>>

    @Query("UPDATE motorcycle SET name = :name, make = :make, model = :model, year = :year, vehicleType = :vehicleType, updatedAt = :updatedAt WHERE id = :id")
    suspend fun update(id: String, name: String, make: String?, model: String?, year: Int?, vehicleType: VehicleType, updatedAt: Long)

    @Query("UPDATE motorcycle SET isArchived = :isArchived, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: String, isArchived: Boolean, updatedAt: Long)

    /**
     * `FR-MOTO-003`: live total tracked distance for this motorcycle - every COMPLETED, non-trashed Trip currently
     * carrying up-to-date statistics. `COALESCE`s to 0.0 (never null) for a motorcycle with no Trips yet, matching
     * every other zero-trip aggregate elsewhere in this codebase.
     */
    @Query(
        """
        SELECT COALESCE(SUM(s.distanceM), 0.0) FROM trip t
        JOIN trip_statistics s ON s.tripId = t.id AND s.processingVersion = :version
        WHERE t.motorcycleId = :motorcycleId AND t.status = :status AND t.deletedAt IS NULL
        """
    )
    fun observeTotalDistanceMeters(motorcycleId: String, version: ProcessingVersion, status: TripStatus = TripStatus.COMPLETED): Flow<Double>
}
