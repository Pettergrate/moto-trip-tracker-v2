package com.mototriptracker.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** DIA-004: the scheduled run of [DiagnosticPurger] (thin on purpose - the logic is tested without WorkManager). */
@HiltWorker
class DiagnosticPurgeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val purger: DiagnosticPurger
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        purger.purge()
        return Result.success()
    }
}

/** A seam like [TrashPurgeScheduler], so nothing that needs the job scheduled has to wire a real WorkManager. */
interface DiagnosticPurgeScheduler {
    fun schedulePeriodicPurge()
}
