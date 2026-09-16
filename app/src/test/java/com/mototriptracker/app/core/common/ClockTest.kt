package com.mototriptracker.app.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FND-004 acceptance: "core logic can use fake clocks". This proves the seam
 * itself is controllable and deterministic; the domain logic that will
 * actually consume [Clock] (detector timers, gap/duration math) is tested
 * against it once that logic exists (the DET and PRC tasks), not invented
 * here just to exercise this seam.
 */
class ClockTest {

    @Test
    fun advanceMillisMovesBothWallAndElapsedClocksTogether() {
        val clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000_000_000L)

        clock.advanceMillis(5_000L)

        assertEquals(6_000L, clock.wallClockMillis())
        assertEquals(6_000_000_000L, clock.elapsedRealtimeNanos())
    }

    @Test
    fun wallAndElapsedClocksCanDivergeIndependently() {
        // Models a user changing the wall-clock time without a reboot:
        // elapsed/monotonic time must not jump just because wall time did
        // (F0.10 §19.3).
        val clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L)

        clock.setWallClockMillis(50_000L)

        assertEquals(50_000L, clock.wallClockMillis())
        assertEquals(1_000L, clock.elapsedRealtimeNanos())
    }
}
