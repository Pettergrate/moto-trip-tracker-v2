package com.mototriptracker.app.debug

import android.content.Context
import com.mototriptracker.app.tracking.persistence.RawWriteFaultInjector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** REC-006, debug builds only: binds the flag-file injector, which turns the optional binding in `main` on. */
@Module
@InstallIn(SingletonComponent::class)
object DebugFaultInjectionModule {

    @Provides
    @Singleton
    fun provideRawWriteFaultInjector(@ApplicationContext context: Context): RawWriteFaultInjector =
        FileFlagRawWriteFaultInjector(context.filesDir)
}
