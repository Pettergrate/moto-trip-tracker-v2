package com.mototriptracker.app.diagnostics

import com.mototriptracker.app.BuildConfig
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.dao.RawTrackPointDao
import com.mototriptracker.app.core.database.dao.TripCaptureDao
import com.mototriptracker.app.core.database.dao.TripDao
import com.mototriptracker.app.core.database.dao.TripStatisticsDao
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.domain.capability.CapabilityResolver
import com.mototriptracker.app.tracking.capability.CapabilityInputsProvider
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import com.mototriptracker.app.tracking.persistence.PersistenceHealthBus
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import com.mototriptracker.app.tracking.recovery.ProcessExitRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * DIA-002/DIA-003: builds a [DiagnosticSnapshot] from what the app can actually observe - Room, the platform, the
 * in-memory persistence bus - and nothing else. Read-only: it writes nothing and caches nothing, so a snapshot can
 * never disagree with the stores for longer than one call.
 *
 * Anything it cannot know is `null`/absent in the result (ADR-016), never a plausible default.
 */
class DiagnosticSnapshotProvider @Inject constructor(
    private val database: MotoTripDatabase,
    private val tripCaptureDao: TripCaptureDao,
    private val rawTrackPointDao: RawTrackPointDao,
    private val diagnosticEventDao: DiagnosticEventDao,
    private val tripDao: TripDao,
    private val tripStatisticsDao: TripStatisticsDao,
    private val capabilityInputsProvider: CapabilityInputsProvider,
    private val system: SystemDiagnosticsInfo,
    private val processingWork: ProcessingWorkReader,
    private val persistenceHealthBus: PersistenceHealthBus,
    private val clock: Clock
) {
    suspend fun snapshot(): DiagnosticSnapshot {
        val inputs = capabilityInputsProvider.current()
        val capabilities = CapabilitiesSection(
            preciseLocation = inputs.preciseLocationGranted,
            approximateLocation = inputs.approximateLocationGranted,
            backgroundLocation = inputs.backgroundLocationGranted,
            activityRecognition = inputs.activityRecognitionGranted,
            notifications = inputs.notificationsEnabled,
            locationServices = inputs.locationServicesEnabled,
            batterySaver = system.batterySaverOn(),
            autoTrackingToggle = inputs.autoTrackingEnabledByUser,
            mode = CapabilityResolver.resolve(inputs)
        )

        val active = tripCaptureDao.findByStatus(CaptureStatus.ACTIVE)
        val recent = active?.let { rawTrackPointDao.findLastN(it.id, INTERVAL_SAMPLE) }.orEmpty()
        val last = recent.firstOrNull()
        val nowNanos = clock.elapsedRealtimeNanos()
        val lastAgeMs = last?.let { (nowNanos - it.elapsedRealtimeNanos) / 1_000_000 }
        val gapReason = active?.let {
            diagnosticEventDao.findOpenGapReason(it.id, TrackingSessionCoordinator.EVENT_LOCATION_GAP_STARTED, TrackingSessionCoordinator.EVENT_LOCATION_GAP_ENDED)
        }
        val approximateOnly = active?.let {
            diagnosticEventDao.findOpenGapReason(it.id, TrackingSessionCoordinator.EVENT_LOCATION_ACCURACY_DEGRADED, TrackingSessionCoordinator.EVENT_LOCATION_ACCURACY_RESTORED)
        } != null
        val lastTripStatistics = tripDao.findPreviousCompleted(Long.MAX_VALUE)
            ?.let { tripStatisticsDao.findByTripAndVersion(it.id, TripProcessingWorker.CURRENT_PROCESSING_VERSION) }

        val location = LocationSection(
            hasActiveCapture = active != null,
            pointCount = active?.let { rawTrackPointDao.countByCapture(it.id) },
            lastFixAgeMs = lastAgeMs,
            lastAccuracyM = last?.horizontalAccuracyM,
            lastFixHadSpeed = last?.let { it.speedMps != null },
            effectiveIntervalMs = DiagnosticFormulas.effectiveIntervalMs(recent.map { it.elapsedRealtimeNanos }),
            gapActive = gapReason != null,
            gapReason = gapReason,
            approximateOnly = approximateOnly,
            lastTripRejectedPoints = lastTripStatistics?.rejectedPointCount,
            lastTripGapCount = lastTripStatistics?.gapCount
        )

        val notificationShown = system.trackingNotificationShown()
        val persistence = persistenceHealthBus.state.value
        // An ACTIVE capture nobody is recording into: the one recovery-required state the app can observe directly.
        val orphaned = active != null && notificationShown == false
        val processing = processingWork.counts()
        val withoutStatistics = tripDao.findCompletedWithoutStatistics(TripProcessingWorker.CURRENT_PROCESSING_VERSION).size
        val inconsistencies = buildList {
            if (orphaned) add("An ACTIVE capture exists but the tracking notification is not showing")
            if (withoutStatistics > 0 && processing.pending + processing.running == 0) {
                add("$withoutStatistics completed trip(s) have no statistics and nothing is queued to compute them")
            }
        }

        val tracking = TrackingSection(
            activeCapture = active != null,
            shortCaptureId = active?.let { DiagnosticFormulas.shortId(it.id) },
            foregroundNotificationShown = notificationShown,
            lastPersistenceAgeMs = lastAgeMs,
            persistence = persistence,
            health = DiagnosticFormulas.health(persistence, gapActive = gapReason != null, approximateOnly = approximateOnly, recoveryRequired = orphaned)
        )

        return DiagnosticSnapshot(
            app = withContext(Dispatchers.IO) {
                AppSection(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    androidApi = system.androidApi(),
                    device = system.deviceSummary(),
                    databaseSchemaVersion = database.openHelper.readableDatabase.version,
                    // Stamped 0 everywhere today (no field-tuned detector or location profile exists yet); shown as they are.
                    detectorVersion = 0,
                    locationProfileVersion = 0,
                    processingVersion = TripProcessingWorker.CURRENT_PROCESSING_VERSION.value
                )
            },
            capabilities = capabilities,
            detector = DetectorSection(
                recent = diagnosticEventDao.findLatestByCategory(DiagnosticCategory.DETECTOR, DETECTOR_EVENTS).map { it.brief() },
                lastActivityTransition = diagnosticEventDao.findLatestByCategory(DiagnosticCategory.ACTIVITY_RECOGNITION, 1).firstOrNull()?.brief()
            ),
            location = location,
            tracking = tracking,
            processing = processing,
            recovery = RecoverySection(
                lastProcessExit = diagnosticEventDao.findLatestByType(ProcessExitRecorder.EVENT_PROCESS_EXIT, 1).firstOrNull()?.brief(),
                lastRecoveryAction = diagnosticEventDao.findLatestInCategoryExcluding(DiagnosticCategory.RECOVERY_SYSTEM, ProcessExitRecorder.EVENT_PROCESS_EXIT)?.brief(),
                openInconsistencies = inconsistencies
            )
        )
    }

    private fun DiagnosticEventEntity.brief() = EventBrief(
        occurredAt = occurredAt,
        eventType = eventType,
        reasonCode = reasonCode,
        // Codes and counters only (F0.13 §4.1); the exit's importance and state summary are exactly that.
        detail = metadata.entries.joinToString(" ") { "${it.key}=${it.value}" }.ifEmpty { null }
    )

    private companion object {
        /** Enough recent fixes for a stable median without loading a ride. */
        const val INTERVAL_SAMPLE = 12
        const val DETECTOR_EVENTS = 5
    }
}
