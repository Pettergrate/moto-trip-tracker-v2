package com.mototriptracker.app.core.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * FND-002: proves the Hilt graph wires up. Real `@Provides`/`@Binds` land
 * with the tasks that need them (FND-003 Room, FND-004 clocks, TRK-001
 * location/tracking, etc.) — see docs/05-roadmap/phase1-backlog.md.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule
