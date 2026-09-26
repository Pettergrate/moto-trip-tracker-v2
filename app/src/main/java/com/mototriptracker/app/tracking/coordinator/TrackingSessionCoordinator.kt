package com.mototriptracker.app.tracking.coordinator

import androidx.room.withTransaction
import com.mototriptracker.app.BuildConfig
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.dao.CaptureEventDao
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.dao.ManualPauseIntervalDao
import com.mototriptracker.app.core.database.dao.RawTrackPointDao
import com.mototriptracker.app.core.database.dao.TripCaptureDao
import com.mototriptracker.app.core.database.dao.TripDao
import com.mototriptracker.app.core.database.dao.TripPartDao
import com.mototriptracker.app.core.database.entity.CaptureEventEntity
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.database.entity.ManualPauseIntervalEntity
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
import com.mototriptracker.app.domain.detection.ForgottenFinishDecision
import com.mototriptracker.app.domain.detection.ForgottenFinishEngine
import com.mototriptracker.app.domain.detection.ForgottenPauseDecision
import com.mototriptracker.app.domain.detection.ForgottenPauseEngine
import com.mototriptracker.app.domain.liveDistanceMeters
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
 * capture into a logical Trip (TRK-004), pause/resume it ([pauseCapture]/
 * [resumeCapture], TRK-003), or run a whole automatic candidate-start-to-
 * candidate-stop session end to end ([runAutoDetection], AUTO-001), or warn
 * about sustained movement while paused without ever auto-resuming
 * ([ForgottenPauseWatch], DET-005) — F0.8 §6: "TrackingSessionCoordinator coordina detector,
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
    private val manualPauseIntervalDao: ManualPauseIntervalDao,
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
     * NOT-001: the live figures the notification's TRACKING/MANUAL_PAUSED
     * text needs (F0.9 §7). [distanceMeters] reuses [liveDistanceMeters] -
     * the same non-authoritative, best-effort figure Home/Active Trip
     * already show, not PRC-002's post-Finish authoritative one.
     */
    data class TrackingSnapshot(val distanceMeters: Double, val elapsedMs: Long, val isPaused: Boolean)

    /**
     * NOT-001: a single on-demand read rather than a live-subscribed Flow -
     * the notification only needs "the current value right now" at a few
     * specific moments (Start, Pause, Resume, rehydrate, and a periodic
     * refresh the Service owns), not a continuous stream. `null` means the
     * capture is already gone (a stale/duplicate command racing a Finish
     * elsewhere) - the Service's own job to decide there's nothing left to
     * show, not this method's.
     */
    suspend fun currentTrackingSnapshot(captureId: String): TrackingSnapshot? {
        val capture = tripCaptureDao.findById(captureId) ?: return null
        val points = rawTrackPointDao.findAllByCapture(captureId)
        val isPaused = manualPauseIntervalDao.findOpenByCapture(captureId) != null
        return TrackingSnapshot(
            distanceMeters = liveDistanceMeters(points),
            elapsedMs = (clock.elapsedRealtimeNanos() - capture.startElapsedRealtimeNanos) / 1_000_000,
            isPaused = isPaused
        )
    }

    /** TRK-003: [pauseCapture]'s outcome. */
    sealed interface PauseResult {
        data class Paused(val captureId: String, val pauseId: String) : PauseResult
        data class AlreadyPaused(val captureId: String, val pauseId: String) : PauseResult
        data object NoActiveCapture : PauseResult
    }

    /** TRK-003: [resumeCapture]'s outcome. */
    sealed interface ResumeResult {
        data class Resumed(val captureId: String) : ResumeResult
        data class AlreadyResumed(val captureId: String) : ResumeResult
        data object NoActiveCapture : ResumeResult
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

    /** REC-001/F0.10 §7.1's "reanudar la misma captura" vs §10's "no asumir que el viaje continuó durante el reboot". */
    sealed interface RecoveryOutcome {
        data class Resumed(val captureId: String) : RecoveryOutcome
        data class AbortedAfterReboot(val captureId: String) : RecoveryOutcome
        data object NoActiveCapture : RecoveryOutcome
    }

    /**
     * REC-001. Called once by the Service whenever it (re)starts with no
     * fresh command intent (a sticky restart, or `Intent == null`) - the
     * only place F0.10 §7/§10's checklist actually applies, since a direct
     * Start/Pause/Resume/Finish command already knows exactly what it means
     * to do.
     *
     * F0.10 §10.1's discontinuity rule: `elapsedRealtimeNanos` is monotonic
     * only *within* a boot session - it resets close to zero after a real
     * reboot. If the capture's own recorded start is now *greater* than the
     * current value, the clock domain changed underneath it, which is only
     * possible if a reboot happened since Start - "same boot" (§7.1) no
     * longer holds, and §10.2's stricter rule takes over instead of §7.1's
     * plain resume.
     */
    suspend fun recoverActiveCaptureIfAny(): RecoveryOutcome {
        val active = tripCaptureDao.findByStatus(CaptureStatus.ACTIVE) ?: return RecoveryOutcome.NoActiveCapture

        return if (clock.elapsedRealtimeNanos() < active.startElapsedRealtimeNanos) {
            abortCaptureAfterReboot(active)
            RecoveryOutcome.AbortedAfterReboot(active.id)
        } else {
            logProcessRecovered(active.id)
            RecoveryOutcome.Resumed(active.id)
        }
    }

    /**
     * F0.10 §10.2's baseline: preserve every RawTrackPoint (nothing here
     * deletes or rewrites one), close the capture using the *last persisted
     * evidence* rather than an invented "now" (rule 4 - a reboot could have
     * lasted seconds or hours; wall-clock "now" says nothing true about when
     * riding actually stopped), and never resume it - "the next valid
     * movement creates a new capture" (rule 6) is a future Start, not
     * something this method does itself. `EndSource.RECOVERY` distinguishes
     * this from a hypothetical future user-initiated abort action
     * (`EndSource.ABORTED`, unused today - no such action exists yet).
     */
    private suspend fun abortCaptureAfterReboot(capture: TripCaptureEntity): String? {
        val tripId = sealInterruptedCapture(capture)
        logCaptureAbortedAfterReboot(capture.id)
        return tripId
    }

    /**
     * REC-003. What [recoverActiveCaptureIfAny]'s reboot branch and
     * [sealActiveCaptureAfterBoot] share: closes an ACTIVE capture as
     * `ABORTED`/`RECOVERY` using only its *last persisted evidence*, then -
     * F0.10 §22 ("un registro incompleto es preferible a un registro
     * inventado o desaparecido"; §10.2 rule 5, "hacer visible que el registro
     * quedó parcial") - gives it a visible partial Trip when there is a route
     * worth showing, so the interrupted ride doesn't silently vanish into an
     * unreachable `ABORTED` row. One transaction (REL-INV-008); processing is
     * enqueued only after it commits (ADR-015).
     *
     * A capture with fewer than [MIN_POINTS_FOR_PARTIAL_TRIP] raw points has
     * no route to show, so it is sealed without a Trip (its evidence still
     * stays). An open manual pause must be closed here too: a Trip's metrics
     * refuse an open one (`TripMetricsCalculator`), and closing it at the last
     * evidence - never at an invented "now" - is the same honesty rule.
     *
     * @return the partial Trip's id, or `null` if none was created.
     */
    private suspend fun sealInterruptedCapture(capture: TripCaptureEntity): String? {
        var partialTripId: String? = null
        database.withTransaction {
            val lastPoint = rawTrackPointDao.findAllByCapture(capture.id).maxByOrNull { it.sequenceNumber }
            val pointCount = rawTrackPointDao.countByCapture(capture.id)
            val endedAt = lastPoint?.capturedAt ?: capture.startedAt
            val endElapsedRealtimeNanos = lastPoint?.elapsedRealtimeNanos ?: capture.startElapsedRealtimeNanos

            manualPauseIntervalDao.findOpenByCapture(capture.id)?.let { openPause ->
                // A pause opened after the last evidence would otherwise be "closed" before it started.
                val closeAt = maxOf(endElapsedRealtimeNanos, openPause.startElapsedRealtimeNanos)
                manualPauseIntervalDao.closePause(
                    id = openPause.id,
                    endedAt = maxOf(endedAt, openPause.startedAt),
                    endElapsedRealtimeNanos = closeAt,
                    endReason = "CAPTURE_INTERRUPTED"
                )
            }

            tripCaptureDao.completeActiveCapture(
                id = capture.id,
                endedAt = endedAt,
                endElapsedRealtimeNanos = endElapsedRealtimeNanos,
                endSource = EndSource.RECOVERY,
                updatedAt = clock.wallClockMillis(),
                newStatus = CaptureStatus.ABORTED
            )

            if (lastPoint != null && pointCount >= MIN_POINTS_FOR_PARTIAL_TRIP) {
                val tripId = idGenerator.newId()
                val now = clock.wallClockMillis()
                tripDao.insert(
                    TripEntity(
                        id = tripId,
                        status = TripStatus.COMPLETED,
                        name = null,
                        isFavorite = false,
                        motorcycleId = null,
                        routeId = null,
                        notes = null,
                        // The same "when the ride ended" every Trip's createdAt means: the last evidence.
                        createdAt = endedAt,
                        updatedAt = now,
                        deletedAt = null
                    )
                )
                tripPartDao.insert(
                    TripPartEntity(
                        id = idGenerator.newId(),
                        tripId = tripId,
                        captureId = capture.id,
                        orderIndex = 0,
                        startElapsedRealtimeNanos = capture.startElapsedRealtimeNanos,
                        endElapsedRealtimeNanos = endElapsedRealtimeNanos,
                        startSequenceNumber = 0L,
                        endSequenceNumber = lastPoint.sequenceNumber
                    )
                )
                partialTripId = tripId
            }
        }
        partialTripId?.let { processingScheduler.enqueueTripProcessing(it, capture.id) }
        return partialTripId
    }

    /** REC-003's answer to "did anything need sealing, and did it get a visible Trip". */
    sealed interface ReconcileOutcome {
        data object NothingToReconcile : ReconcileOutcome
        data class SealedAfterReboot(val captureId: String, val partialTripId: String?) : ReconcileOutcome
    }

    /**
     * REC-003/F0.10 §10.3 + §25: `BOOT_COMPLETED` is the *certain* signal that
     * a reboot happened, so any capture still `ACTIVE` at that moment belongs to
     * the previous boot and must be sealed - without this it stays `ACTIVE`
     * forever after a reboot, the "Trip in progress" card never goes away and
     * ADR-020's single-active rule blocks every future Start. Deliberately does
     * **not** use [recoverActiveCaptureIfAny]'s `elapsedRealtime` comparison
     * here: a capture started in the first seconds of the previous boot can
     * have a *smaller* start value than the fresh boot's current one, and that
     * heuristic would then wrongly call it "same boot".
     *
     * Idempotent: once sealed, nothing is `ACTIVE` any more.
     */
    suspend fun sealActiveCaptureAfterBoot(): ReconcileOutcome {
        val active = tripCaptureDao.findByStatus(CaptureStatus.ACTIVE) ?: return ReconcileOutcome.NothingToReconcile
        return ReconcileOutcome.SealedAfterReboot(active.id, abortCaptureAfterReboot(active))
    }

    /**
     * REC-003/F0.10 §25: the same reconciliation for any *other* process entry
     * (app launch, worker) that finds the capture left over from a previous
     * boot, using only the definitive §10.1 discontinuity test - not a wall-clock
     * guess, which a manual clock change (§19.3) could fool into aborting a live
     * trip. Same-boot captures are left alone: process death within one boot is
     * the sticky service's job to resume ([recoverActiveCaptureIfAny]).
     */
    suspend fun reconcileActiveCaptureAfterReboot(): ReconcileOutcome {
        val active = tripCaptureDao.findByStatus(CaptureStatus.ACTIVE) ?: return ReconcileOutcome.NothingToReconcile
        if (clock.elapsedRealtimeNanos() >= active.startElapsedRealtimeNanos) return ReconcileOutcome.NothingToReconcile
        return ReconcileOutcome.SealedAfterReboot(active.id, abortCaptureAfterReboot(active))
    }

    /**
     * REC-002/F0.10 §7.3: a sticky restart found the capture ACTIVE but the
     * service can't legitimately resume it (location permission is gone).
     * Declares it degraded - a persisted diagnostic event, once per capture and
     * reason (§25 step 8) - instead of pretending the recording is healthy, and
     * deliberately leaves the capture `ACTIVE` with all its evidence intact: the
     * user may restore the permission or Finish it, and sealing an orphaned
     * capture is the reconciliation policy's job (REC-003/004), not this
     * method's.
     *
     * @return the affected capture's id, or `null` if none is ACTIVE.
     */
    suspend fun markRecoveryDegraded(reasonCode: String): String? {
        val active = tripCaptureDao.findByStatus(CaptureStatus.ACTIVE) ?: return null
        if (diagnosticEventDao.countByCaptureAndType(active.id, EVENT_RECOVERY_DEGRADED) == 0) {
            diagnosticEventDao.insert(
                DiagnosticEventEntity(
                    eventId = idGenerator.newId(),
                    occurredAt = clock.wallClockMillis(),
                    elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                    category = DiagnosticCategory.CAPABILITY_PERMISSIONS,
                    eventType = EVENT_RECOVERY_DEGRADED,
                    severity = DiagnosticSeverity.WARN,
                    source = "tracking-service",
                    captureId = active.id,
                    tripId = null,
                    correlationId = null,
                    stateBefore = "ACTIVE",
                    stateAfter = "ACTIVE_NOT_RECORDING",
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
        return active.id
    }

    /** F0.10 §7.1 item 6: "registrar PROCESS_RECOVERED/evento equivalente." */
    private suspend fun logProcessRecovered(captureId: String) {
        diagnosticEventDao.insert(
            DiagnosticEventEntity(
                eventId = idGenerator.newId(),
                occurredAt = clock.wallClockMillis(),
                elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                category = DiagnosticCategory.RECOVERY_SYSTEM,
                eventType = "PROCESS_RECOVERED",
                severity = DiagnosticSeverity.INFO,
                source = "tracking-service",
                captureId = captureId,
                tripId = null,
                correlationId = null,
                stateBefore = null,
                stateAfter = null,
                reasonCode = null,
                metadata = emptyMap(),
                appVersion = BuildConfig.VERSION_NAME,
                schemaVersion = 1,
                detectorVersion = DetectorVersion(0),
                locationProfileVersion = LocationProfileVersion(0),
                processingVersion = ProcessingVersion(0)
            )
        )
    }

    /** F0.10 §10.2 item 2: "registrar el motivo de recuperación cuando sea posible." */
    private suspend fun logCaptureAbortedAfterReboot(captureId: String) {
        diagnosticEventDao.insert(
            DiagnosticEventEntity(
                eventId = idGenerator.newId(),
                occurredAt = clock.wallClockMillis(),
                elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                category = DiagnosticCategory.RECOVERY_SYSTEM,
                eventType = "CAPTURE_ABORTED_AFTER_REBOOT",
                severity = DiagnosticSeverity.WARN,
                source = "tracking-service",
                captureId = captureId,
                tripId = null,
                correlationId = null,
                stateBefore = "ACTIVE",
                stateAfter = "ABORTED",
                reasonCode = "ELAPSED_REALTIME_DISCONTINUITY",
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
     * F0.3 §8: "Pause is available from the active-trip UI and notification
     * ... the Trip remains logically active ... the pause start timestamp is
     * persisted." No `CaptureStatus` change - an open [ManualPauseIntervalEntity]
     * row (`endedAt IS NULL`) is what "paused" means, exactly as F0.7 §6.4
     * already documented on that entity, so [findActiveCapture] and
     * everything built on `CaptureStatus.ACTIVE` keeps working unchanged.
     *
     * Wrapped in a transaction: two concurrent Pause commands both seeing
     * "no open pause" and both inserting would otherwise leave two open
     * pause rows for the same capture (REL-INV-008), unlike Resume's
     * idempotent `UPDATE`, which two concurrent callers can safely repeat.
     */
    suspend fun pauseCapture(): PauseResult {
        val result = database.withTransaction {
            val active = tripCaptureDao.findByStatus(CaptureStatus.ACTIVE) ?: return@withTransaction PauseResult.NoActiveCapture
            val existingOpenPause = manualPauseIntervalDao.findOpenByCapture(active.id)
            if (existingOpenPause != null) {
                return@withTransaction PauseResult.AlreadyPaused(active.id, existingOpenPause.id)
            }

            val wallNow = clock.wallClockMillis()
            val elapsedNow = clock.elapsedRealtimeNanos()
            val pauseId = idGenerator.newId()
            manualPauseIntervalDao.insert(
                ManualPauseIntervalEntity(
                    id = pauseId,
                    captureId = active.id,
                    startedAt = wallNow,
                    endedAt = null,
                    startElapsedRealtimeNanos = elapsedNow,
                    endElapsedRealtimeNanos = null,
                    startReason = "USER_COMMAND",
                    endReason = null
                )
            )
            PauseResult.Paused(active.id, pauseId)
        }

        logPauseCommand(result)
        return result
    }

    /**
     * Closes the open pause interval, if any - [recordLocationUpdates] and
     * [runAutoDetection] both resume normal behavior on their own the next
     * time they check, since neither holds any pause-specific state of its
     * own; they just stop seeing an open pause row.
     */
    suspend fun resumeCapture(): ResumeResult {
        val active = tripCaptureDao.findByStatus(CaptureStatus.ACTIVE)
        if (active == null) {
            logResumeCommand(null, ResumeResult.NoActiveCapture)
            return ResumeResult.NoActiveCapture
        }

        val openPause = manualPauseIntervalDao.findOpenByCapture(active.id)
        val result = if (openPause == null) {
            ResumeResult.AlreadyResumed(active.id)
        } else {
            manualPauseIntervalDao.closePause(
                id = openPause.id,
                endedAt = clock.wallClockMillis(),
                endElapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                endReason = "USER_COMMAND"
            )
            ResumeResult.Resumed(active.id)
        }

        logResumeCommand(active.id, result)
        return result
    }

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
     * F0.10 §15.1's "cerrar pausa abierta si aplica" (TRK-003): if an open
     * pause exists for this capture, it's closed inside this same
     * transaction with `endReason = "CAPTURE_FINISHED"` — F0.3 §8's own
     * "Finish is available while paused" requirement, satisfied without a
     * separate Resume call the user never made.
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

            manualPauseIntervalDao.findOpenByCapture(captureId)?.let { openPause ->
                manualPauseIntervalDao.closePause(
                    id = openPause.id,
                    endedAt = wallNow,
                    endElapsedRealtimeNanos = elapsedNow,
                    endReason = "CAPTURE_FINISHED"
                )
            }

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
     *
     * TRK-003: once a real capture is active, every event is also checked
     * against [ManualPauseIntervalDao.findOpenByCapture] before persistence/
     * stop-evaluation - see the inline comment at that check for why.
     *
     * DET-005: a location sample arriving while paused is routed to a
     * [ForgottenPauseWatch] instead of being silently ignored - the same
     * detection [recordLocationUpdates] runs for manually-started captures,
     * so an auto-started capture that gets manually paused is covered too.
     */
    suspend fun runAutoDetection(
        activityEvents: Flow<ActivityTransitionSample>,
        onCaptureStarted: suspend (captureId: String) -> Unit = {},
        onForgottenPauseWarning: suspend () -> Unit = {}
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
        val forgottenPauseWatch = ForgottenPauseWatch()

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
                    val openPause = manualPauseIntervalDao.findOpenByCapture(captureId)
                    if (openPause == null) {
                        forgottenPauseWatch.onResumed()
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
                    } else if (event is DetectionEvent.Location) {
                        // TRK-003/F0.3 §8: "automatic stop detection should not
                        // silently close a manually paused Trip" - the stop
                        // engine never sees this event, freezing its own
                        // grace-period clock instead of letting it tick during
                        // a pause the user explicitly asked for (DP-005).
                        // DET-005 still watches it for forgotten-pause evidence.
                        forgottenPauseWatch.onPausedSample(openPause.id, event.sample) { decision ->
                            logForgottenPauseWarning(captureId, openPause.id, decision)
                            onForgottenPauseWarning()
                        }
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
     * DET-005: shared by both [recordLocationUpdates] and [runAutoDetection]
     * so neither has to duplicate "notice a new pause, run a fresh
     * [ForgottenPauseEngine] for it, forget it again once resumed." A fresh
     * engine per pause, exactly like `runAutoDetection` already creates a
     * fresh [CandidateStopEngine] per capture - this class has no way to
     * know a pause resumed and a new one later opened are the same episode,
     * nor should it need to.
     */
    private class ForgottenPauseWatch {
        private var engine: ForgottenPauseEngine? = null
        private var trackedPauseId: String? = null

        suspend fun onPausedSample(
            pauseId: String,
            sample: LocationSample,
            onWarning: suspend (ForgottenPauseDecision.WarningIssued) -> Unit
        ) {
            if (pauseId != trackedPauseId) {
                trackedPauseId = pauseId
                engine = ForgottenPauseEngine()
            }
            when (val decision = checkNotNull(engine).accept(sample)) {
                is ForgottenPauseDecision.WarningIssued -> onWarning(decision)
                ForgottenPauseDecision.NoChange -> Unit
            }
        }

        fun onResumed() {
            trackedPauseId = null
            engine = null
        }
    }

    /**
     * DET-007: [ForgottenFinishEngine] only ever needs one instance for the
     * whole not-paused lifetime of a manual capture (unlike
     * [ForgottenPauseWatch], which needs a fresh engine per bounded pause
     * episode) - it already resets its own "stationary episode" internally
     * on real movement. The one thing an engine instance can't know on its
     * own is that a pause happened: without [onPaused] discarding it, the
     * first sample after a long Resume would see an anchor whose age spans
     * the entire pause and could immediately misfire as "stationary too
     * long," when the rider was just deliberately, briefly stopped.
     */
    private class ForgottenFinishWatch {
        private var engine = ForgottenFinishEngine()

        suspend fun onSample(sample: LocationSample, onWarning: suspend (ForgottenFinishDecision.WarningIssued) -> Unit) {
            when (val decision = engine.accept(sample)) {
                is ForgottenFinishDecision.WarningIssued -> onWarning(decision)
                ForgottenFinishDecision.NoChange -> Unit
            }
        }

        fun onPaused() {
            engine = ForgottenFinishEngine()
        }
    }

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
     *
     * TRK-003/F0.3 §8: a sample arriving while an open pause exists for
     * [captureId] is neither persisted nor advances `sequenceNumber` -
     * "detailed movement during the pause is excluded from normal Trip
     * distance/route by default" and "missed route geometry during manual
     * pause should be treated as genuinely missing rather than
     * reconstructed." The collector itself keeps running (not cancelled) -
     * DET-005's [ForgottenPauseEngine] watches this same live stream while
     * paused instead of needing a separate subscription.
     *
     * DET-005/F0.3 §8's "forgotten-pause scenario": a paused sample is fed to
     * [ForgottenPauseWatch] instead of being dropped outright; [onForgottenPauseWarning]
     * lets the caller (`TrackingForegroundService`) surface a real reminder -
     * this method itself never resumes anything on its own (DP-005: manual
     * ownership stays authoritative regardless of what gets detected here).
     */
    suspend fun recordLocationUpdates(
        captureId: String,
        onForgottenPauseWarning: suspend () -> Unit = {},
        onForgottenFinishWarning: suspend () -> Unit = {}
    ) {
        var nextSequenceNumber = (rawTrackPointDao.maxSequenceNumber(captureId) ?: -1) + 1
        val forgottenPauseWatch = ForgottenPauseWatch()
        // DET-007: only relevant here, not `runAutoDetection` - an
        // AUTO-started capture already gets a real stop-and-finish from
        // `CandidateStopEngine`. A manual capture has no such safety net,
        // which is the actual gap this closes.
        val forgottenFinishWatch = ForgottenFinishWatch()
        locationGateway.locationUpdates().collect { sample ->
            val openPause = manualPauseIntervalDao.findOpenByCapture(captureId)
            if (openPause == null) {
                forgottenPauseWatch.onResumed()
                try {
                    rawTrackPointDao.insert(sample.toRawTrackPointEntity(captureId, nextSequenceNumber))
                    nextSequenceNumber++
                } catch (error: Exception) {
                    logRawTrackPointPersistenceFailure(captureId, error)
                }
                forgottenFinishWatch.onSample(sample) { decision ->
                    logForgottenFinishWarning(captureId, decision)
                    onForgottenFinishWarning()
                }
            } else {
                forgottenFinishWatch.onPaused()
                forgottenPauseWatch.onPausedSample(openPause.id, sample) { decision ->
                    logForgottenPauseWarning(captureId, openPause.id, decision)
                    onForgottenPauseWarning()
                }
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

    /** Pause is always user-initiated (F0.3 §8) - no `DETECTOR`/`USER_COMMAND` split needed here, unlike Start/Finish. */
    private suspend fun logPauseCommand(result: PauseResult) {
        val (captureId, reasonCode) = when (result) {
            is PauseResult.Paused -> result.captureId to "NEW_PAUSE"
            is PauseResult.AlreadyPaused -> result.captureId to "ALREADY_PAUSED"
            PauseResult.NoActiveCapture -> null to "NO_ACTIVE_CAPTURE"
        }
        diagnosticEventDao.insert(
            DiagnosticEventEntity(
                eventId = idGenerator.newId(),
                occurredAt = clock.wallClockMillis(),
                elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                category = DiagnosticCategory.USER_COMMAND,
                eventType = "PAUSE",
                severity = DiagnosticSeverity.INFO,
                source = "tracking-service",
                captureId = captureId,
                tripId = null,
                correlationId = null,
                stateBefore = null,
                stateAfter = null,
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

    /**
     * DET-005: [correlationId] links this back to the specific pause episode
     * it warned about - useful for making sense of a diagnostic export later
     * without a foreign key entangling diagnostic and domain data (see
     * [DiagnosticEventEntity]'s own KDoc on why there isn't one).
     */
    private suspend fun logForgottenPauseWarning(captureId: String, pauseId: String, decision: ForgottenPauseDecision.WarningIssued) {
        diagnosticEventDao.insert(
            DiagnosticEventEntity(
                eventId = idGenerator.newId(),
                occurredAt = clock.wallClockMillis(),
                elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                category = DiagnosticCategory.DETECTOR,
                eventType = "FORGOTTEN_PAUSE_WARNING",
                severity = DiagnosticSeverity.WARN,
                source = "tracking-service",
                captureId = captureId,
                tripId = null,
                correlationId = pauseId,
                stateBefore = null,
                stateAfter = null,
                reasonCode = "SUSTAINED_MOVEMENT_WHILE_PAUSED",
                metadata = emptyMap(),
                appVersion = BuildConfig.VERSION_NAME,
                schemaVersion = 1,
                detectorVersion = DetectorVersion(0),
                locationProfileVersion = LocationProfileVersion(0),
                processingVersion = ProcessingVersion(0)
            )
        )
    }

    /** DET-007: mirrors [logForgottenPauseWarning] - `correlationId` has no pause to reference here, so it's left null. */
    private suspend fun logForgottenFinishWarning(captureId: String, decision: ForgottenFinishDecision.WarningIssued) {
        diagnosticEventDao.insert(
            DiagnosticEventEntity(
                eventId = idGenerator.newId(),
                occurredAt = clock.wallClockMillis(),
                elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                category = DiagnosticCategory.DETECTOR,
                eventType = "FORGOTTEN_FINISH_WARNING",
                severity = DiagnosticSeverity.WARN,
                source = "tracking-service",
                captureId = captureId,
                tripId = null,
                correlationId = null,
                stateBefore = null,
                stateAfter = null,
                reasonCode = "SUSTAINED_NON_MOVEMENT_WHILE_ACTIVE",
                metadata = emptyMap(),
                appVersion = BuildConfig.VERSION_NAME,
                schemaVersion = 1,
                detectorVersion = DetectorVersion(0),
                locationProfileVersion = LocationProfileVersion(0),
                processingVersion = ProcessingVersion(0)
            )
        )
    }

    private suspend fun logResumeCommand(captureId: String?, result: ResumeResult) {
        val reasonCode = when (result) {
            is ResumeResult.Resumed -> "RESUMED"
            is ResumeResult.AlreadyResumed -> "ALREADY_RESUMED"
            ResumeResult.NoActiveCapture -> "NO_ACTIVE_CAPTURE"
        }
        diagnosticEventDao.insert(
            DiagnosticEventEntity(
                eventId = idGenerator.newId(),
                occurredAt = clock.wallClockMillis(),
                elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                category = DiagnosticCategory.USER_COMMAND,
                eventType = "RESUME",
                severity = DiagnosticSeverity.INFO,
                source = "tracking-service",
                captureId = captureId,
                tripId = null,
                correlationId = null,
                stateBefore = null,
                stateAfter = null,
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

        /** REC-002: the [DiagnosticEventEntity.eventType] of a restart that couldn't legitimately resume an ACTIVE capture. */
        const val EVENT_RECOVERY_DEGRADED = "RECOVERY_DEGRADED"

        /** REC-003: below this a sealed capture has no route worth showing, so it gets no partial Trip (its evidence stays). */
        const val MIN_POINTS_FOR_PARTIAL_TRIP = 2
    }
}
