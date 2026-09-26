package com.mototriptracker.app.core.di

import com.mototriptracker.app.tracking.persistence.RawPointWriter
import com.mototriptracker.app.tracking.persistence.RawWriteFaultInjector
import dagger.Module
import dagger.Provides
import dagger.BindsOptionalOf
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Named

/**
 * REC-006: declares that a [RawWriteFaultInjector] *may* be bound. Nothing in `main` binds one - only
 * the `debug` source set does - so in a release build the [Optional] is empty and the app behaves as if
 * this seam did not exist.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FaultInjectionModule {

    @BindsOptionalOf
    abstract fun optionalRawWriteFaultInjector(): RawWriteFaultInjector

    companion object {
        /** The raw-point buffer size: the production default unless a debug build overrides it to make an overflow quick to reach. */
        @Provides
        @Named(RAW_BUFFER_CAPACITY)
        fun provideRawBufferCapacity(faults: Optional<RawWriteFaultInjector>): Int =
            if (faults.isPresent) faults.get().bufferCapacity() ?: RawPointWriter.DEFAULT_CAPACITY else RawPointWriter.DEFAULT_CAPACITY
    }
}

const val RAW_BUFFER_CAPACITY = "rawBufferCapacity"
