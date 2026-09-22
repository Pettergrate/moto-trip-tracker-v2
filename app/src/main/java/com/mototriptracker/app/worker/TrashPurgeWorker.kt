package com.mototriptracker.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mototriptracker.app.BuildConfig
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.dao.TripDao
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.ProcessingVersion
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * TRS-001/PRIV-013: physically deletes every `TRASHED` Trip that has sat past
 * the 30-day retention target (still "reabrible por UX/legal" - a single
 * constant to change if that number ever moves). [TripPurger] does the actual
 * per-Trip work, including domain-data-model.md §15/§16's reference-counting
 * rule for the underlying TripCapture/raw data (ADR-006) - the same path a
 * user's own immediate "delete forever" action uses.
 */
@HiltWorker
class TrashPurgeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tripDao: TripDao,
    private val tripPurger: TripPurger,
    private val diagnosticEventDao: DiagnosticEventDao,
    private val clock: Clock,
    private val idGenerator: IdGenerator
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cutoff = clock.wallClockMillis() - RETENTION_MS
        val eligible = tripDao.findEligibleForPurge(cutoff)
        if (eligible.isEmpty()) return Result.success()

        var purgedCaptures = 0
        for (trip in eligible) {
            purgedCaptures += tripPurger.purge(trip.id)
        }

        diagnosticEventDao.insert(
            DiagnosticEventEntity(
                eventId = idGenerator.newId(),
                occurredAt = clock.wallClockMillis(),
                elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                category = DiagnosticCategory.EDITING_LINEAGE,
                eventType = "TRASH_PURGE_COMPLETED",
                severity = DiagnosticSeverity.INFO,
                source = "trash-purge-worker",
                captureId = null,
                tripId = null,
                correlationId = null,
                stateBefore = null,
                stateAfter = null,
                reasonCode = "RETENTION_WINDOW_ELAPSED",
                metadata = mapOf(
                    "tripsPurged" to eligible.size.toString(),
                    "capturesPurged" to purgedCaptures.toString()
                ),
                appVersion = BuildConfig.VERSION_NAME,
                schemaVersion = 1,
                detectorVersion = DetectorVersion(0),
                locationProfileVersion = LocationProfileVersion(0),
                processingVersion = ProcessingVersion(0)
            )
        )

        return Result.success()
    }

    companion object {
        /** PRIV-013's accepted baseline - owner-reopenable, hence a single named constant, not scattered literals. */
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(30)
    }
}
