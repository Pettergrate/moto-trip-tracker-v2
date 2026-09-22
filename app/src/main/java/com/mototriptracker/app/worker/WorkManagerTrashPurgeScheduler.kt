package com.mototriptracker.app.worker

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * A once-a-day cadence is plenty against a 30-day retention window - no
 * need for anything finer-grained. [ExistingPeriodicWorkPolicy.KEEP] so
 * every app start (`MotoTripApplication.onCreate`) re-asserting the schedule
 * doesn't reset an already-running periodic job's cycle.
 */
class WorkManagerTrashPurgeScheduler @Inject constructor(
    private val workManager: WorkManager
) : TrashPurgeScheduler {

    override fun schedulePeriodicPurge() {
        val request = PeriodicWorkRequestBuilder<TrashPurgeWorker>(1, TimeUnit.DAYS).build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "trash-purge"
    }
}
