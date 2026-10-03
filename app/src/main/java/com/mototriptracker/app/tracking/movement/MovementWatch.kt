package com.mototriptracker.app.tracking.movement

import android.util.Log
import com.mototriptracker.app.BuildConfig
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.dao.TripCaptureDao
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.domain.capability.DetectionListening
import com.mototriptracker.app.domain.detection.MovementWatchProfile
import com.mototriptracker.app.tracking.capability.CapabilityInputsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DET-011 (`ADR-030`): a low-power watcher that notices the phone leaving the place it was parked, without GPS and
 * without depending on Android calling the rider a vehicle - the label that, on the owner's phone, arrives late (three
 * rides on 2026-10-02 all began recording at speed or after their first stop).
 *
 * **Observation mode: it records, it does not act.** A geofence EXIT is logged (`MOVEMENT_WATCH_EXIT`) as the moment a
 * detector built on it *would have* started a candidate; nothing is started, nothing about how rides are recorded
 * changes. The point of this first version is to measure, over a day or two of real riding, how much earlier that moment
 * is than the Activity Recognition trigger, and what the watcher costs on the battery. Acting on it is a separate,
 * later decision (the code that would do it is `ActivityTransitionReceiver.maybeStartAutoDetection`'s).
 *
 * Why a geofence and not plain location updates: Android limits a background app's location updates to a few per hour,
 * so a passive trigger built on them would be nearly blind; geofencing is the platform's mechanism for exactly this, it
 * works from network/Wi-Fi/cell positions (no GPS) and it survives the process being killed (a PendingIntent, like the
 * Activity Recognition registration).
 *
 * The watch is centred on the phone's position, taken once when it is armed. When it is armed: at every app start, boot
 * or change of Auto Tracking (`AutoTrackingDetection.sync`), when Activity Recognition reports `STILL` (the phone has
 * settled somewhere new), when a capture finishes, and straight after an EXIT (at most every
 * [MovementWatchProfile.minRearmIntervalMs]). It is never armed while a capture is ACTIVE (a ride is already being
 * recorded), and "off means off" (`PERM-002`): with Auto Tracking not listening it is removed and records nothing.
 *
 * The events carry no coordinates (ADR-009): only counts, ms and metres - the radius, the accuracy and age of the
 * position used - and the reason.
 */
@Singleton
class MovementWatch @Inject constructor(
    private val registration: MovementWatchRegistration,
    private val fixSource: MovementFixSource,
    private val capabilityInputsProvider: CapabilityInputsProvider,
    private val tripCaptureDao: TripCaptureDao,
    private val diagnosticEventDao: DiagnosticEventDao,
    private val clock: Clock,
    private val idGenerator: IdGenerator
) : MovementWatching {

    private val profile = MovementWatchProfile()
    private val mutex = Mutex()

    /** In memory on purpose: after a process restart the geofence is still there, only this note is lost - and a missing note costs one extra position fix, nothing more. */
    private var armed = false
    private var lastArmedAtElapsedRealtimeNanos: Long? = null

    override suspend fun sync(listening: Boolean) {
        if (listening) ensureArmed(REASON_SYNC) else disarm(REASON_AUTO_TRACKING_OFF)
    }

    override suspend fun ensureArmed(reason: String) = mutex.withLock {
        val inputs = try {
            capabilityInputsProvider.current()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return@withLock // not being sure is not a reason to start watching
        }
        if (!DetectionListening.shouldListen(inputs)) {
            removeLocked(REASON_AUTO_TRACKING_OFF)
            return@withLock
        }
        val blocker = when {
            !inputs.locationServicesEnabled -> REASON_LOCATION_SERVICES_OFF
            !inputs.preciseLocationGranted -> REASON_PRECISE_LOCATION_MISSING
            !inputs.backgroundLocationGranted -> REASON_BACKGROUND_LOCATION_MISSING
            else -> null
        }
        if (blocker != null) {
            record(EVENT_ARM_FAILED, blocker, mapOf("armReason" to reason))
            return@withLock
        }
        // A ride is already being recorded: there is nothing for the watcher to notice.
        if (tripCaptureDao.findByStatus(CaptureStatus.ACTIVE) != null) return@withLock

        val now = clock.elapsedRealtimeNanos()
        val last = lastArmedAtElapsedRealtimeNanos
        // Re-centring on a *place change* (STILL, a finished capture) is always worth a position; re-centring because the
        // app was synced again (it is, several times when it opens: seen on the phone, two arms in one second) or because
        // the circle was just left, is not worth one more than every two minutes.
        val throttled = reason == REASON_AFTER_EXIT || reason == REASON_SYNC
        if (throttled && last != null && (now - last) / 1_000_000 < profile.minRearmIntervalMs) return@withLock

        val fix = fixSource.currentFix(profile.maxFixAgeMs, profile.fixTimeoutMs)
        if (fix == null) {
            record(EVENT_ARM_FAILED, REASON_NO_FIX, mapOf("armReason" to reason))
            return@withLock
        }
        // The platform can hand back an old cached position as a "current" one (seen: 4.8 minutes old). Centring the
        // circle there would watch a place the phone may already have left.
        if (fix.ageMs > profile.maxFixAgeMs) {
            record(EVENT_ARM_FAILED, REASON_STALE_FIX, mapOf("armReason" to reason, "fixAgeMs" to fix.ageMs.toString()))
            return@withLock
        }
        if (fix.accuracyM > profile.maxCenterAccuracyM) {
            record(EVENT_ARM_FAILED, REASON_POOR_FIX, mapOf("armReason" to reason, "fixAccuracyM" to fix.accuracyM.toInt().toString()))
            return@withLock
        }
        try {
            registration.arm(fix.latitude, fix.longitude, profile.radiusMeters)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            record(EVENT_ARM_FAILED, REASON_REGISTRATION_FAILED, mapOf("armReason" to reason, "error" to (error::class.simpleName ?: "Exception")))
            return@withLock
        }
        armed = true
        lastArmedAtElapsedRealtimeNanos = now
        record(
            EVENT_ARMED, reason,
            mapOf(
                "radiusM" to profile.radiusMeters.toInt().toString(),
                "fixAccuracyM" to fix.accuracyM.toInt().toString(),
                "fixAgeMs" to fix.ageMs.toString(),
                "source" to fix.source
            )
        )
    }

    override suspend fun onExit() {
        val stillListening = try {
            DetectionListening.shouldListen(capabilityInputsProvider.current())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            false
        }
        if (!stillListening) {
            // "Off means off": an EXIT that arrives after the switch was turned off is dropped, not recorded.
            disarm(REASON_AUTO_TRACKING_OFF)
            return
        }
        val captureActive = tripCaptureDao.findByStatus(CaptureStatus.ACTIVE) != null
        record(EVENT_EXIT, REASON_GEOFENCE_EXIT, mapOf("captureActive" to captureActive.toString()))
        mutex.withLock { armed = false }
        ensureArmed(REASON_AFTER_EXIT)
    }

    override suspend fun onEmptyBroadcast(reason: String) {
        val listening = try {
            DetectionListening.shouldListen(capabilityInputsProvider.current())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            false
        }
        if (listening) record(EVENT_BROADCAST_EMPTY, reason)
    }

    private suspend fun disarm(reason: String) = mutex.withLock { removeLocked(reason) }

    private suspend fun removeLocked(reason: String) {
        // Always asked of the platform, even when this process does not remember arming: the geofence outlives the process.
        registration.disarm()
        if (armed) {
            armed = false
            lastArmedAtElapsedRealtimeNanos = null
            record(EVENT_DISARMED, reason)
        }
    }

    /** Best effort: a failed write must never stop the watcher or whoever called it. */
    private suspend fun record(eventType: String, reasonCode: String, metadata: Map<String, String> = emptyMap()) {
        try {
            diagnosticEventDao.insert(
                DiagnosticEventEntity(
                    eventId = idGenerator.newId(),
                    occurredAt = clock.wallClockMillis(),
                    elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                    category = DiagnosticCategory.DETECTOR,
                    eventType = eventType,
                    severity = if (eventType == EVENT_ARM_FAILED || eventType == EVENT_BROADCAST_EMPTY) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
                    source = "MovementWatch",
                    captureId = null,
                    tripId = null,
                    correlationId = null,
                    stateBefore = null,
                    stateAfter = null,
                    reasonCode = reasonCode,
                    metadata = metadata,
                    appVersion = BuildConfig.VERSION_NAME,
                    schemaVersion = 1,
                    detectorVersion = DetectorVersion(0),
                    locationProfileVersion = LocationProfileVersion(0),
                    processingVersion = ProcessingVersion(0)
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "movement watch event not recorded ($eventType/${error::class.simpleName}); the watch continues")
        }
    }

    companion object {
        private const val TAG = "MovementWatch"

        /** The vocabulary of `eventType` for the watcher's own events (category `DETECTOR`). */
        const val EVENT_ARMED = "MOVEMENT_WATCH_ARMED"
        const val EVENT_ARM_FAILED = "MOVEMENT_WATCH_ARM_FAILED"
        const val EVENT_EXIT = "MOVEMENT_WATCH_EXIT"
        const val EVENT_DISARMED = "MOVEMENT_WATCH_DISARMED"
        const val EVENT_BROADCAST_EMPTY = "MOVEMENT_WATCH_BROADCAST_EMPTY"

        /** Why it was (re)armed: the `reasonCode` of `MOVEMENT_WATCH_ARMED`. */
        const val REASON_SYNC = "SYNC"
        const val REASON_STILL = "STILL"
        const val REASON_CAPTURE_FINISHED = "CAPTURE_FINISHED"
        const val REASON_AFTER_EXIT = "AFTER_EXIT"

        /** Why it could not be, or was removed: the `reasonCode` of `MOVEMENT_WATCH_ARM_FAILED` / `MOVEMENT_WATCH_DISARMED`. */
        const val REASON_AUTO_TRACKING_OFF = "AUTO_TRACKING_OFF"
        const val REASON_LOCATION_SERVICES_OFF = "LOCATION_SERVICES_OFF"
        const val REASON_PRECISE_LOCATION_MISSING = "PRECISE_LOCATION_MISSING"
        const val REASON_BACKGROUND_LOCATION_MISSING = "BACKGROUND_LOCATION_MISSING"
        const val REASON_NO_FIX = "NO_FIX"
        const val REASON_STALE_FIX = "STALE_FIX"
        const val REASON_POOR_FIX = "POOR_FIX"
        const val REASON_REGISTRATION_FAILED = "REGISTRATION_FAILED"

        /** `reasonCode` of `MOVEMENT_WATCH_EXIT`. */
        const val REASON_GEOFENCE_EXIT = "GEOFENCE_EXIT"
    }
}
