package com.mototriptracker.app.tracking.service

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DET-001 / AUTO-001 follow-up (2026-09-29): `createAutoDetectIntent`/`seedFromIntent` is the fix for the transition
 * that started `ACTION_AUTO_DETECT` being lost by `ActivityTransitionBus` (no replay, and the subscription this
 * intent leads to cannot exist yet at the moment the receiver emits) - proven separately by
 * `ActivityTransitionBusProbeTest`. These test the encode/decode round trip in isolation, and that anything short of a
 * complete, well-formed seed degrades to `null` rather than a guess.
 */
@RunWith(RobolectricTestRunner::class)
class TrackingForegroundServiceSeedTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val sample = ActivityTransitionSample(
        activityType = ActivityType.IN_VEHICLE,
        transitionType = TransitionType.ENTER,
        elapsedRealtimeNanos = 123_456_789L,
        wallTimeEpochMs = 1_700_000_000_000L,
        source = "activity-transition-receiver",
        confidence = 87
    )

    @Test
    fun aSeedSurvivesTheRoundTrip() {
        val intent = TrackingForegroundService.createAutoDetectIntent(context, sample)

        assertEquals(sample, TrackingForegroundService.seedFromIntent(intent))
    }

    @Test
    fun theOptionalConfidenceSurvivesBeingAbsent() {
        val intent = TrackingForegroundService.createAutoDetectIntent(context, sample.copy(confidence = null))

        assertEquals(null, TrackingForegroundService.seedFromIntent(intent)?.confidence)
    }

    @Test
    fun everyActivityAndTransitionTypeSurvivesTheRoundTrip() {
        ActivityType.entries.forEach { activityType ->
            TransitionType.entries.forEach { transitionType ->
                val intent = TrackingForegroundService.createAutoDetectIntent(context, sample.copy(activityType = activityType, transitionType = transitionType))
                val decoded = TrackingForegroundService.seedFromIntent(intent)
                assertEquals("$activityType/$transitionType", activityType, decoded?.activityType)
                assertEquals("$activityType/$transitionType", transitionType, decoded?.transitionType)
            }
        }
    }

    @Test
    fun aNullIntentHasNoSeed() {
        assertNull(TrackingForegroundService.seedFromIntent(null))
    }

    /** What a bare `ACTION_AUTO_DETECT` with no extras at all decodes to - the fallback path a manual/adb-sent intent would take. */
    @Test
    fun anIntentWithNoExtrasHasNoSeed() {
        val bare = Intent(context, TrackingForegroundService::class.java).setAction(TrackingForegroundService.ACTION_AUTO_DETECT)

        assertNull(TrackingForegroundService.seedFromIntent(bare))
    }

    @Test
    fun anUnknownActivityTypeStringDegradesToNoSeedRatherThanCrashing() {
        val intent = TrackingForegroundService.createAutoDetectIntent(context, sample)
            .putExtra("com.mototriptracker.app.extra.SEED_ACTIVITY_TYPE", "NOT_A_REAL_TYPE")

        assertNull(TrackingForegroundService.seedFromIntent(intent))
    }

    @Test
    fun aMissingTimestampExtraDegradesToNoSeed() {
        val intent = TrackingForegroundService.createAutoDetectIntent(context, sample)
            .apply { removeExtra("com.mototriptracker.app.extra.SEED_ELAPSED_REALTIME_NANOS") }

        assertNull(TrackingForegroundService.seedFromIntent(intent))
    }
}
