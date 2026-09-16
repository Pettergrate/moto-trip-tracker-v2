package com.mototriptracker.app.testing

import com.mototriptracker.app.tracking.processing.ProcessingScheduler

/** Records calls instead of touching a real WorkManager. */
class FakeProcessingScheduler : ProcessingScheduler {
    data class EnqueuedRequest(val tripId: String, val captureId: String)

    val enqueuedRequests = mutableListOf<EnqueuedRequest>()

    override fun enqueueTripProcessing(tripId: String, captureId: String) {
        enqueuedRequests += EnqueuedRequest(tripId, captureId)
    }
}
