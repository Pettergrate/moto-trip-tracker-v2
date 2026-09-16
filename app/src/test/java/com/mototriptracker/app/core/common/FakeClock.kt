package com.mototriptracker.app.core.common

/**
 * F0.12 §5.1: a controllable time source for tests — no real sleeps to test
 * timeouts, grace periods or duration math (F0.12 §19). TST-001 will expand
 * the shared test-double suite around this; this is the minimal version
 * needed to prove FND-004's own acceptance criterion ("core logic can use
 * fake clocks").
 */
class FakeClock(
    private var wallMillis: Long = 0L,
    private var elapsedNanos: Long = 0L
) : Clock {
    override fun wallClockMillis(): Long = wallMillis
    override fun elapsedRealtimeNanos(): Long = elapsedNanos

    fun advanceMillis(millis: Long) {
        wallMillis += millis
        elapsedNanos += millis * 1_000_000L
    }

    fun setWallClockMillis(millis: Long) {
        wallMillis = millis
    }

    fun setElapsedRealtimeNanos(nanos: Long) {
        elapsedNanos = nanos
    }
}
