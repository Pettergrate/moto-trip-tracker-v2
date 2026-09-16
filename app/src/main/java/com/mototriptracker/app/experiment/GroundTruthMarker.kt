package com.mototriptracker.app.experiment

/**
 * F0.6 §7: the closed vocabulary of ground-truth annotations a tester can
 * record while stopped ("no se requiere interacción mientras la motocicleta
 * está en movimiento"). GT_START/GT_END are reviewed post-hoc against the
 * Raw Track, not tapped live — see F0.6 §7's own definitions for why they're
 * not "engine on"/"helmet on"/"first GPS point".
 */
enum class GroundTruthMarkerType {
    GT_START,
    GT_END,
    READY_TO_START,
    ARRIVED,
    MANUAL_PAUSE_BEGIN,
    MANUAL_PAUSE_END,
    KNOWN_TRAFFIC_STOP,
    KNOWN_TUNNEL,
    KNOWN_GPS_OBSTRUCTION
}

data class GroundTruthMarker(
    val type: GroundTruthMarkerType,
    val wallTimeEpochMs: Long,
    val elapsedRealtimeNanos: Long,
    val note: String? = null
)
