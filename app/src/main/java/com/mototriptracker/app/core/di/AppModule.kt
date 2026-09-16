package com.mototriptracker.app.core.di

import com.mototriptracker.app.core.common.AndroidClock
import com.mototriptracker.app.core.common.AndroidDispatcherProvider
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.DispatcherProvider
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.common.UuidIdGenerator
import com.mototriptracker.app.experiment.AndroidFieldTestDatasetWriter
import com.mototriptracker.app.experiment.FieldTestDatasetWriter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * FND-002 proved the Hilt graph wires up with no bindings. FND-004 added
 * [Clock]/[IdGenerator]; TST-001 added [DispatcherProvider]; EXP-001 adds
 * [FieldTestDatasetWriter] for the F0.6 field-test harness shell.
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
}
