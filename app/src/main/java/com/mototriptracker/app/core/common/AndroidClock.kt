package com.mototriptracker.app.core.common

import android.os.SystemClock
import javax.inject.Inject

/**
 * The only place allowed to call the real Android/JVM clocks (ADR-013) —
 * everything else, including domain code, takes a [Clock] and can be given
 * a fake one in tests.
 */
class AndroidClock @Inject constructor() : Clock {
    override fun wallClockMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
