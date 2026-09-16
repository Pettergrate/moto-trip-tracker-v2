package com.mototriptracker.app.tracking.processing

/**
 * F0.10 §15.1 step 6 / ADR-010: enqueues the deferrable post-Finish
 * processing work. A seam (like [com.mototriptracker.app.tracking.location.LocationGateway])
 * so [com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator]'s
 * tests don't need a real WorkManager/Robolectric wiring just to prove the
 * Finish transaction itself is correct.
 */
interface ProcessingScheduler {
    fun enqueueTripProcessing(tripId: String, captureId: String)
}
