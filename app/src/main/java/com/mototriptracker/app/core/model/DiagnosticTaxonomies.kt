package com.mototriptracker.app.core.model

/**
 * F0.13 §5: the 10 diagnostic event categories. `eventType` itself stays a
 * plain String (F0.13 §4: "código estable, no texto libre como identidad
 * lógica") — F0.13 §5 gives worked examples per category (e.g.
 * `DETECTOR_STATE_CHANGED`, `LOCATION_GAP_STARTED`), not an exhaustive
 * closed list, so it isn't modeled as a second enum here.
 */
enum class DiagnosticCategory {
    DETECTOR,
    ACTIVITY_RECOGNITION,
    LOCATION,
    TRACKING_SERVICE,
    CAPABILITY_PERMISSIONS,
    PERSISTENCE,
    PROCESSING_WORKER,
    EDITING_LINEAGE,
    RECOVERY_SYSTEM,
    USER_COMMAND
}

/** F0.13 §6, verbatim table. */
enum class DiagnosticSeverity { TRACE, INFO, WARN, ERROR }
