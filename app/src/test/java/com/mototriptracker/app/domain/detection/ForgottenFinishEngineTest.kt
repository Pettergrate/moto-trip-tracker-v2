package com.mototriptracker.app.domain.detection

import com.mototriptracker.app.core.model.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ForgottenFinishEngineTest {

    // Short, deterministic profile so tests don't need unrealistically long synthetic sequences.
    private val profile = ForgottenFinishProfile(minStationaryDurationMs = 10_000L, maxStationaryDisplacementMeters = 50.0)
    private lateinit var engine: ForgottenFinishEngine

    @Before
    fun setUp() {
        engine = ForgottenFinishEngine(profile)
    }

    private fun location(elapsedNanos: Long, latitude: Double, longitude: Double = -84.0) = LocationSample(
        wallTimeEpochMs = elapsedNanos / 1_000_000,
        elapsedRealtimeNanos = elapsedNanos,
        receivedAtElapsedRealtimeNanos = elapsedNanos,
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyM = 5.0f,
        requestProfileId = "test-profile"
    )

    @Test
    fun theFirstSampleOnlyAnchorsAndNeverWarnsByItself() {
        val decision = engine.accept(location(0L, latitude = 10.0))

        assertEquals(ForgottenFinishDecision.NoChange, decision)
    }

    @Test
    fun noWarningWhenStationaryButNotLongEnough() {
        engine.accept(location(0L, latitude = 10.0))

        val decision = engine.accept(location(9_000_000_000L, latitude = 10.0)) // same spot, 9s later

        assertEquals(ForgottenFinishDecision.NoChange, decision)
    }

    @Test
    fun noWarningWhenLongEnoughButTheRiderKeptMoving() {
        engine.accept(location(0L, latitude = 10.0))

        // Real, continuous riding: each sample moves well past the stationary radius,
        // repeatedly re-anchoring - the elapsed time from the *original* anchor is
        // irrelevant once real movement is observed.
        engine.accept(location(3_000_000_000L, latitude = 10.001))
        val decision = engine.accept(location(20_000_000_000L, latitude = 10.002))

        assertEquals(ForgottenFinishDecision.NoChange, decision)
    }

    @Test
    fun warnsOnceStationaryPastTheDurationThreshold() {
        engine.accept(location(0L, latitude = 10.0))

        val decision = engine.accept(location(11_000_000_000L, latitude = 10.0))

        assertTrue(decision is ForgottenFinishDecision.WarningIssued)
        val warning = decision as ForgottenFinishDecision.WarningIssued
        assertEquals(0L, warning.anchoredAtElapsedRealtimeNanos)
        assertEquals(11_000_000_000L, warning.detectedAtElapsedRealtimeNanos)
    }

    @Test
    fun smallGpsJitterWithinTheRadiusStillCountsAsStationary() {
        engine.accept(location(0L, latitude = 10.0))
        // ~1m of jitter (0.00001 degree), well inside the 50m radius.
        engine.accept(location(5_000_000_000L, latitude = 10.00001))

        val decision = engine.accept(location(11_000_000_000L, latitude = 10.0))

        assertTrue(
            "jitter inside the radius must not reset the stationary anchor",
            decision is ForgottenFinishDecision.WarningIssued
        )
    }

    @Test
    fun neverWarnsTwiceForTheSameStationaryEpisode() {
        engine.accept(location(0L, latitude = 10.0))
        val first = engine.accept(location(11_000_000_000L, latitude = 10.0))
        check(first is ForgottenFinishDecision.WarningIssued)

        val decision = engine.accept(location(20_000_000_000L, latitude = 10.0))

        assertEquals(ForgottenFinishDecision.NoChange, decision)
    }

    @Test
    fun warnsAgainForALaterStationaryEpisodeAfterRealMovementInBetween() {
        engine.accept(location(0L, latitude = 10.0))
        val first = engine.accept(location(11_000_000_000L, latitude = 10.0))
        check(first is ForgottenFinishDecision.WarningIssued)

        // Rides away (real movement past the radius), then stops again just as long.
        engine.accept(location(12_000_000_000L, latitude = 10.01))
        val decision = engine.accept(location(23_000_000_000L, latitude = 10.01))

        assertTrue(
            "a genuinely new stationary episode after real movement must be able to warn again",
            decision is ForgottenFinishDecision.WarningIssued
        )
    }
}
