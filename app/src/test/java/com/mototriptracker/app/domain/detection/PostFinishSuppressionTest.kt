package com.mototriptracker.app.domain.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostFinishSuppressionTest {

    // Short, deterministic profile so tests don't need unrealistically long synthetic durations.
    private val profile = PostFinishSuppressionProfile(suppressionDurationMs = 10_000L)

    @Test
    fun neverSuppressedWhenNoCaptureHasEverEnded() {
        val suppressed = PostFinishSuppression.isSuppressed(
            lastCaptureEndedAtElapsedRealtimeNanos = null,
            nowElapsedRealtimeNanos = 1_000_000_000L,
            profile = profile
        )

        assertFalse(suppressed)
    }

    @Test
    fun suppressedImmediatelyAfterAFinish() {
        val suppressed = PostFinishSuppression.isSuppressed(
            lastCaptureEndedAtElapsedRealtimeNanos = 0L,
            nowElapsedRealtimeNanos = 0L,
            profile = profile
        )

        assertTrue(suppressed)
    }

    @Test
    fun stillSuppressedJustBeforeTheWindowElapses() {
        val suppressed = PostFinishSuppression.isSuppressed(
            lastCaptureEndedAtElapsedRealtimeNanos = 0L,
            nowElapsedRealtimeNanos = 9_999_000_000L, // 9.999s
            profile = profile
        )

        assertTrue(suppressed)
    }

    @Test
    fun noLongerSuppressedOnceTheWindowFullyElapses() {
        val suppressed = PostFinishSuppression.isSuppressed(
            lastCaptureEndedAtElapsedRealtimeNanos = 0L,
            nowElapsedRealtimeNanos = 10_000_000_000L, // exactly 10s
            profile = profile
        )

        assertFalse(suppressed)
    }

    @Test
    fun noLongerSuppressedLongAfterTheWindowElapses() {
        val suppressed = PostFinishSuppression.isSuppressed(
            lastCaptureEndedAtElapsedRealtimeNanos = 0L,
            nowElapsedRealtimeNanos = 60_000_000_000L,
            profile = profile
        )

        assertFalse(suppressed)
    }
}
