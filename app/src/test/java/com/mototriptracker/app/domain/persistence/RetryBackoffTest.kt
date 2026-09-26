package com.mototriptracker.app.domain.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** REC-006 / §14.3: a short retry policy that cannot become a retry storm. */
class RetryBackoffTest {

    @Test
    fun theFirstAttemptIsAlwaysAllowed() {
        assertTrue(RetryBackoff().canAttempt(nowMs = 0))
    }

    @Test
    fun afterAFailureTheNextAttemptWaitsAndTheWaitDoubles() {
        val backoff = RetryBackoff(initialDelayMs = 2_000, maxDelayMs = 30_000)

        backoff.onFailure(nowMs = 10_000)
        assertFalse(backoff.canAttempt(11_999))
        assertTrue(backoff.canAttempt(12_000))

        backoff.onFailure(nowMs = 12_000)
        assertFalse("second wait is 4 s", backoff.canAttempt(15_999))
        assertTrue(backoff.canAttempt(16_000))
    }

    @Test
    fun theWaitStopsGrowingAtTheCap() {
        val backoff = RetryBackoff(initialDelayMs = 2_000, maxDelayMs = 30_000)

        repeat(10) { backoff.onFailure(nowMs = 0) }

        assertEquals(30_000L, backoff.currentDelayMs)
        assertEquals(10, backoff.consecutiveFailures)
    }

    @Test
    fun aSuccessResetsTheWaitAndAllowsTheNextAttemptImmediately() {
        val backoff = RetryBackoff(initialDelayMs = 2_000, maxDelayMs = 30_000)
        repeat(4) { backoff.onFailure(nowMs = 0) }

        backoff.onSuccess()

        assertTrue(backoff.canAttempt(0))
        assertEquals(2_000L, backoff.currentDelayMs)
        assertEquals(0, backoff.consecutiveFailures)
    }

    /** An hour of a dead database is tried a few dozen times, not once per 2 s fix. */
    @Test
    fun anHourOfContinuousFailureCostsAFewDozenAttemptsNotOnePerFix() {
        val backoff = RetryBackoff()
        var attempts = 0

        var nowMs = 0L
        while (nowMs < 3_600_000L) { // one location fix every 2 s
            if (backoff.canAttempt(nowMs)) {
                attempts++
                backoff.onFailure(nowMs)
            }
            nowMs += 2_000L
        }

        assertTrue("attempts=$attempts", attempts in 1..150)
    }
}
