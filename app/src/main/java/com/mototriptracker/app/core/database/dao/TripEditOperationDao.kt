package com.mototriptracker.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import com.mototriptracker.app.core.database.entity.TripEditOperationEntity

/** F0.7 §8.1/EDT-001: records a merge/split/boundary-edit/restore operation. */
@Dao
interface TripEditOperationDao {

    @Insert
    suspend fun insert(operation: TripEditOperationEntity)
}
