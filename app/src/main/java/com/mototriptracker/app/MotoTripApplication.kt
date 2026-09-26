package com.mototriptracker.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.mototriptracker.app.tracking.activityrecognition.ActivityRecognitionRegistrar
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import com.mototriptracker.app.tracking.recovery.RebootReconciler
import com.mototriptracker.app.tracking.recovery.UserStopReconciler
import com.mototriptracker.app.worker.DerivedDataReconciler
import com.mototriptracker.app.worker.TrashPurgeScheduler
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    @Inject lateinit var activityRecognitionRegistrar: ActivityRecognitionRegistrar

    /**
     * `Lazy`, not a plain `@Inject lateinit var` like the two fields above -
     * [TrashPurgeScheduler]'s own implementation needs a `WorkManager`
     * instance, and Hilt resolves every eagerly-injected field during the
     * same `super.onCreate()` call that runs *before* the
     * `WorkManager.initialize()` line below - a plain field here would
     * construct it too early and crash with "WorkManager is not initialized
     * properly" (verified: this is exactly what happened on the first
     * version of this change). `Lazy` defers construction until [get] is
     * called, which this does only after initialization.
     */
    @Inject lateinit var trashPurgeScheduler: Lazy<TrashPurgeScheduler>

    /** `Lazy` for the same reason as [trashPurgeScheduler]: it enqueues through WorkManager. */
    @Inject lateinit var derivedDataReconciler: Lazy<DerivedDataReconciler>

    /** REC-003: `Lazy` for the same WorkManager-ordering reason as the two above. */
    @Inject lateinit var trackingCoordinator: Lazy<TrackingSessionCoordinator>

    /** REC-004: `Lazy` for the same reason - it (via the coordinator) enqueues through WorkManager. */
    @Inject lateinit var userStopReconciler: Lazy<UserStopReconciler>

    /** REC-003: `Lazy` for the same reason. */
    @Inject lateinit var rebootReconciler: Lazy<RebootReconciler>

    override fun onCreate() {
        super.onCreate()
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(this, Configuration.Builder().setWorkerFactory(workerFactory).build())
        }
        // DET-001/ADR-007: registration doesn't survive reboot/update either
        // (BootReceiver handles those separately) - this is just the
        // normal-start/first-run case, same idempotent call.
        activityRecognitionRegistrar.register()
        // TRS-001: ExistingPeriodicWorkPolicy.KEEP makes this idempotent too.
        trashPurgeScheduler.get().schedulePeriodicPurge()
        // EDT-004: heal Trips left without derived data (e.g. a crash between a
        // merge/split/trim commit and its processing being enqueued). Off the main
        // thread; a failure here must never take the app down.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // REC-003/F0.10 §25: a capture left ACTIVE by a previous boot is sealed on
            // the next process start too (definitive elapsedRealtime test only).
            // REC-004/F0.10 §9: a user Stop / Force stop must not be silently revived.
            runCatching { userStopReconciler.get().reconcile() }
            // REC-003: also on every process start, for OEMs that restrict boot receivers.
            runCatching { rebootReconciler.get().reconcile() }
            runCatching { trackingCoordinator.get().reconcileActiveCaptureAfterReboot() }
            runCatching { derivedDataReconciler.get().reconcile() }
        }
    }
}
