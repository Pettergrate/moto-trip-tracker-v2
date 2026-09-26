package com.mototriptracker.app.domain.persistence

/**
 * REC-006 / §14.1-14.3: "reintentar según política corta" without a retry storm ("evitar ciclos
 * infinitos de escritura que consuman CPU/batería"). Retries are allowed at most once per
 * [currentDelayMs], which doubles after every failure up to [maxDelayMs] and resets on success -
 * so a database that is down for an hour is tried a few dozen times, not once per location fix.
 *
 * Time is passed in (monotonic milliseconds), never read here (REL-INV-010). No Android
 * dependency (ADR-013). Not thread-safe: the owner serializes access.
 */
class RetryBackoff(
    private val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS
) {
    private var nextAttemptAtMs: Long = Long.MIN_VALUE

    var currentDelayMs: Long = initialDelayMs
        private set

    /** Failed attempts since the last success. */
    var consecutiveFailures: Int = 0
        private set

    fun canAttempt(nowMs: Long): Boolean = nowMs >= nextAttemptAtMs

    fun onFailure(nowMs: Long) {
        consecutiveFailures++
        nextAttemptAtMs = nowMs + currentDelayMs
        currentDelayMs = (currentDelayMs * 2).coerceAtMost(maxDelayMs)
    }

    fun onSuccess() {
        consecutiveFailures = 0
        currentDelayMs = initialDelayMs
        nextAttemptAtMs = Long.MIN_VALUE
    }

    companion object {
        /** About one location fix: the first retry is nearly immediate because most failures are momentary. */
        const val DEFAULT_INITIAL_DELAY_MS = 2_000L

        /** Unvalidated placeholder (F0.8 leaves the numbers to Phase 1 measurement). */
        const val DEFAULT_MAX_DELAY_MS = 30_000L
    }
}
