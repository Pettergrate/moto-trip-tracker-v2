package com.mototriptracker.app.core.model

/**
 * The observation-level subset of F0.5 §5 / F0.7 §6.2's RawTrackPoint
 * contract — everything except the capture/detector bookkeeping fields
 * (captureId, sequenceNumber, detectorStateSnapshot/tripStateAtCapture) that
 * only make sense once a capture is actively recording, and that F0.12
 * §5.2's own (narrower) field list for `LocationReplaySource` doesn't ask
 * for. [requestProfileId] is kept — F0.12 §5.2 explicitly lists
 * "source/profile metadata" as a field this replay type needs. This is what
 * TST-001's `LocationReplaySource` (app/src/test/...) feeds into detector/
 * processing logic during tests. Pure data, zero Android dependency
 * (ADR-013) — the DET and PRC tasks convert real `android.location.Location`
 * objects into this shape at the platform boundary; this class itself never
 * does.
 *
 * Nullability mirrors the same F0.5/F0.7 audit done for RawTrackPointEntity
 * in FND-003, including its one open inconsistency: F0.7 §6.2 doesn't mark
 * [receivedAtElapsedRealtimeNanos] with `?`, but F0.5 §5 explicitly does
 * ("medir latencia de entrega si se necesita") — kept nullable here for the
 * same reason as RawTrackPointEntity.
 */
data class LocationSample(
    val wallTimeEpochMs: Long,
    val elapsedRealtimeNanos: Long,
    val receivedAtElapsedRealtimeNanos: Long?,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyM: Float,
    val requestProfileId: String,
    val altitudeEllipsoidM: Double? = null,
    val altitudeMslM: Double? = null,
    val verticalAccuracyM: Float? = null,
    val speedMps: Float? = null,
    val speedAccuracyMps: Float? = null,
    val bearingDeg: Float? = null,
    val bearingAccuracyDeg: Float? = null,
    val provider: String? = null,
    val isMock: Boolean? = null
)
