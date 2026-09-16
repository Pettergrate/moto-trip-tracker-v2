package com.mototriptracker.app.core.model

/**
 * The raw permission/service/setting inputs F0.11 §18-19's capability model
 * resolves into FULL_AUTO / ASSISTED_AUTO / MANUAL / LOCATION_DEGRADED.
 *
 * This is deliberately just the input shape, not the resolver: `CAP-001`
 * (not yet implemented) owns the actual decision logic and its output enum.
 * TST-001 needs this shape now because its `FakeCapabilityProvider`
 * (app/src/test/...) has to expose *something* deterministic for CAP-001 to
 * consume in its own tests once it exists — the fields mirror F0.11 §5's
 * permission matrix and CAP-001's own stated test matrix ("clean install,
 * denied permissions, approximate location, disabled location services and
 * notification denial") one-to-one, nothing invented beyond it.
 */
data class CapabilityInputs(
    val preciseLocationGranted: Boolean,
    val approximateLocationGranted: Boolean,
    val activityRecognitionGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val notificationsEnabled: Boolean,
    val locationServicesEnabled: Boolean,
    val autoTrackingEnabledByUser: Boolean
)
