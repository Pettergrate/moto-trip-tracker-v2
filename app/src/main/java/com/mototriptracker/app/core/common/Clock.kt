package com.mototriptracker.app.core.common

/**
 * F0.10 §19 / ADR-013: domain and data logic must depend on this seam, never
 * directly on `System.currentTimeMillis()` or `android.os.SystemClock`, so
 * tests can substitute a controllable time source (F0.12 §5.1 FakeClock).
 *
 * The two clocks are not interchangeable: [wallClockMillis] is for
 * presentation/export/auditing and can jump (timezone change, user sets the
 * clock); [elapsedRealtimeNanos] is monotonic within a boot and is what
 * duration/ordering math must use (F0.7 §2.6, F0.10 §19.1, REL-INV-010).
 */
interface Clock {
    fun wallClockMillis(): Long
    fun elapsedRealtimeNanos(): Long
}
