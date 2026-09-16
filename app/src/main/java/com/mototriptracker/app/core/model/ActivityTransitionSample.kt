package com.mototriptracker.app.core.model

/**
 * Mirrors the Activity Recognition vocabulary F0.4 §4.1 documents as
 * available from the platform Transition API. `IN_VEHICLE` is a candidate
 * trigger only — never proof of motorcycle (AND-002 / F0.4 §4.3).
 */
enum class ActivityType { IN_VEHICLE, ON_FOOT, WALKING, RUNNING, ON_BICYCLE, STILL, UNKNOWN }

enum class TransitionType { ENTER, EXIT }

/**
 * Domain-safe replay of an Activity Recognition transition, per F0.6 §6.3's
 * "Activity events" field list ("timestamp / elapsedRealtime; transitionType;
 * activityType; confidence? (solo cuando la API usada lo ofrezca); source").
 * Only `confidence` is marked optional there — [wallTimeEpochMs] and
 * [source] are required, not optional as an earlier version of this class
 * had them. Zero Android/Google-Play-services dependency (ADR-013) —
 * TST-001's `ActivityReplaySource` (app/src/test/...) feeds these into
 * detector logic during tests without needing Play services on the test JVM.
 */
data class ActivityTransitionSample(
    val activityType: ActivityType,
    val transitionType: TransitionType,
    val elapsedRealtimeNanos: Long,
    val wallTimeEpochMs: Long,
    val source: String,
    val confidence: Int? = null
)
