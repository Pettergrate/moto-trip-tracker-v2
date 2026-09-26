package com.mototriptracker.app.domain.detection

/**
 * REC-005 / `reliability-recovery.md` §13: tells, from monotonic time alone,
 * whether an ACTIVE recording has gone "fix expected but none arrived" long
 * enough to be a *gap*, and when it ends. It never decides anything about the
 * trip: absence of fixes is not "the user stopped" (§13, GPS research §17), so
 * the only output is a pair of transitions for the caller to record and show.
 *
 * Time is `elapsedRealtimeNanos` throughout (REL-INV-010): it keeps counting
 * through device sleep, so a gap measured here is the real one even if the
 * process was throttled while it lasted. No Android dependency (ADR-013).
 *
 * Two ways a gap is noticed, and they agree by construction:
 * - [onTick]: the periodic check saw silence reach [gapThresholdMs] - reports
 *   [Transition.GapStarted] while the gap is still going on, so the UI can say
 *   "signal lost" *now*.
 * - [onSignal]: a fix arrived after a silence that was already past the
 *   threshold but nobody ticked in between (the ticker does not run while the
 *   device is in deep sleep) - reports the whole gap retroactively, started
 *   and ended together, so the evidence is the same either way.
 *
 * Before the very first signal there is nothing to have *lost*: a capture that
 * has not received its first fix yet is "searching", not in a gap (§13 speaks of
 * a gap between valid evidence), so [onTick] stays quiet until [onSignal] has
 * been seen once or the watch was seeded with an earlier signal.
 */
class LocationSignalWatch(
    private val gapThresholdMs: Long = DEFAULT_GAP_THRESHOLD_MS,
    initialSignalAtElapsedRealtimeNanos: Long? = null
) {
    sealed interface Transition {
        /** [lastSignalAtElapsedRealtimeNanos] is when the last fix *before* the silence arrived. */
        data class GapStarted(val lastSignalAtElapsedRealtimeNanos: Long, val silenceMs: Long) : Transition

        /** [closedBySuspension]: the rider paused, so the gap was closed rather than the signal restored. */
        data class GapEnded(
            val lastSignalAtElapsedRealtimeNanos: Long,
            val resumedAtElapsedRealtimeNanos: Long,
            val closedBySuspension: Boolean = false
        ) : Transition {
            val durationMs: Long get() = (resumedAtElapsedRealtimeNanos - lastSignalAtElapsedRealtimeNanos) / NANOS_PER_MILLI
        }
    }

    private var lastSignalAt: Long? = initialSignalAtElapsedRealtimeNanos
    private var gapOpen = false

    /** True between a reported [Transition.GapStarted] and its [Transition.GapEnded]. */
    val isInGap: Boolean get() = gapOpen

    /** A location fix was received at [nowElapsedRealtimeNanos]. Zero, one or two transitions, in order. */
    fun onSignal(nowElapsedRealtimeNanos: Long): List<Transition> {
        val last = lastSignalAt
        lastSignalAt = nowElapsedRealtimeNanos
        if (last == null) return emptyList()

        val transitions = mutableListOf<Transition>()
        val silenceMs = (nowElapsedRealtimeNanos - last) / NANOS_PER_MILLI
        if (!gapOpen && silenceMs >= gapThresholdMs) {
            transitions += Transition.GapStarted(last, silenceMs)
            gapOpen = true
        }
        if (gapOpen) {
            transitions += Transition.GapEnded(last, nowElapsedRealtimeNanos)
            gapOpen = false
        }
        return transitions
    }

    /**
     * The rider has paused: no route evidence is wanted, so silence is not judged (§13 is about an
     * ACTIVE recording). Called for every fix heard and every periodic check while paused, it keeps
     * the reference fresh so resuming does not read the whole pause as lost signal, and closes a gap
     * that was still open when the pause began. Does nothing before the first fix - a paused
     * recording that never heard one is still only "searching".
     */
    fun onSuspended(nowElapsedRealtimeNanos: Long): Transition.GapEnded? {
        val last = lastSignalAt ?: return null
        lastSignalAt = nowElapsedRealtimeNanos
        if (!gapOpen) return null
        gapOpen = false
        return Transition.GapEnded(last, nowElapsedRealtimeNanos, closedBySuspension = true)
    }

    /** The periodic check at [nowElapsedRealtimeNanos]; only ever opens a gap. */
    fun onTick(nowElapsedRealtimeNanos: Long): Transition? {
        val last = lastSignalAt ?: return null
        if (gapOpen) return null
        val silenceMs = (nowElapsedRealtimeNanos - last) / NANOS_PER_MILLI
        if (silenceMs < gapThresholdMs) return null
        gapOpen = true
        return Transition.GapStarted(last, silenceMs)
    }

    companion object {
        /**
         * The same silence `ProcessingEngine` already calls a gap when it derives
         * `LocationGap`s from the raw points (PRC-001), so what the rider is told
         * live and what the finished trip discloses are one definition. Still an
         * unvalidated placeholder until F0.6 field data exists (see there).
         */
        const val DEFAULT_GAP_THRESHOLD_MS = 30_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
