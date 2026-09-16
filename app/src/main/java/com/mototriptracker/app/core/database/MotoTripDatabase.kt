package com.mototriptracker.app.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mototriptracker.app.core.database.dao.TripCaptureDao
import com.mototriptracker.app.core.database.entity.CaptureEventEntity
import com.mototriptracker.app.core.database.entity.LocationGapEntity
import com.mototriptracker.app.core.database.entity.ManualPauseIntervalEntity
import com.mototriptracker.app.core.database.entity.MarkerEntity
import com.mototriptracker.app.core.database.entity.MotorcycleEntity
import com.mototriptracker.app.core.database.entity.PointAssessmentEntity
import com.mototriptracker.app.core.database.entity.ProcessedTrackPointEntity
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.database.entity.RouteEntity
import com.mototriptracker.app.core.database.entity.TagEntity
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.database.entity.TripEditOperationEntity
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.database.entity.TripLineageLinkEntity
import com.mototriptracker.app.core.database.entity.TripPartEntity
import com.mototriptracker.app.core.database.entity.TripStatisticsEntity
import com.mototriptracker.app.core.database.entity.TripStopEntity
import com.mototriptracker.app.core.database.entity.TripTagEntity

/**
 * Room source of truth (ADR-003). Schema v1 — FND-003, covering the full
 * conceptual model from docs/03-architecture/domain-data-model.md (F0.7).
 * schemaVersion bumps and migrations are a separate, later concern (F0.8
 * §16-17) once a v2 is actually needed.
 *
 * Only TripCaptureDao exists so far, to prove and exercise the
 * single-active-capture invariant (FND-003's specific acceptance
 * criterion). Other DAOs are added by the tasks that need them
 * (TRK-*, PRC-*, EDT-*, HIS-*, FAV-*, etc.) rather than pre-built here
 * without a caller.
 */
@Database(
    entities = [
        TripCaptureEntity::class,
        RawTrackPointEntity::class,
        CaptureEventEntity::class,
        ManualPauseIntervalEntity::class,
        TripEntity::class,
        TripPartEntity::class,
        TripEditOperationEntity::class,
        TripLineageLinkEntity::class,
        ProcessedTrackPointEntity::class,
        PointAssessmentEntity::class,
        TripStatisticsEntity::class,
        LocationGapEntity::class,
        TripStopEntity::class,
        RouteEntity::class,
        TagEntity::class,
        TripTagEntity::class,
        MarkerEntity::class,
        MotorcycleEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class MotoTripDatabase : RoomDatabase() {
    abstract fun tripCaptureDao(): TripCaptureDao
}
