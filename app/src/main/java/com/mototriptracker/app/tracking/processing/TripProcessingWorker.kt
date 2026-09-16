package com.mototriptracker.app.tracking.processing

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mototriptracker.app.BuildConfig
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.ProcessingVersion
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * TRK-004: the enqueue mechanism ADR-010 asks for, wired end-to-end and
 * tested — with no real pipeline behind it yet. `PRC-001` ("derive
 * accepted/rejected point assessments and Processed Track") is the task
 * that gives this a real body (Raw → assessment → ProcessedTrackPoint →
 * TripStatistics per F0.8 §9-10); until then this only records that
 * processing was requested, so a Finish doesn't silently promise work that
 * never happens anywhere, even as a placeholder.
 */
@HiltWorker
class TripProcessingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val diagnosticEventDao: DiagnosticEventDao,
    private val clock: Clock,
    private val idGenerator: IdGenerator
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val tripId = inputData.getString(KEY_TRIP_ID) ?: return Result.failure()
        val captureId = inputData.getString(KEY_CAPTURE_ID) ?: return Result.failure()

        diagnosticEventDao.insert(
            DiagnosticEventEntity(
                eventId = idGenerator.newId(),
                occurredAt = clock.wallClockMillis(),
                elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                category = DiagnosticCategory.PROCESSING_WORKER,
                eventType = "TRIP_PROCESSING_REQUESTED_NO_PIPELINE_YET",
                severity = DiagnosticSeverity.INFO,
                source = "trip-processing-worker",
                captureId = captureId,
                tripId = tripId,
                correlationId = null,
                stateBefore = null,
                stateAfter = null,
                reasonCode = "PRC_001_NOT_IMPLEMENTED",
                metadata = emptyMap(),
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
        const val KEY_TRIP_ID = "tripId"
        const val KEY_CAPTURE_ID = "captureId"
    }
}
