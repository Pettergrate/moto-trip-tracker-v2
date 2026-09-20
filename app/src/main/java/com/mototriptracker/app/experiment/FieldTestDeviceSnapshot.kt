package com.mototriptracker.app.experiment

/**
 * The subset of `FieldTestSessionMetadata`'s F0.6 §6.1 fields that Android
 * itself can answer, captured once at session start. `playServicesVersion`
 * is left `null` deliberately: F0.6 marks it "si disponible" (optional), and
 * reading it would need `GoogleApiAvailability` from `play-services-base`,
 * which isn't an existing dependency here (only `play-services-location` is)
 * — not worth adding for an optional diagnostic field.
 */
data class FieldTestDeviceSnapshot(
    val appVersion: String,
    val phoneManufacturer: String,
    val phoneModel: String,
    val androidVersion: String,
    val playServicesVersion: String?,
    val batterySaverState: String,
    val locationSettingsState: String,
    val notificationPermissionState: String,
    val screenStateAtStart: String
)
