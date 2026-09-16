package com.mototriptracker.app.experiment

import com.mototriptracker.app.core.model.DetectorVersion

/**
 * F0.6 §6.1's session metadata contract, verbatim. Only `playServicesVersion`
 * ("si disponible"), `weatherNotes` and `notes` are marked optional there —
 * everything else, including [endedAt], is required. That means this type
 * models a *completed* session record (built once the session has actually
 * ended), not live in-progress state; see `FieldTestSessionExporter`.
 *
 * Fields ending in "...State" (`batterySaverState`, `locationSettingsState`,
 * `notificationPermissionState`) are open descriptive strings, not booleans:
 * F0.6 doesn't give them a closed vocabulary the way it does for the
 * "...Granted" fields, and Android's own notification permission in
 * particular has a real "not yet requested" state distinct from "denied"
 * that's worth preserving as diagnostic context.
 */
data class FieldTestSessionMetadata(
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long,
    val appVersion: String,
    val diagnosticSchemaVersion: Int,
    val experimentProfileId: String,
    val detectorVersion: DetectorVersion,
    val phoneManufacturer: String,
    val phoneModel: String,
    val androidVersion: String,
    val playServicesVersion: String?,
    val batterySaverState: String,
    val locationSettingsState: String,
    val preciseLocationGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val activityRecognitionGranted: Boolean,
    val notificationPermissionState: String,
    val phonePlacement: String,
    val screenStateAtStart: String,
    val routeType: String,
    val weatherNotes: String? = null,
    val notes: String? = null
)
