package com.mototriptracker.app.core.di

import android.content.Context
import androidx.room.Room
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.dao.CaptureEventDao
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.dao.LocationGapDao
import com.mototriptracker.app.core.database.dao.PointAssessmentDao
import com.mototriptracker.app.core.database.dao.ProcessedTrackPointDao
import com.mototriptracker.app.core.database.dao.RawTrackPointDao
import com.mototriptracker.app.core.database.dao.TripCaptureDao
import com.mototriptracker.app.core.database.dao.TripDao
import com.mototriptracker.app.core.database.dao.TripPartDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * TRK-001: the first production consumer that actually needs a
 * [MotoTripDatabase] instance injected (`TrackingSessionCoordinator`).
 * FND-003 built the schema/DAOs but never wired a real builder — nothing
 * needed one yet; this is that builder.
 *
 * No `fallbackToDestructiveMigration()` (F0.8/F0.10: never destructive in a
 * build with real user history) and no migrations defined yet — schema v1
 * is still pre-release, so there is nothing to migrate from.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val DATABASE_NAME = "moto-trip-tracker.db"

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MotoTripDatabase =
        Room.databaseBuilder(context, MotoTripDatabase::class.java, DATABASE_NAME).build()

    @Provides
    fun provideTripCaptureDao(database: MotoTripDatabase): TripCaptureDao = database.tripCaptureDao()

    @Provides
    fun provideDiagnosticEventDao(database: MotoTripDatabase): DiagnosticEventDao = database.diagnosticEventDao()

    @Provides
    fun provideRawTrackPointDao(database: MotoTripDatabase): RawTrackPointDao = database.rawTrackPointDao()

    @Provides
    fun provideCaptureEventDao(database: MotoTripDatabase): CaptureEventDao = database.captureEventDao()

    @Provides
    fun provideTripDao(database: MotoTripDatabase): TripDao = database.tripDao()

    @Provides
    fun provideTripPartDao(database: MotoTripDatabase): TripPartDao = database.tripPartDao()

    @Provides
    fun providePointAssessmentDao(database: MotoTripDatabase): PointAssessmentDao = database.pointAssessmentDao()

    @Provides
    fun provideProcessedTrackPointDao(database: MotoTripDatabase): ProcessedTrackPointDao =
        database.processedTrackPointDao()

    @Provides
    fun provideLocationGapDao(database: MotoTripDatabase): LocationGapDao = database.locationGapDao()
}
