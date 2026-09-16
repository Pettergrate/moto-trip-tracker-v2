package com.mototriptracker.app.tracking.processing

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mototriptracker.app.BuildConfig
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.dao.LocationGapDao
import com.mototriptracker.app.core.database.dao.PointAssessmentDao
import com.mototriptracker.app.core.database.dao.ProcessedTrackPointDao
import com.mototriptracker.app.core.database.dao.RawTrackPointDao
import com.mototriptracker.app.core.database.dao.TripPartDao
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.domain.processing.ProcessingEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * PRC-001: runs [ProcessingEngine] over a finished Trip's raw evidence and
 * publishes the derived tables. F0.10 §16.3's ordering: read Raw + compute
 * outside any transaction (pure CPU, safe here because nothing writes to a
 * COMPLETED capture's raw points anymore — TRK-002 only records while
 * ACTIVE), then one short transaction to delete-then-insert each
 * (captureId|tripId, processingVersion)'s derived rows, so re-running this
 * worker for the same version is a clean replace, not a duplicate-row error
 * (F0.10 §16.2's reproducibility requirement).
 */
@HiltWorker
class TripProcessingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val database: MotoTripDatabase,
    private val tripPartDao: TripPartDao,
    private val rawTrackPointDao: RawTrackPointDao,
    private val pointAssessmentDao: PointAssessmentDao,
    private val processedTrackPointDao: ProcessedTrackPointDao,
    private val locationGapDao: LocationGapDao,
    private val diagnosticEventDao: DiagnosticEventDao,
    private val processingEngine: ProcessingEngine,
    private val clock: Clock,
    private val idGenerator: IdGenerator
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val tripId = inputData.getString(KEY_TRIP_ID) ?: return Result.failure()
        val captureId = inputData.getString(KEY_CAPTURE_ID) ?: return Result.failure()

        val parts = tripPartDao.findAllByTrip(tripId)
        if (parts.isEmpty()) return Result.failure()

        val rawPointsByCapture = parts.associate { part -> part.captureId to rawTrackPointDao.findAllByCapture(part.captureId) }
        val outcome = processingEngine.process(tripId, CURRENT_PROCESSING_VERSION, parts, rawPointsByCapture)

        database.withTransaction {
            for (sourceCaptureId in rawPointsByCapture.keys) {
                pointAssessmentDao.deleteByCaptureAndVersion(sourceCaptureId, CURRENT_PROCESSING_VERSION)
            }
            processedTrackPointDao.deleteByTripAndVersion(tripId, CURRENT_PROCESSING_VERSION)
            locationGapDao.deleteByTripAndVersion(tripId, CURRENT_PROCESSING_VERSION)

            pointAssessmentDao.insertAll(outcome.assessments)
            processedTrackPointDao.insertAll(outcome.processedPoints)
            locationGapDao.insertAll(outcome.gaps)
        }

        diagnosticEventDao.insert(
            DiagnosticEventEntity(
                eventId = idGenerator.newId(),
                occurredAt = clock.wallClockMillis(),
                elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                category = DiagnosticCategory.PROCESSING_WORKER,
                eventType = "TRIP_PROCESSING_COMPLETED",
                severity = DiagnosticSeverity.INFO,
                source = "trip-processing-worker",
                captureId = captureId,
                tripId = tripId,
                correlationId = null,
                stateBefore = null,
                stateAfter = "READY",
                reasonCode = "PROCESSING_COMPLETED",
                metadata = mapOf(
                    "acceptedPoints" to outcome.processedPoints.size.toString(),
                    "rejectedPoints" to (outcome.assessments.size - outcome.processedPoints.size).toString(),
                    "gapCount" to outcome.gaps.size.toString()
                ),
                appVersion = BuildConfig.VERSION_NAME,
                schemaVersion = 1,
                detectorVersion = DetectorVersion(0),
                locationProfileVersion = LocationProfileVersion(0),
                processingVersion = CURRENT_PROCESSING_VERSION
            )
        )

        return Result.success()
    }

    companion object {
        const val KEY_TRIP_ID = "tripId"
        const val KEY_CAPTURE_ID = "captureId"

        /** PRC-001's initial algorithm — the first *real* processing version, not a "no pipeline" placeholder. */
        val CURRENT_PROCESSING_VERSION = ProcessingVersion(0)
    }
}
