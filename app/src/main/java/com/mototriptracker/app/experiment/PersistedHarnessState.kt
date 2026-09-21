package com.mototriptracker.app.experiment

import com.mototriptracker.app.core.model.CapabilityInputs

/**
 * A full snapshot of `FieldTestHarnessViewModel`'s in-progress session -
 * everything needed to rebuild its `Active` state after the app process is
 * recreated. Not part of F0.6 §20's dataset schema (that's the FINAL,
 * already-ended session's `session.json`/`annotations.json`); this is purely
 * a resumability aid, written to `field-tests/harness-in-progress.json` by
 * [FieldTestHarnessStateStore] and cleared once a session is genuinely
 * exported.
 */
data class PersistedHarnessState(
    val sessionId: String,
    val experimentProfileId: String,
    val startedAtWallMs: Long,
    val startedAtElapsedNanos: Long,
    val phonePlacement: String,
    val routeType: String,
    val weatherNotes: String,
    val notes: String,
    val deviceSnapshot: FieldTestDeviceSnapshot,
    val capabilityInputsAtStart: CapabilityInputs,
    val markers: List<GroundTruthMarker>,
    /** The real `TripCaptureEntity.id` this session is riding along with, once observed - `null` until the first successful poll finds one. Needed at export time to pull this session's own `raw-track.csv` rows. */
    val associatedCaptureId: String?
)
