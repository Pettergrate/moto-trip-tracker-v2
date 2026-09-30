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

    /**
     * Found on the phone (2026-09-29): `elapsedRealtime` is monotonic only within one boot session (REL-INV-010) - a
     * reboot resets it near zero, so a completed capture's stored end time (from before the reboot) reads as later than
     * "now" (after it). The subtraction went negative, which was always less than the window, so this silently
     * suppressed automatic detection forever after any reboot that followed a Finish - reproduced with the app's own
     * debug trigger: the real numbers were `now` = 39,474,690 ms of uptime, stored end = 87,421,367 ms from the boot
     * before. No trip had recorded automatically since, and there was no way out except a manual Finish.
     */
    @Test
    fun notSuppressedWhenTheStoredEndIsAfterNowBecauseARebootMustHaveHappenedSince() {
        val suppressed = PostFinishSuppression.isSuppressed(
            lastCaptureEndedAtElapsedRealtimeNanos = 87_421_367_000_000L,
            nowElapsedRealtimeNanos = 39_474_690_000_000L,
            profile = profile
        )

        assertFalse(suppressed)
    }

    @Test
    fun theExactBoundaryOfNowEqualsTheStoredEndIsNotTreatedAsAReboot() {
        assertTrue(PostFinishSuppression.isSuppressed(lastCaptureEndedAtElapsedRealtimeNanos = 5_000L, nowElapsedRealtimeNanos = 5_000L, profile = profile))
    }
}
