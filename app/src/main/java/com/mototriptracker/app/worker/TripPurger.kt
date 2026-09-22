package com.mototriptracker.app.worker

import androidx.room.withTransaction
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.dao.TripCaptureDao
import com.mototriptracker.app.core.database.dao.TripDao
import com.mototriptracker.app.core.database.dao.TripPartDao
import javax.inject.Inject

/**
 * TRS-001. Physically deletes a Trip and, only once no other TripPart
 * references it, the TripCapture(s) it owned - domain-data-model.md §15/§16's
 * reference-counting rule, shared by [TrashPurgeWorker]'s scheduled run and a
 * user's own immediate "delete forever" so both go through exactly one
 * tested path rather than two copies of the same logic. Lives here rather
 * than in `domain/` - it needs `MotoTripDatabase.withTransaction` directly,
 * and ADR-013 keeps `domain/` free of any Android/Room dependency (enforced
 * by `DomainBoundaryTest`), the same reason `TrackingSessionCoordinator`
 * lives outside `domain/` too.
 */
class TripPurger @Inject constructor(
    private val database: MotoTripDatabase,
    private val tripDao: TripDao,
    private val tripPartDao: TripPartDao,
    private val tripCaptureDao: TripCaptureDao
) {
    /** @return how many now-unreferenced TripCaptures were also purged alongside [tripId]. */
    suspend fun purge(tripId: String): Int {
        var purgedCaptures = 0
        database.withTransaction {
            val captureIds = tripPartDao.findAllByTrip(tripId).map { it.captureId }
            tripDao.deleteById(tripId)
            for (captureId in captureIds) {
                if (tripPartDao.findByCaptureId(captureId) == null) {
                    tripCaptureDao.deleteById(captureId)
                    purgedCaptures++
                }
            }
        }
        return purgedCaptures
    }
}
