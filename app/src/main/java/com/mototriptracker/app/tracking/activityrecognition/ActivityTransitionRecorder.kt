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
    /**
     * A broadcast from Play Services that held nothing usable. `WARN`, because a working setup delivers transitions: seeing
     * these is the sign that something between Google and the receiver is wrong. Carries only the reason - no coordinates,
     * no activity, nothing that describes movement.
     */
    suspend fun recordEmptyBroadcast(reason: String) {
        diagnosticEventDao.insert(
            DiagnosticEventEntity(
                eventId = idGenerator.newId(),
                occurredAt = clock.wallClockMillis(),
                elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                category = DiagnosticCategory.ACTIVITY_RECOGNITION,
                eventType = "ACTIVITY_BROADCAST_EMPTY",
                severity = DiagnosticSeverity.WARN,
                source = "ActivityTransitionReceiver",
                captureId = null,
                tripId = null,
                correlationId = null,
                stateBefore = null,
                stateAfter = null,
                reasonCode = reason,
                metadata = emptyMap(),
                appVersion = BuildConfig.VERSION_NAME,
                schemaVersion = 1,
                detectorVersion = DetectorVersion(0),
                locationProfileVersion = LocationProfileVersion(0),
                processingVersion = ProcessingVersion(0)
            )
        )
    }

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

    /**
     * PERM-002 / AUTO-001 follow-up (2026-09-29): found on the owner's phone - Play Services correctly delivered and
     * classified two `IN_VEHICLE` `ENTER` transitions matching a real commute, both recorded, and still no trip started.
     * Nothing crashed; the decision that follows an `IN_VEHICLE ENTER` simply had no record of what it decided or why.
     * Every one of [ActivityTransitionReceiver.maybeStartAutoDetection]'s branches now leaves one, category `DETECTOR`
     * (the same category `TrackingSessionCoordinator` uses for an auto-started/finished capture) - so the *next* one has
     * an answer instead of a silence to interpret.
     */
    suspend fun recordAutoDetectionDecision(eventType: String, reasonCode: String, stateAfter: String? = null) {
        diagnosticEventDao.insert(
            DiagnosticEventEntity(
                eventId = idGenerator.newId(),
                occurredAt = clock.wallClockMillis(),
                elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                category = DiagnosticCategory.DETECTOR,
                eventType = eventType,
                severity = DiagnosticSeverity.INFO,
                source = "ActivityTransitionReceiver",
                captureId = null,
                tripId = null,
                correlationId = null,
                stateBefore = null,
                stateAfter = stateAfter,
                reasonCode = reasonCode,
                metadata = emptyMap(),
                appVersion = BuildConfig.VERSION_NAME,
                schemaVersion = 1,
                detectorVersion = DetectorVersion(0),
                locationProfileVersion = LocationProfileVersion(0),
                processingVersion = ProcessingVersion(0)
            )
        )
    }

    companion object {
        /** The vocabulary of `reasonCode` on an `ACTIVITY_BROADCAST_EMPTY` event. */
        const val EMPTY_NO_RESULT = "NO_RESULT"
        const val EMPTY_UNREADABLE = "UNREADABLE"
        const val EMPTY_NO_EVENTS = "NO_EVENTS"
        const val EMPTY_NO_USABLE_EVENTS = "NO_USABLE_EVENTS"

        /** The vocabulary of `eventType`/`reasonCode` on an auto-detection decision. */
        const val EVENT_AUTO_DETECTION_STARTED = "AUTO_DETECTION_STARTED"
        const val EVENT_AUTO_DETECTION_NOT_STARTED = "AUTO_DETECTION_NOT_STARTED"
        const val REASON_IN_VEHICLE_ENTER = "IN_VEHICLE_ENTER"
        const val REASON_CAPTURE_ALREADY_ACTIVE = "CAPTURE_ALREADY_ACTIVE"
        /** [stateAfter] carries the actual [com.mototriptracker.app.core.model.CapabilityMode] the resolver returned. */
        const val REASON_CAPABILITY_NOT_ELIGIBLE = "CAPABILITY_NOT_ELIGIBLE"
        const val REASON_POST_FINISH_SUPPRESSED = "POST_FINISH_SUPPRESSED"
        /** The one branch that is not a silent early return: `startForegroundService` itself refused or threw. */
        const val REASON_SERVICE_START_FAILED = "SERVICE_START_FAILED"
    }
}
