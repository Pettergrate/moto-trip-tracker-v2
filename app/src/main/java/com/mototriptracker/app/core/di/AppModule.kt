package com.mototriptracker.app.core.di

import com.mototriptracker.app.core.common.AndroidClock
import com.mototriptracker.app.core.common.AndroidDispatcherProvider
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.DispatcherProvider
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.common.UuidIdGenerator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * FND-002 proved the Hilt graph wires up with no bindings. FND-004 added
 * [Clock]/[IdGenerator]; TST-001 adds [DispatcherProvider], the last of the
 * ADR-013 seams needed before domain/data logic can be written without a
 * direct Android/coroutine-dispatcher dependency.
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
}
