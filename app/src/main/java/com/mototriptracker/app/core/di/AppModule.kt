package com.mototriptracker.app.core.di

import android.content.Context
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.mototriptracker.app.core.common.AndroidClock
import com.mototriptracker.app.core.common.AndroidDispatcherProvider
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.DispatcherProvider
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.common.UuidIdGenerator
import com.mototriptracker.app.experiment.AndroidFieldTestDatasetWriter
import com.mototriptracker.app.experiment.FieldTestDatasetWriter
import com.mototriptracker.app.tracking.location.FusedLocationGateway
import com.mototriptracker.app.tracking.location.LocationGateway
import com.mototriptracker.app.tracking.processing.ProcessingScheduler
import com.mototriptracker.app.tracking.processing.WorkManagerProcessingScheduler
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * FND-002 proved the Hilt graph wires up with no bindings. FND-004 added
 * [Clock]/[IdGenerator]; TST-001 added [DispatcherProvider]; EXP-001 added
 * [FieldTestDatasetWriter]; TRK-002 added [LocationGateway]; TRK-004 adds
 * [ProcessingScheduler].
 */
@Module
@InstallIn(SingletonComponent::class)
interface AppModule {

    @Binds
    fun bindClock(impl: AndroidClock): Clock

    @Binds
    fun bindIdGenerator(impl: UuidIdGenerator): IdGenerator

    @Binds
    fun bindDispatcherProvider(impl: AndroidDispatcherProvider): DispatcherProvider

    @Binds
    fun bindFieldTestDatasetWriter(impl: AndroidFieldTestDatasetWriter): FieldTestDatasetWriter

    @Binds
    fun bindLocationGateway(impl: FusedLocationGateway): LocationGateway

    @Binds
    fun bindProcessingScheduler(impl: WorkManagerProcessingScheduler): ProcessingScheduler

    companion object {
        @Provides
        fun provideFusedLocationProviderClient(
            @ApplicationContext context: Context
        ): FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

        @Provides
        fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
            WorkManager.getInstance(context)
    }
}
