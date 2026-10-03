package com.mototriptracker.app.domain.detection

/**
 * DET-011 (`ADR-030`): the numbers of the low-power movement watcher - a geofence around the place the phone is parked,
 * whose EXIT says "the phone has left". ADR-018: every one is a placeholder, chosen to be measured, not tuned.
 *
 * - [radiusMeters]: 150 m. Play Services does not report a geofence much smaller than ~100 m reliably, and the network
 *   position that centres it can itself be off by tens of metres: a smaller circle would "exit" without anyone moving.
 *   Larger means later (at 10 m/s, 150 m is 15 s before the latency of the detection itself is counted).
 * - [maxCenterAccuracyM]: 100 m. A centre taken from a fix that poor says little about where the phone is; arming around
 *   it would fire (or never fire) for no reason, so the watcher says it could not arm (`POOR_FIX`) instead.
 * - [maxFixAgeMs] / [fixTimeoutMs]: a last-known position is accepted if it is under two minutes old; otherwise one
 *   current network fix is asked for, for at most eight seconds (the watcher often runs inside a broadcast's lifetime).
 *   A position older than that is refused (`STALE_FIX`) whatever the platform calls it: it returned a 4.8-minute-old one
 *   as "current" on the phone.
 * - [minRearmIntervalMs]: after an EXIT the watcher re-arms around the new position, and the app re-syncing it re-arms
 *   too, but neither more often than every two minutes: while riding, each EXIT would otherwise cost a position fix every
 *   ~150 m, and opening the app syncs it more than once in the same second.
 */
data class MovementWatchProfile(
    val radiusMeters: Float = 150f,
    val maxCenterAccuracyM: Float = 100f,
    val maxFixAgeMs: Long = 120_000L,
    val fixTimeoutMs: Long = 8_000L,
    val minRearmIntervalMs: Long = 120_000L
)
