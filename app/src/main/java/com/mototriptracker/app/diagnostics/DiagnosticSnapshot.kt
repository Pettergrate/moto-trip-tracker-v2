package com.mototriptracker.app.diagnostics

import com.mototriptracker.app.core.model.CapabilityMode
import com.mototriptracker.app.tracking.persistence.PersistenceState

/**
 * DIA-002 / F0.13 §10.1: everything the debug screen shows *about the app's current state*, gathered in one
 * value. The events timeline is a separate stream. DIA-003's export reuses this same value (its
 * `health-snapshot.json`, `capabilities.json`, `versions.json`), so what a person reads on screen and what a
 * bug report carries cannot drift apart.
 *
 * **Never a source of truth** (F0.13 §18.1: "debug screen puede reconstruirse desde repositories sin convertirse
 * en fuente de verdad"): every field is read from Room, the platform or an in-memory bus, and nothing here is
 * written back. **No coordinates, ever** (F0.13 §10.1 "no mostrar coordenadas exactas por defecto"): a point is
 * described by its age, accuracy and whether it carried a speed, not by where it was.
 *
 * A field that cannot be known says so (`null`) rather than guessing - ADR-016 applied to diagnostics.
 */
data class DiagnosticSnapshot(
    val app: AppSection,
    val capabilities: CapabilitiesSection,
    val detector: DetectorSection,
    val location: LocationSection,
    val tracking: TrackingSection,
    val processing: ProcessingSection,
    val recovery: RecoverySection
)

data class AppSection(
    val versionName: String,
    val versionCode: Int,
    val androidApi: Int,
    /** Manufacturer and model only - no serial, no advertising id. */
    val device: String,
    val databaseSchemaVersion: Int,
    val detectorVersion: Int,
    val locationProfileVersion: Int,
    val processingVersion: Int
)

data class CapabilitiesSection(
    val preciseLocation: Boolean,
    val approximateLocation: Boolean,
    val backgroundLocation: Boolean,
    val activityRecognition: Boolean,
    val notifications: Boolean,
    val locationServices: Boolean,
    /** `null` when the platform would not say. */
    val batterySaver: Boolean?,
    val autoTrackingToggle: Boolean,
    val mode: CapabilityMode
)

/**
 * F0.13 §10.1 asks for the detector's current state, time in state and last trigger. None of that is *persisted* (the
 * detection engines are in-memory and only exist while a session runs), so this shows what is really recorded - the
 * last things the detector said and the last Activity Recognition transition - and does not invent a "current state".
 */
data class DetectorSection(
    val recent: List<EventBrief>,
    val lastActivityTransition: EventBrief?
)

data class LocationSection(
    val hasActiveCapture: Boolean,
    val pointCount: Int?,
    val lastFixAgeMs: Long?,
    val lastAccuracyM: Float?,
    val lastFixHadSpeed: Boolean?,
    /** Median gap between the most recent stored fixes; `null` with fewer than two. */
    val effectiveIntervalMs: Long?,
    val gapActive: Boolean,
    val gapReason: String?,
    val approximateOnly: Boolean,
    val lastTripRejectedPoints: Int?,
    val lastTripGapCount: Int?
)

data class TrackingSection(
    val activeCapture: Boolean,
    /** A short, non-reversible label (a hash), so a human can tell captures apart without the real id. */
    val shortCaptureId: String?,
    /** Whether the foreground notification is actually showing; `null` when it cannot be told. */
    val foregroundNotificationShown: Boolean?,
    val lastPersistenceAgeMs: Long?,
    val persistence: PersistenceState,
    val health: HealthState
)

/** F0.10 §21's four conceptual states. */
enum class HealthState { HEALTHY, DEGRADED, RECOVERY_REQUIRED, PERSISTENCE_CRITICAL }

data class ProcessingSection(
    val pending: Int,
    val running: Int,
    val succeeded: Int,
    val failed: Int,
    val maxAttemptCount: Int
)

data class RecoverySection(
    val lastProcessExit: EventBrief?,
    val lastRecoveryAction: EventBrief?,
    val openInconsistencies: List<String>
)

/** A diagnostic event reduced to what a person needs to read in one line. */
data class EventBrief(
    val occurredAt: Long,
    val eventType: String,
    val reasonCode: String?,
    val detail: String?
)
