package com.mototriptracker.app.tracking.movement

/**
 * DET-011: one position, handed from [MovementFixSource] to [MovementWatchRegistration] and nowhere else. The
 * coordinates exist only for that hand-over: they are never stored, logged or put in a diagnostic (ADR-009) - the
 * only copy that outlives it is the one Play Services keeps inside the geofence.
 */
data class MovementFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val ageMs: Long,
    /** `last` (an already known position) or `current` (one asked for now) - recorded, to tell the cost of each. */
    val source: String
)

/** Where the watcher gets the position to centre its geofence on; an interface so it can be faked without Play Services. */
interface MovementFixSource {
    /** A position at most [maxAgeMs] old, or a fresh one within [timeoutMs]; `null` when neither could be had. Never throws. */
    suspend fun currentFix(maxAgeMs: Long, timeoutMs: Long): MovementFix?
}

/** The two things a geofence registration can do; an interface so it can be faked without Play Services. */
interface MovementWatchRegistration {
    /** Replaces any earlier watch with one centred on the given position. Throws if the platform refuses. */
    suspend fun arm(latitude: Double, longitude: Double, radiusMeters: Float)

    /** Removes the watch (what "Auto Tracking is off" has to mean). Idempotent; never throws. */
    suspend fun disarm()
}

/**
 * What the rest of the app asks of the watcher. Several places can change what it should be doing - the app starting,
 * the person switching Auto Tracking, Activity Recognition saying the phone is still, a capture ending - and each just
 * names its reason; the watcher decides.
 */
interface MovementWatching {
    /** Applies the desired state: armed when Auto Tracking is listening, removed when it is not. */
    suspend fun sync(listening: Boolean)

    /** (Re)centres the watch on where the phone is now, if the watcher may run at all. */
    suspend fun ensureArmed(reason: String)

    /** The geofence reported that the phone left it. */
    suspend fun onExit()

    /** A broadcast arrived that held no usable event; recorded, so it cannot be mistaken for one that never came. */
    suspend fun onEmptyBroadcast(reason: String)
}
