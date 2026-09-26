package com.mototriptracker.app.worker

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Once a day is plenty against a 14-day window. `KEEP` so every app start re-asserting the schedule does
 * not reset an already-running periodic job's cycle (same as [WorkManagerTrashPurgeScheduler]).
 */
class WorkManagerDiagnosticPurgeScheduler @Inject constructor(
    private val workManager: WorkManager
) : DiagnosticPurgeScheduler {

    override fun schedulePeriodicPurge() {
        val request = PeriodicWorkRequestBuilder<DiagnosticPurgeWorker>(1, TimeUnit.DAYS).build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "diagnostic-purge"
    }
}
