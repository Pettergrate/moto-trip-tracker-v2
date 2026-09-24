package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mototriptracker.app.core.database.entity.TripLineageLinkEntity
import com.mototriptracker.app.core.model.LineageRole

/** F0.7 §8.2/EDT-001: relates a TripEditOperation to the Trips it consumed (INPUT) or produced (OUTPUT). */
@Dao
interface TripLineageLinkDao {

    @Insert
    suspend fun insertAll(links: List<TripLineageLinkEntity>)

    @Query("SELECT * FROM trip_lineage_link WHERE operationId = :operationId AND role = :role")
    suspend fun findByOperationAndRole(operationId: String, role: LineageRole): List<TripLineageLinkEntity>

    /** EDT-001: looks up a Trip's own lineage links (e.g. finding the operation that produced it as an OUTPUT) without needing the operationId ahead of time. */
    @Query("SELECT * FROM trip_lineage_link WHERE tripId = :tripId")
    suspend fun findByTrip(tripId: String): List<TripLineageLinkEntity>
}
