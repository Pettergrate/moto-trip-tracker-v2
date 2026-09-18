package com.mototriptracker.app.domain.detection

import com.mototriptracker.app.core.model.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ForgottenPauseEngineTest {

    // Short, deterministic profile so tests don't need unrealistically long synthetic sequences.
    private val profile = ForgottenPauseProfile(minConfirmationDurationMs = 10_000L, minDisplacementMeters = 50.0)
    private lateinit var engine: ForgottenPauseEngine

    @Before
    fun setUp() {
        engine = ForgottenPauseEngine(profile)
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

        assertEquals(ForgottenPauseDecision.NoChange, decision)
        assertFalse(engine.hasWarned)
    }

    @Test
    fun noWarningWhenEnoughTimeHasPassedButDisplacementIsTooSmall() {
        engine.accept(location(0L, latitude = 10.0))

        val decision = engine.accept(location(11_000_000_000L, latitude = 10.0)) // same spot, 11s later

        assertEquals(ForgottenPauseDecision.NoChange, decision)
        assertFalse(engine.hasWarned)
    }

    @Test
    fun noWarningWhenDisplacementIsEnoughButNotEnoughTimeHasPassed() {
        engine.accept(location(0L, latitude = 10.0))

        // ~111m north (0.001 degree) after only 2s.
        val decision = engine.accept(location(2_000_000_000L, latitude = 10.001))

        assertEquals(ForgottenPauseDecision.NoChange, decision)
        assertFalse(engine.hasWarned)
    }

    @Test
    fun warnsOnceBothDurationAndDisplacementThresholdsAreMet() {
        engine.accept(location(0L, latitude = 10.0))

        val decision = engine.accept(location(11_000_000_000L, latitude = 10.001))

        assertTrue(decision is ForgottenPauseDecision.WarningIssued)
        val warning = decision as ForgottenPauseDecision.WarningIssued
        assertEquals(0L, warning.anchoredAtElapsedRealtimeNanos)
        assertEquals(11_000_000_000L, warning.detectedAtElapsedRealtimeNanos)
        assertTrue(engine.hasWarned)
    }

    @Test
    fun neverWarnsTwiceForTheSameEngineInstance() {
        engine.accept(location(0L, latitude = 10.0))
        engine.accept(location(11_000_000_000L, latitude = 10.001))
        check(engine.hasWarned)

        // Even more time/displacement afterward must not produce a second warning -
        // F0.3 §8 explicitly defers "second reminder/escalation" to future research.
        val decision = engine.accept(location(30_000_000_000L, latitude = 10.01))

        assertEquals(ForgottenPauseDecision.NoChange, decision)
    }

    @Test
    fun measuresDisplacementFromTheOriginalAnchorNotTheLastSample() {
        engine.accept(location(0L, latitude = 10.0))
        // Small, sub-threshold movement first - must not silently become the new anchor.
        engine.accept(location(5_000_000_000L, latitude = 10.00005))

        val decision = engine.accept(location(11_000_000_000L, latitude = 10.001))

        assertTrue(
            "displacement must be measured from the original anchor (10.0), not the intermediate sample",
            decision is ForgottenPauseDecision.WarningIssued
        )
    }
}
