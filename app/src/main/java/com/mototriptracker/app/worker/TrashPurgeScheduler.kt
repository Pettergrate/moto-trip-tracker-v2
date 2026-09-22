package com.mototriptracker.app.worker

/**
 * TRS-001/ADR-010: enqueues [TrashPurgeWorker] as recurring, deferrable
 * maintenance work. A seam (like `ProcessingScheduler`) so anything that
 * needs to ensure the purge job is scheduled doesn't need a real
 * WorkManager/Robolectric wiring in its own tests.
 */
interface TrashPurgeScheduler {
    fun schedulePeriodicPurge()
}
