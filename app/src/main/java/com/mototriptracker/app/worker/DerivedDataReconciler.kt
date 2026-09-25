package com.mototriptracker.app.worker

import com.mototriptracker.app.core.database.dao.TripDao
import com.mototriptracker.app.core.database.dao.TripPartDao
import com.mototriptracker.app.tracking.processing.ProcessingScheduler
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import javax.inject.Inject

/**
 * EDT-004/domain-data-model.md §17 ("stats/processed outputs quedan
 * invalidados hasta recalcular"; "si source cambia, el derivado se
 * invalida/recalcula") and reliability-recovery.md §20 ("Merge/Split crash
 * después commit -> Reprocessing pendiente"): finds every visible Trip whose
 * derived data doesn't exist for the *current* `processingVersion` and
 * (re)enqueues its processing.
 *
 * Merge/split/trim each commit their structural change first and enqueue the
 * recompute after (ADR-015) - correct, but if the process dies in the gap the
 * new Trips would be left without statistics, with nothing ever going to
 * compute them. Running this once per app start closes that gap. The same
 * check also covers a failed worker and, later, a `processingVersion` bump
 * (ADR-014: a Trip with only an *older* version's rows counts as missing the
 * current one - the old rows stay, versions coexist).
 *
 * Idempotent and cheap when nothing is missing: the scheduler keys unique work
 * by tripId with `KEEP`, so a Trip whose processing is already queued or
 * running is left alone, and a Trip that has statistics isn't selected at all.
 */
class DerivedDataReconciler @Inject constructor(
    private val tripDao: TripDao,
    private val tripPartDao: TripPartDao,
    private val processingScheduler: ProcessingScheduler
) {
    /** @return how many Trips had their processing (re)enqueued. */
    suspend fun reconcile(): Int {
        var enqueued = 0
        for (trip in tripDao.findCompletedWithoutStatistics(TripProcessingWorker.CURRENT_PROCESSING_VERSION)) {
            // A Trip with no parts has nothing to compute from - skip rather than enqueue work that can only fail.
            val representativeCapture = tripPartDao.findAllByTrip(trip.id).firstOrNull()?.captureId ?: continue
            processingScheduler.enqueueTripProcessing(trip.id, representativeCapture)
            enqueued++
        }
        return enqueued
    }
}
