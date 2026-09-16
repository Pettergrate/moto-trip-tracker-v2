package com.mototriptracker.app.tracking.coordinator

import com.mototriptracker.app.BuildConfig
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.dao.RawTrackPointDao
import com.mototriptracker.app.core.database.dao.TripCaptureDao
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorState
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.tracking.location.LocationGateway
import kotlinx.coroutines.flow.collect
import java.util.TimeZone
import javax.inject.Inject

/**
 * The only thing that may create/reuse the active [TripCaptureEntity]
 * (TRK-001) or persist [LocationSample]s into Raw Track (TRK-002) — F0.8 §6:
 * "TrackingSessionCoordinator coordina detector, location, persistencia y
 * lifecycle de captura". No Android dependency (ADR-013) —
 * `TrackingForegroundService` is the Android-owning caller (ADR-004); this
 * class is plain, testable logic against the same seams every other task
 * uses (Clock, IdGenerator, DAOs, [LocationGateway]).
 *
 * `detectorVersion`/`locationProfileVersion` are stamped as `DetectorVersion(0)`/
 * `LocationProfileVersion(0)` — explicit placeholders. No real detector or
 * location-sampling profile exists yet (`DET-001`/`TRK-002`); a manual Start
 * doesn't need one (F0.3 §6: "Manual Start bypasses candidate validation"),
 * but the columns are non-null, so a value has to exist. Revisit once those
 * tasks establish real versioning.
 */
class TrackingSessionCoordinator @Inject constructor(
    private val tripCaptureDao: TripCaptureDao,
    private val diagnosticEventDao: DiagnosticEventDao,
    private val rawTrackPointDao: RawTrackPointDao,
    private val locationGateway: LocationGateway,
    private val clock: Clock,
    private val idGenerator: IdGenerator
) {
    sealed interface StartResult {
        val captureId: String
        data class Started(override val captureId: String) : StartResult
        data class AlreadyActive(override val captureId: String) : StartResult
    }

    /**
     * ADR-020/REL-INV-001: idempotent by construction — delegates the actual
     * check-then-insert to [TripCaptureDao.startCaptureIfNoneActive], which
     * already runs it in one transaction (see that DAO for why a raw DB
     * constraint isn't used instead).
     */
    suspend fun startManualCapture(): StartResult {
        val wallNow = clock.wallClockMillis()
        val elapsedNow = clock.elapsedRealtimeNanos()
        val newCapture = TripCaptureEntity(
            id = idGenerator.newId(),
            status = CaptureStatus.ACTIVE,
            startedAt = wallNow,
            endedAt = null,
            startElapsedRealtimeNanos = elapsedNow,
            endElapsedRealtimeNanos = null,
            localTimeZoneId = TimeZone.getDefault().id,
            startSource = StartSource.MANUAL,
            endSource = null,
            detectorVersion = DetectorVersion(0),
            locationProfileVersion = LocationProfileVersion(0),
            createdAt = wallNow,
            updatedAt = wallNow
        )

        val started = tripCaptureDao.startCaptureIfNoneActive(newCapture)
        val result = if (started) {
            StartResult.Started(newCapture.id)
        } else {
            val existing = requireNotNull(tripCaptureDao.findByStatus(CaptureStatus.ACTIVE)) {
                "startCaptureIfNoneActive rejected the insert but no ACTIVE capture is present"
            }
            StartResult.AlreadyActive(existing.id)
        }

        logUserStartCommand(result)
        return result
    }

    /** Used by the service on sticky restart / `Intent == null` recovery. */
    suspend fun findActiveCapture(): TripCaptureEntity? = tripCaptureDao.findByStatus(CaptureStatus.ACTIVE)

    /**
     * TRK-002: collects [locationGateway]'s stream for the lifetime of the
     * caller's coroutine (cancelled by the service on `onDestroy`, per
     * ADR-004 — there is no explicit stop call here). F0.8 §9's pipeline:
     * each sample is stamped with the next `sequenceNumber` and inserted
     * immediately (no in-memory buffering beyond the single in-flight point
     * — "buffer acotado" is trivially satisfied without a separate
     * batching/flush subsystem the architecture doc explicitly defers to a
     * later benchmark).
     *
     * Resumes from `max(sequenceNumber) + 1` rather than assuming a fresh 0:
     * TRK-001's sticky-restart rehydration already continues into an
     * existing ACTIVE capture, so a restart mid-recording must not collide
     * with the (captureId, sequenceNumber) unique index.
     *
     * A raw fix that fails to insert (e.g. a persistent DB failure) is
     * logged as a [DiagnosticCategory.PERSISTENCE]/ERROR event per F0.8 §9's
     * "no debe quedar invisible" rule, then skipped — retrying/buffering
     * failed writes is REC-006's job, not this one's.
     */
    suspend fun recordLocationUpdates(captureId: String) {
        var nextSequenceNumber = (rawTrackPointDao.maxSequenceNumber(captureId) ?: -1) + 1
        locationGateway.locationUpdates().collect { sample ->
            try {
                rawTrackPointDao.insert(sample.toRawTrackPointEntity(captureId, nextSequenceNumber))
                nextSequenceNumber++
            } catch (error: Exception) {
                logRawTrackPointPersistenceFailure(captureId, error)
            }
        }
    }

    private fun LocationSample.toRawTrackPointEntity(captureId: String, sequenceNumber: Long) =
        RawTrackPointEntity(
            captureId = captureId,
            sequenceNumber = sequenceNumber,
            capturedAt = wallTimeEpochMs,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            receivedAtElapsedRealtimeNanos = receivedAtElapsedRealtimeNanos,
            latitude = latitude,
            longitude = longitude,
            horizontalAccuracyM = horizontalAccuracyM,
            altitudeEllipsoidM = altitudeEllipsoidM,
            altitudeMslM = altitudeMslM,
            verticalAccuracyM = verticalAccuracyM,
            speedMps = speedMps,
            speedAccuracyMps = speedAccuracyMps,
            bearingDeg = bearingDeg,
            bearingAccuracyDeg = bearingAccuracyDeg,
            provider = provider,
            isMock = isMock,
            requestProfileId = requestProfileId,
            callbackBatchId = null,
            detectorStateSnapshot = DetectorState.TRACKING.name
        )

    private suspend fun logRawTrackPointPersistenceFailure(captureId: String, error: Throwable) {
        diagnosticEventDao.insert(
            DiagnosticEventEntity(
                eventId = idGenerator.newId(),
                occurredAt = clock.wallClockMillis(),
                elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                category = DiagnosticCategory.PERSISTENCE,
                eventType = "RAW_TRACK_POINT_INSERT_FAILED",
                severity = DiagnosticSeverity.ERROR,
                source = "tracking-service",
                captureId = captureId,
                tripId = null,
                correlationId = null,
                stateBefore = null,
                stateAfter = null,
                reasonCode = error::class.simpleName,
                metadata = emptyMap(),
                appVersion = BuildConfig.VERSION_NAME,
                schemaVersion = 1,
                detectorVersion = DetectorVersion(0),
                locationProfileVersion = LocationProfileVersion(0),
                processingVersion = ProcessingVersion(0)
            )
        )
    }

    private suspend fun logUserStartCommand(result: StartResult) {
        val (captureId, reasonCode) = when (result) {
            is StartResult.Started -> result.captureId to "NEW_CAPTURE"
            is StartResult.AlreadyActive -> result.captureId to "ALREADY_ACTIVE"
        }
        diagnosticEventDao.insert(
            DiagnosticEventEntity(
                eventId = idGenerator.newId(),
                occurredAt = clock.wallClockMillis(),
                elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                category = DiagnosticCategory.USER_COMMAND,
                eventType = "START",
                severity = DiagnosticSeverity.INFO,
                source = "tracking-service",
                captureId = captureId,
                tripId = null,
                correlationId = null,
                stateBefore = null,
                stateAfter = "ACTIVE",
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
}
