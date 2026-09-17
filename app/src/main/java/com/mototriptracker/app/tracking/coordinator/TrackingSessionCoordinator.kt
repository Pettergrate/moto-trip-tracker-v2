package com.mototriptracker.app.tracking.coordinator

import androidx.room.withTransaction
import com.mototriptracker.app.BuildConfig
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.dao.CaptureEventDao
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.dao.RawTrackPointDao
import com.mototriptracker.app.core.database.dao.TripCaptureDao
import com.mototriptracker.app.core.database.dao.TripDao
import com.mototriptracker.app.core.database.dao.TripPartDao
import com.mototriptracker.app.core.database.entity.CaptureEventEntity
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.database.entity.TripPartEntity
import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorState
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.EndSource
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.domain.detection.CandidateStartDecision
import com.mototriptracker.app.domain.detection.CandidateStartEngine
import com.mototriptracker.app.domain.detection.CandidateStopDecision
import com.mototriptracker.app.domain.detection.CandidateStopEngine
import com.mototriptracker.app.domain.detection.DetectionEvent
import com.mototriptracker.app.tracking.location.LocationGateway
import com.mototriptracker.app.tracking.processing.ProcessingScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.util.TimeZone
import javax.inject.Inject

/**
 * The only thing that may create/reuse the active [TripCaptureEntity]
 * (TRK-001), persist [LocationSample]s into Raw Track (TRK-002), finish a
 * capture into a logical Trip (TRK-004), or run a whole automatic
 * candidate-start-to-candidate-stop session end to end ([runAutoDetection],
 * AUTO-001) — F0.8 §6: "TrackingSessionCoordinator coordina detector,
 * location, persistencia y lifecycle de captura". No Android dependency
 * (ADR-013) — `TrackingForegroundService` is the Android-owning caller
 * (ADR-004); this class is plain, testable logic against the same seams
 * every other task uses (Clock, IdGenerator, DAOs, [LocationGateway],
 * [ProcessingScheduler]).
 *
 * `detectorVersion`/`locationProfileVersion` are stamped as `DetectorVersion(0)`/
 * `LocationProfileVersion(0)` — explicit placeholders. No real detector or
 * location-sampling profile exists yet (`DET-001`/`TRK-002`); a manual Start
 * doesn't need one (F0.3 §6: "Manual Start bypasses candidate validation"),
 * but the columns are non-null, so a value has to exist. Revisit once those
 * tasks establish real versioning.
 */
class TrackingSessionCoordinator @Inject constructor(
    private val database: MotoTripDatabase,
    private val tripCaptureDao: TripCaptureDao,
    private val diagnosticEventDao: DiagnosticEventDao,
    private val rawTrackPointDao: RawTrackPointDao,
    private val captureEventDao: CaptureEventDao,
    private val tripDao: TripDao,
    private val tripPartDao: TripPartDao,
    private val locationGateway: LocationGateway,
    private val processingScheduler: ProcessingScheduler,
    private val clock: Clock,
    private val idGenerator: IdGenerator
) {
    sealed interface StartResult {
        val captureId: String
        data class Started(override val captureId: String) : StartResult
        data class AlreadyActive(override val captureId: String) : StartResult
    }

    sealed interface FinishResult {
        val captureId: String
        val tripId: String
        data class Finished(override val captureId: String, override val tripId: String) : FinishResult
        data class AlreadyFinished(override val captureId: String, override val tripId: String) : FinishResult
    }

    /** AUTO-001: [runAutoDetection]'s own outcome, distinct from [StartResult]/[FinishResult]. */
    sealed interface AutoDetectionOutcome {
        /** Nothing was ever confirmed - the candidate opened (or never did) and closed with no Trip created. */
        data object CandidateAbandoned : AutoDetectionOutcome
        data class TripCompleted(val captureId: String, val tripId: String) : AutoDetectionOutcome
    }

    /**
     * ADR-020/REL-INV-001: idempotent by construction — delegates the actual
     * check-then-insert to [TripCaptureDao.startCaptureIfNoneActive], which
     * already runs it in one transaction (see that DAO for why a raw DB
     * constraint isn't used instead).
     */
    suspend fun startManualCapture(): StartResult = startCapture(StartSource.MANUAL)

    /**
     * AUTO-001: the same transactional start path as [startManualCapture],
     * stamped [StartSource.AUTO] - used only by [runAutoDetection] once
     * `CandidateStartEngine` actually confirms.
     */
    suspend fun startAutoCapture(): StartResult = startCapture(StartSource.AUTO)

    private suspend fun startCapture(source: StartSource): StartResult {
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
            startSource = source,
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

        logStartCommand(source, result)
        return result
    }

    /** Used by the service on sticky restart / `Intent == null` recovery. */
    suspend fun findActiveCapture(): TripCaptureEntity? = tripCaptureDao.findByStatus(CaptureStatus.ACTIVE)

    /**
     * F0.10 §15.1's Finish transaction, minus the steps the caller owns
     * (stopping the location stream and the foreground service itself —
     * ADR-004 keeps the Service as the Android-lifecycle owner, this stays
     * plain logic). Everything that mutates state runs inside one
     * [MotoTripDatabase.withTransaction] — REL-INV-008 (atomic compound
     * operations) — so a failure partway (e.g. the Trip/TripPart insert)
     * rolls back the whole thing, including the capture's own COMPLETED
     * transition (F0.10 §15.2: "si falla antes de commit, la captura no se
     * considera completada").
     *
     * Idempotent per REL-INV-007 ("Finish sobre una captura ya cerrada = no
     * crea otro Trip"): if [captureId] is no longer ACTIVE, this looks up
     * the Trip that a prior successful Finish already created via
     * [TripPartDao.findByCaptureId] and returns [FinishResult.AlreadyFinished]
     * without writing anything — safe to call from a retried/duplicate
     * Finish command (F0.10 §15.3).
     *
     * F0.10 §15.1 also says to "cerrar pausa abierta si aplica" before
     * completing the capture. No producer of `ManualPauseIntervalEntity`
     * exists yet (`TRK-003`), so no pause can structurally be open right
     * now — nothing to close. `TRK-003` must extend this transaction with
     * that step when it lands, not silently rely on this comment.
     *
     * "marcar processing PENDING" (F0.10 §15.1 step 4) is satisfied by
     * [ProcessingScheduler.enqueueTripProcessing] itself rather than a new
     * persisted status column: WorkManager already persists its own
     * enqueued-work state across process death/reboot (ADR-010's own
     * rationale for using it at all), and F0.7's Trip/TripStatistics field
     * lists don't define such a column — inventing one ahead of PRC-001
     * actually needing it would be exactly the kind of unrequested schema
     * this project avoids.
     */
    suspend fun finishCapture(captureId: String, endSource: EndSource = EndSource.MANUAL): FinishResult {
        val result = database.withTransaction {
            val capture = requireNotNull(tripCaptureDao.findById(captureId)) {
                "finishCapture called with an unknown captureId: $captureId"
            }

            if (capture.status != CaptureStatus.ACTIVE) {
                val existingPart = requireNotNull(tripPartDao.findByCaptureId(captureId)) {
                    "capture $captureId is ${capture.status} but has no TripPart - inconsistent Finish state"
                }
                return@withTransaction FinishResult.AlreadyFinished(captureId, existingPart.tripId)
            }

            val wallNow = clock.wallClockMillis()
            val elapsedNow = clock.elapsedRealtimeNanos()

            val nextEventIndex = (captureEventDao.maxEventIndex(captureId) ?: -1) + 1
            captureEventDao.insert(
                CaptureEventEntity(
                    captureId = captureId,
                    eventIndex = nextEventIndex,
                    timestamp = wallNow,
                    elapsedRealtimeNanos = elapsedNow,
                    eventType = "CAPTURE_FINISHED",
                    stateFrom = CaptureStatus.ACTIVE.name,
                    stateTo = CaptureStatus.COMPLETED.name,
                    reasonCode = null,
                    source = "tracking-service",
                    metadata = null
                )
            )

            tripCaptureDao.completeActiveCapture(
                id = captureId,
                endedAt = wallNow,
                endElapsedRealtimeNanos = elapsedNow,
                endSource = endSource,
                updatedAt = wallNow
            )

            val tripId = idGenerator.newId()
            tripDao.insert(
                TripEntity(
                    id = tripId,
                    status = TripStatus.COMPLETED,
                    name = null,
                    isFavorite = false,
                    motorcycleId = null,
                    routeId = null,
                    notes = null,
                    createdAt = wallNow,
                    updatedAt = wallNow,
                    deletedAt = null
                )
            )

            val lastSequenceNumber = rawTrackPointDao.maxSequenceNumber(captureId)
            tripPartDao.insert(
                TripPartEntity(
                    id = idGenerator.newId(),
                    tripId = tripId,
                    captureId = captureId,
                    orderIndex = 0,
                    startElapsedRealtimeNanos = capture.startElapsedRealtimeNanos,
                    endElapsedRealtimeNanos = elapsedNow,
                    startSequenceNumber = if (lastSequenceNumber == null) null else 0L,
                    endSequenceNumber = lastSequenceNumber
                )
            )

            FinishResult.Finished(captureId, tripId)
        }

        logFinishCommand(endSource, result)
        if (result is FinishResult.Finished) {
            processingScheduler.enqueueTripProcessing(result.tripId, result.captureId)
        }
        return result
    }

    /**
     * AUTO-001: `DET-002`/`DET-003`'s engines wired to a real Start/Finish.
     * The caller (`ActivityTransitionReceiver`/`TrackingForegroundService`)
     * already checked `CapabilityResolver` before ever invoking this - this
     * method has no mode awareness of its own, matching
     * `CandidateStartEngine`/`CandidateStopEngine`'s own "caller decides when
     * to route events here" posture.
     *
     * One continuous coroutine spans BOTH candidate-start validation and,
     * once confirmed, candidate-stop monitoring, deliberately: the same live
     * [locationGateway] subscription that validates the candidate keeps
     * flowing straight into Raw Track persistence with no re-subscribe gap.
     * DP-008 ("high-detail location tracking SHOULD be activated only when a
     * Trip is being validated OR recorded") is this task's justification for
     * reusing the one TRACKING-grade profile for both phases rather than
     * building a separate, lighter "burst" profile - a documented v1
     * simplification, not an oversight.
     *
     * The merged ticker closes a real gap `CandidateStopEngineTest` names
     * directly: grace-period/candidate-window expiry must stay reachable
     * during a total, sustained GPS loss (tunnel, parking garage) where no
     * location sample would otherwise arrive to trigger the check.
     *
     * Two known, deliberately accepted v1 gaps, not silently dropped:
     * - F0.3 §6 requirement 5 ("SHOULD not lose a large initial route
     *   section"): samples evaluated during candidate validation are never
     *   retroactively persisted once confirmed - only samples from the
     *   moment of confirmation onward are. Buffering/backdating them would
     *   need real sample-retention/replay machinery this task doesn't build.
     * - If the process dies mid-flight, `TrackingForegroundService`'s
     *   existing sticky-restart path (`rehydrateOrStop`) resumes plain Raw
     *   Track recording for an already-confirmed AUTO capture (no data loss)
     *   but does not resume automatic candidate-stop monitoring for it - the
     *   trip remains finishable manually. Re-entering just the stop-
     *   monitoring phase after a restart is deferred past this task.
     */
    suspend fun runAutoDetection(
        activityEvents: Flow<ActivityTransitionSample>,
        onCaptureStarted: suspend (captureId: String) -> Unit = {}
    ): AutoDetectionOutcome {
        val activityFlow: Flow<DetectionEvent> = activityEvents.map { DetectionEvent.Activity(it) }
        val locationFlow: Flow<DetectionEvent> = locationGateway.locationUpdates().map { DetectionEvent.Location(it) }
        val tickerFlow: Flow<DetectionEvent> = flow {
            while (true) {
                delay(TICKER_INTERVAL_MS)
                emit(DetectionEvent.TimeTick(clock.elapsedRealtimeNanos()))
            }
        }

        val startEngine = CandidateStartEngine()
        var stopEngine: CandidateStopEngine? = null
        var activeCaptureId: String? = null
        var nextSequenceNumber = 0L

        try {
            merge(activityFlow, locationFlow, tickerFlow).collect { event ->
                val captureId = activeCaptureId
                if (captureId == null) {
                    when (val decision = startEngine.accept(event)) {
                        is CandidateStartDecision.Confirmed -> {
                            when (val started = startAutoCapture()) {
                                is StartResult.Started -> {
                                    activeCaptureId = started.captureId
                                    nextSequenceNumber = (rawTrackPointDao.maxSequenceNumber(started.captureId) ?: -1) + 1
                                    stopEngine = CandidateStopEngine()
                                    onCaptureStarted(started.captureId)
                                }
                                // Another Start (most likely manual - DP-005 "manual
                                // intent wins") raced in first; nothing to do here.
                                is StartResult.AlreadyActive -> throw StopAutoDetection(AutoDetectionOutcome.CandidateAbandoned)
                            }
                        }
                        CandidateStartDecision.Abandoned -> throw StopAutoDetection(AutoDetectionOutcome.CandidateAbandoned)
                        CandidateStartDecision.NoChange, CandidateStartDecision.CandidateOpened -> Unit
                    }
                } else {
                    if (event is DetectionEvent.Location) {
                        try {
                            rawTrackPointDao.insert(event.sample.toRawTrackPointEntity(captureId, nextSequenceNumber))
                            nextSequenceNumber++
                        } catch (error: Exception) {
                            logRawTrackPointPersistenceFailure(captureId, error)
                        }
                    }
                    when (val decision = checkNotNull(stopEngine).accept(event)) {
                        is CandidateStopDecision.Confirmed -> {
                            val result = finishCapture(captureId, EndSource.AUTO)
                            throw StopAutoDetection(AutoDetectionOutcome.TripCompleted(captureId, result.tripId))
                        }
                        CandidateStopDecision.NoChange, CandidateStopDecision.CandidateOpened, CandidateStopDecision.Abandoned -> Unit
                    }
                }
            }
            return AutoDetectionOutcome.CandidateAbandoned
        } catch (stop: StopAutoDetection) {
            return stop.outcome
        }
    }

    /** A structured way to unwind out of [runAutoDetection]'s `collect` early with a known result. */
    private class StopAutoDetection(val outcome: AutoDetectionOutcome) : CancellationException()

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

    /**
     * `DETECTOR` for an auto-triggered command, `USER_COMMAND` for a manual
     * one - AUTO-001 is the first caller where [endSource] can actually be
     * [EndSource.AUTO], so this category split didn't need to exist before.
     */
    private suspend fun logFinishCommand(endSource: EndSource, result: FinishResult) {
        val (reasonCode, stateBefore) = when (result) {
            is FinishResult.Finished -> "NEW_TRIP" to CaptureStatus.ACTIVE.name
            is FinishResult.AlreadyFinished -> "ALREADY_FINISHED" to CaptureStatus.COMPLETED.name
        }
        diagnosticEventDao.insert(
            DiagnosticEventEntity(
                eventId = idGenerator.newId(),
                occurredAt = clock.wallClockMillis(),
                elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                category = if (endSource == EndSource.AUTO) DiagnosticCategory.DETECTOR else DiagnosticCategory.USER_COMMAND,
                eventType = "FINISH",
                severity = DiagnosticSeverity.INFO,
                source = "tracking-service",
                captureId = result.captureId,
                tripId = result.tripId,
                correlationId = null,
                stateBefore = stateBefore,
                stateAfter = CaptureStatus.COMPLETED.name,
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

    /** Same [DiagnosticCategory.DETECTOR]/[DiagnosticCategory.USER_COMMAND] split as [logFinishCommand], keyed off [source] instead. */
    private suspend fun logStartCommand(source: StartSource, result: StartResult) {
        val (captureId, reasonCode) = when (result) {
            is StartResult.Started -> result.captureId to "NEW_CAPTURE"
            is StartResult.AlreadyActive -> result.captureId to "ALREADY_ACTIVE"
        }
        diagnosticEventDao.insert(
            DiagnosticEventEntity(
                eventId = idGenerator.newId(),
                occurredAt = clock.wallClockMillis(),
                elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                category = if (source == StartSource.AUTO) DiagnosticCategory.DETECTOR else DiagnosticCategory.USER_COMMAND,
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

    companion object {
        /**
         * AUTO-001: well under both engines' shortest threshold
         * (`CandidateStartProfile`'s 15s confirmation window) - just frequent
         * enough that a total, sustained GPS loss still lets a stale
         * candidate/grace-period expire in bounded time instead of hanging
         * until a location fix eventually returns.
         */
        private const val TICKER_INTERVAL_MS = 15_000L
    }
}
