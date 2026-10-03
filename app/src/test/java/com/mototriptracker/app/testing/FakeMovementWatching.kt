package com.mototriptracker.app.testing

import com.mototriptracker.app.tracking.movement.MovementFix
import com.mototriptracker.app.tracking.movement.MovementFixSource
import com.mototriptracker.app.tracking.movement.MovementWatchRegistration
import com.mototriptracker.app.tracking.movement.MovementWatching

/** DET-011: records what the rest of the app asked of the movement watch, without any of its machinery. */
class FakeMovementWatching : MovementWatching {
    val calls = mutableListOf<String>()

    /** Makes every call fail, the way a platform error inside the watcher would. */
    var failWith: Exception? = null

    private fun note(call: String) {
        calls += call
        failWith?.let { throw it }
    }

    override suspend fun sync(listening: Boolean) = note("sync($listening)")
    override suspend fun ensureArmed(reason: String) = note("ensureArmed($reason)")
    override suspend fun onExit() = note("onExit")
    override suspend fun onEmptyBroadcast(reason: String) = note("onEmptyBroadcast($reason)")
}

/** DET-011: records what was asked of the geofence registration, without Play Services. */
class FakeMovementWatchRegistration : MovementWatchRegistration {
    class Armed(val latitude: Double, val longitude: Double, val radiusMeters: Float)

    val armed = mutableListOf<Armed>()
    var disarmCount = 0

    /** When set, the next [arm] throws it, the way a platform refusal would. */
    var failArmWith: Exception? = null

    override suspend fun arm(latitude: Double, longitude: Double, radiusMeters: Float) {
        failArmWith?.let { throw it }
        armed += Armed(latitude, longitude, radiusMeters)
    }

    override suspend fun disarm() {
        disarmCount++
    }
}

/** DET-011: a position source that hands back what the test sets, and remembers how it was asked. */
class FakeMovementFixSource(var fix: MovementFix? = null) : MovementFixSource {
    var requests = 0
    var lastMaxAgeMs: Long? = null
    var lastTimeoutMs: Long? = null

    override suspend fun currentFix(maxAgeMs: Long, timeoutMs: Long): MovementFix? {
        requests++
        lastMaxAgeMs = maxAgeMs
        lastTimeoutMs = timeoutMs
        return fix
    }
}
