package com.mototriptracker.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * TRK-004: a `@HiltWorker` (e.g. `TripProcessingWorker`) needs a
 * [HiltWorkerFactory] to be instantiated with its injected dependencies
 * instead of WorkManager's reflection-based default factory, which only
 * knows how to call a plain `(Context, WorkerParameters)` constructor.
 *
 * Implementing `Configuration.Provider` and letting WorkManager's on-demand
 * initializer auto-detect it does *not* work here — that initializer runs
 * as a ContentProvider, created before `Application.onCreate()` runs Hilt's
 * field injection, so [workerFactory] isn't set yet when it would be read.
 * Verified on-device: with only `Configuration.Provider`, WorkManager
 * silently fell back to its default configuration (no crash, no error) and
 * every enqueued `TripProcessingWorker` failed with a `NoSuchMethodException`
 * on its Hilt-only constructor. The manifest disables the default
 * initializer (see AndroidManifest.xml) and this calls
 * `WorkManager.initialize()` explicitly, after injection has already run.
 */
@HiltAndroidApp
class MotoTripApplication : Application() {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(this, Configuration.Builder().setWorkerFactory(workerFactory).build())
        }
    }
}
