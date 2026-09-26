package com.mototriptracker.app.core.di

import android.content.Context
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.mototriptracker.app.core.common.AndroidClock
import com.mototriptracker.app.core.common.AndroidDispatcherProvider
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.DispatcherProvider
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.common.UuidIdGenerator
import com.mototriptracker.app.experiment.AndroidFieldTestDatasetWriter
import com.mototriptracker.app.experiment.AndroidFieldTestDeviceInfoProvider
import com.mototriptracker.app.experiment.AndroidFieldTestHarnessStateStore
import com.mototriptracker.app.experiment.FieldTestDatasetWriter
import com.mototriptracker.app.experiment.FieldTestDeviceInfoProvider
import com.mototriptracker.app.experiment.FieldTestHarnessStateStore
import com.mototriptracker.app.tracking.capability.AndroidCapabilityInputsProvider
import com.mototriptracker.app.tracking.capability.CapabilityInputsProvider
import com.mototriptracker.app.tracking.location.FusedLocationGateway
import com.mototriptracker.app.tracking.location.InMemoryLocationProfileSelector
import com.mototriptracker.app.tracking.location.LocationGateway
import com.mototriptracker.app.tracking.location.LocationProfileSelector
import com.mototriptracker.app.tracking.processing.ProcessingScheduler
import com.mototriptracker.app.tracking.processing.WorkManagerProcessingScheduler
import com.mototriptracker.app.worker.TrashPurgeScheduler
import com.mototriptracker.app.worker.WorkManagerTrashPurgeScheduler
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * FND-002 proved the Hilt graph wires up with no bindings. FND-004 added
 * [Clock]/[IdGenerator]; TST-001 added [DispatcherProvider]; EXP-001 added
 * [FieldTestDatasetWriter]; TRK-002 added [LocationGateway]; TRK-004 adds
 * [ProcessingScheduler]; AUTO-001 adds [CapabilityInputsProvider].
 */
@Module
@InstallIn(SingletonComponent::class)
interface AppModule {

    @Binds
    fun bindClock(impl: AndroidClock): Clock

    /** REC-004. */
    @Binds
    fun bindProcessExitReasonReader(impl: com.mototriptracker.app.tracking.recovery.AndroidProcessExitReasonReader): com.mototriptracker.app.tracking.recovery.ProcessExitReasonReader

    @Binds
    fun bindBootCountReader(impl: com.mototriptracker.app.tracking.recovery.AndroidBootCountReader): com.mototriptracker.app.tracking.recovery.BootCountReader

    @Binds
    fun bindHandledExitStore(impl: com.mototriptracker.app.tracking.recovery.DataStoreHandledExitStore): com.mototriptracker.app.tracking.recovery.HandledExitStore

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

    @Binds
    fun bindTrashPurgeScheduler(impl: WorkManagerTrashPurgeScheduler): TrashPurgeScheduler

    @Binds
    fun bindCapabilityInputsProvider(impl: AndroidCapabilityInputsProvider): CapabilityInputsProvider

    @Binds
    fun bindFieldTestDeviceInfoProvider(impl: AndroidFieldTestDeviceInfoProvider): FieldTestDeviceInfoProvider

    @Binds
    fun bindFieldTestHarnessStateStore(impl: AndroidFieldTestHarnessStateStore): FieldTestHarnessStateStore

    /**
     * `@Singleton` is required here despite [InMemoryLocationProfileSelector]
     * already carrying it on its own class - a `@Binds` interface binding
     * needs the scope repeated, or `FusedLocationGateway` and
     * `FieldTestHarnessViewModel` would each get their own separate instance
     * and a harness-selected profile would never reach real tracking.
     */
    @Binds
    @Singleton
    fun bindLocationProfileSelector(impl: InMemoryLocationProfileSelector): LocationProfileSelector

    companion object {
        @Provides
        fun provideFusedLocationProviderClient(
            @ApplicationContext context: Context
        ): FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

        @Provides
        fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
            WorkManager.getInstance(context)

        @Provides
        fun provideActivityRecognitionClient(@ApplicationContext context: Context): ActivityRecognitionClient =
            ActivityRecognition.getClient(context)
    }
}
