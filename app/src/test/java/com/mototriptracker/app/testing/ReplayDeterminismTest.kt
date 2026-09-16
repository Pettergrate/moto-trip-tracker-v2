package com.mototriptracker.app.testing

import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.core.model.TransitionType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * TST-001 acceptance: "a sample replay is deterministic; tests use no
 * arbitrary sleeps for core state timing." This is that proof — not a test
 * of detector/processing behavior (nothing consumes these replays yet).
 */
class ReplayDeterminismTest {

    private fun sampleLocations() = listOf(
        LocationSample(
            wallTimeEpochMs = 1_000L,
            elapsedRealtimeNanos = 1_000_000_000L,
            receivedAtElapsedRealtimeNanos = null,
            latitude = 9.93,
            longitude = -84.08,
            horizontalAccuracyM = 5f,
            requestProfileId = "test-profile"
        ),
        LocationSample(
            wallTimeEpochMs = 2_000L,
            elapsedRealtimeNanos = 2_000_000_000L,
            receivedAtElapsedRealtimeNanos = null,
            latitude = 9.931,
            longitude = -84.081,
            horizontalAccuracyM = 6f,
            requestProfileId = "test-profile"
        )
    )

    @Test
    fun locationReplayIsDeterministicAndDoesNotSleep() = runTest {
        val source = LocationReplaySource(sampleLocations())

        val elapsedWallClockMs = measureTimeMillis {
            val firstRun = source.replay().toList()
            val secondRun = source.replay().toList()
            assertEquals(firstRun, secondRun)
            assertEquals(2, firstRun.size)
            assertEquals(1_000L, firstRun.first().wallTimeEpochMs)
            assertEquals(2_000L, firstRun.last().wallTimeEpochMs)
        }

        // Two replays of a 2-sample list must not take anywhere near the
        // ~2 seconds of "recorded" time between the samples — proving
        // nothing here is a real sleep/delay, just data replay.
        assertTrue("replay took ${elapsedWallClockMs}ms, suspiciously slow for pure data replay", elapsedWallClockMs < 1_000)
    }

    @Test
    fun activityReplayIsDeterministic() = runTest {
        val samples = listOf(
            ActivityTransitionSample(
                ActivityType.IN_VEHICLE, TransitionType.ENTER,
                elapsedRealtimeNanos = 1_000L, wallTimeEpochMs = 1_000L, source = "test"
            ),
            ActivityTransitionSample(
                ActivityType.IN_VEHICLE, TransitionType.EXIT,
                elapsedRealtimeNanos = 5_000L, wallTimeEpochMs = 5_000L, source = "test"
            )
        )
        val source = ActivityReplaySource(samples)

        assertEquals(samples, source.replay().toList())
    }

    @Test
    fun fakeDispatcherProviderCollapsesAllThreeOntoOneTestDispatcher() {
        val dispatcher = StandardTestDispatcher()
        val provider = FakeDispatcherProvider(dispatcher)

        assertEquals(dispatcher, provider.io)
        assertEquals(dispatcher, provider.default)
        assertEquals(dispatcher, provider.main)
    }

    @Test
    fun fakeCapabilityProviderCanTransitionFromFullAutoToRevoked() {
        val provider = FakeCapabilityProvider(FakeCapabilityProvider.fullAuto())
        assertTrue(provider.capabilityInputs.value.backgroundLocationGranted)

        provider.update { it.copy(backgroundLocationGranted = false) }

        assertEquals(false, provider.capabilityInputs.value.backgroundLocationGranted)
        // Revoking one capability must not silently flip unrelated ones.
        assertTrue(provider.capabilityInputs.value.preciseLocationGranted)
    }
}
