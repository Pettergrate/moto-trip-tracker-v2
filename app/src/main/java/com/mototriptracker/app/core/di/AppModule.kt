package com.mototriptracker.app.core.di

import com.mototriptracker.app.core.common.AndroidClock
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.common.UuidIdGenerator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * FND-002 proved the Hilt graph wires up with no bindings. FND-004 adds the
 * first real ones: [Clock] and [IdGenerator], the two seams ADR-013 requires
 * so domain/data code never touches Android's clock or UUID APIs directly.
 * Further `@Binds`/`@Provides` land with the tasks that need them (FND-003
 * Room instance, TRK-001 location/tracking, etc.).
 */
@Module
@InstallIn(SingletonComponent::class)
interface AppModule {

    @Binds
    fun bindClock(impl: AndroidClock): Clock

    @Binds
    fun bindIdGenerator(impl: UuidIdGenerator): IdGenerator
}
