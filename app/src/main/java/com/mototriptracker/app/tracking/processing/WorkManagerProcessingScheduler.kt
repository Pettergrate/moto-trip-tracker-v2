package com.mototriptracker.app.tracking.processing

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import javax.inject.Inject

/**
 * F0.10 §16.2's idempotency key is conceptually `tripId/captureId +
 * processingVersion` — with no real `processingVersion` selection yet
 * (that's PRC-001's job too), this keys unique work by [tripId] alone,
 * which is enough given [ExistingWorkPolicy.KEEP]: a Trip is only ever
 * created once per Finish (REL-INV-007), and
 * `TrackingSessionCoordinator.finishCapture` only calls this on that fresh
 * creation, never on an idempotent-retry Finish — so nothing here needs to
 * defend against a second enqueue for the same trip.
 */
class WorkManagerProcessingScheduler @Inject constructor(
    private val workManager: WorkManager
) : ProcessingScheduler {

    override fun enqueueTripProcessing(tripId: String, captureId: String) {
        val request = OneTimeWorkRequestBuilder<TripProcessingWorker>()
            .setInputData(
                workDataOf(
                    TripProcessingWorker.KEY_TRIP_ID to tripId,
                    TripProcessingWorker.KEY_CAPTURE_ID to captureId
                )
            )
            .build()

        workManager.enqueueUniqueWork(uniqueWorkName(tripId), ExistingWorkPolicy.KEEP, request)
    }

    private fun uniqueWorkName(tripId: String) = "trip-processing-$tripId"
}
