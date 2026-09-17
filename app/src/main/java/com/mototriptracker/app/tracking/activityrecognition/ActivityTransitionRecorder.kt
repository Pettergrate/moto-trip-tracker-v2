package com.mototriptracker.app.tracking.activityrecognition

import com.mototriptracker.app.BuildConfig
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.ProcessingVersion
import javax.inject.Inject

/**
 * DET-001: persists each [ActivityTransitionSample] as a [DiagnosticEventEntity]
 * (`ACTIVITY_RECOGNITION` category — DIA-001 already reserved it). This is
 * this task's whole scope: prove passive registration/reception/restoration
 * works and leave a durable, queryable trail. Turning these into actual
 * Candidate Start/Stop decisions is `DET-002`/`DET-003`'s job, not this
 * one's — this doesn't try to guess at that state machine.
 *
 * No Android dependency (ADR-013's spirit, like `TrackingSessionCoordinator`)
 * — [ActivityTransitionSample] is the already-converted, framework-free
 * model; the Android-touching conversion happens in `ActivityTransitionReceiver`.
 */
class ActivityTransitionRecorder @Inject constructor(
    private val diagnosticEventDao: DiagnosticEventDao,
    private val clock: Clock,
    private val idGenerator: IdGenerator
) {
    suspend fun record(sample: ActivityTransitionSample) {
        diagnosticEventDao.insert(
            DiagnosticEventEntity(
                eventId = idGenerator.newId(),
                occurredAt = clock.wallClockMillis(),
                elapsedRealtimeNanos = sample.elapsedRealtimeNanos,
                category = DiagnosticCategory.ACTIVITY_RECOGNITION,
                eventType = "ACTIVITY_TRANSITION",
                severity = DiagnosticSeverity.INFO,
                source = sample.source,
                captureId = null,
                tripId = null,
                correlationId = null,
                stateBefore = null,
                stateAfter = sample.activityType.name,
                reasonCode = sample.transitionType.name,
                metadata = sample.confidence?.let { mapOf("confidence" to it.toString()) } ?: emptyMap(),
                appVersion = BuildConfig.VERSION_NAME,
                schemaVersion = 1,
                detectorVersion = DetectorVersion(0),
                locationProfileVersion = LocationProfileVersion(0),
                processingVersion = ProcessingVersion(0)
            )
        )
    }
}
