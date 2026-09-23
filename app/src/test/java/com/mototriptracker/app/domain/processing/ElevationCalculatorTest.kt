package com.mototriptracker.app.domain.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElevationCalculatorTest {

    // Short, deterministic thresholds so tests don't need unrealistically long synthetic sequences.
    private val profile = ElevationProfile(maxVerticalAccuracyM = 20.0, minElevationChangeM = 3.0, minReliableSampleCount = 3)

    private fun sample(elevationMeters: Double?, isGapBoundary: Boolean = false) = ElevationSample(elevationMeters, isGapBoundary)

    @Test
    fun noValidSamplesLeavesEveryFieldNull() {
        val metrics = ElevationCalculator.compute(listOf(sample(null), sample(null)), profile)

        assertNull(metrics.minElevationM)
        assertNull(metrics.maxElevationM)
        assertNull(metrics.ascentM)
        assertNull(metrics.descentM)
    }

    @Test
    fun minMaxFillWithFewerSamplesThanTheReliableCountButAscentDescentStayNull() {
        // Only 2 valid samples, below minReliableSampleCount=3 - FR-MET-009's
        // range isn't gated the way FR-MET-010's gain/loss is.
        val metrics = ElevationCalculator.compute(listOf(sample(100.0), sample(110.0)), profile)

        assertEquals(100.0, metrics.minElevationM!!, 0.0001)
        assertEquals(110.0, metrics.maxElevationM!!, 0.0001)
        assertNull("too few samples for a reliable ascent/descent figure", metrics.ascentM)
        assertNull("too few samples for a reliable ascent/descent figure", metrics.descentM)
    }

    @Test
    fun startAndEndElevationAreTheFirstAndLastValidSamplesEvenBelowTheReliableCount() {
        // Same 2-sample case as above - start/end are ungated exactly like min/max.
        val metrics = ElevationCalculator.compute(listOf(sample(100.0), sample(105.0), sample(110.0)), profile)

        assertEquals(100.0, metrics.startElevationM!!, 0.0001)
        assertEquals(110.0, metrics.endElevationM!!, 0.0001)
    }

    @Test
    fun startAndEndElevationSkipMissingSamplesAtEitherEdge() {
        // The very first and last list entries have no elevation at all -
        // start/end must be the first/last *valid* sample, not list position 0/last.
        val metrics = ElevationCalculator.compute(
            listOf(sample(null), sample(100.0), sample(110.0), sample(120.0), sample(null)),
            profile
        )

        assertEquals(100.0, metrics.startElevationM!!, 0.0001)
        assertEquals(120.0, metrics.endElevationM!!, 0.0001)
    }

    @Test
    fun startAndEndElevationAreNullWhenNoSampleHasAnElevation() {
        val metrics = ElevationCalculator.compute(listOf(sample(null), sample(null)), profile)

        assertNull(metrics.startElevationM)
        assertNull(metrics.endElevationM)
    }

    @Test
    fun aSteadyClimbIsCreditedEntirelyAsAscent() {
        val metrics = ElevationCalculator.compute(
            listOf(sample(100.0), sample(105.0), sample(110.0), sample(115.0)),
            profile
        )

        assertEquals(15.0, metrics.ascentM!!, 0.0001)
        assertEquals(0.0, metrics.descentM!!, 0.0001)
        assertEquals(100.0, metrics.minElevationM!!, 0.0001)
        assertEquals(115.0, metrics.maxElevationM!!, 0.0001)
    }

    @Test
    fun aSteadyDescentIsCreditedEntirelyAsDescent() {
        val metrics = ElevationCalculator.compute(
            listOf(sample(115.0), sample(110.0), sample(105.0), sample(100.0)),
            profile
        )

        assertEquals(0.0, metrics.ascentM!!, 0.0001)
        assertEquals(15.0, metrics.descentM!!, 0.0001)
    }

    @Test
    fun oscillationWithinTheHysteresisBandNeverAccumulates() {
        // Each step is +/-2m, under the 3m band - GPS vertical noise, not real climbing.
        val metrics = ElevationCalculator.compute(
            listOf(sample(100.0), sample(102.0), sample(100.0), sample(102.0), sample(100.0)),
            profile
        )

        assertEquals(0.0, metrics.ascentM!!, 0.0001)
        assertEquals(0.0, metrics.descentM!!, 0.0001)
    }

    @Test
    fun aRealClimbThenDescentCreditsBothDirectionsFromTheirOwnConfirmedBaseline() {
        val metrics = ElevationCalculator.compute(
            listOf(sample(100.0), sample(110.0), sample(120.0), sample(112.0), sample(104.0)),
            profile
        )

        assertEquals(20.0, metrics.ascentM!!, 0.0001) // 100 -> 120
        assertEquals(16.0, metrics.descentM!!, 0.0001) // 120 -> 104
    }

    @Test
    fun aGapBoundaryDoesNotCreditADeltaAcrossItsOwnEdgeButStillResetsTheBaselineAfterward() {
        val metrics = ElevationCalculator.compute(
            listOf(
                sample(100.0),
                sample(500.0, isGapBoundary = true), // a huge jump across an unmeasured gap - must not count as real ascent
                sample(505.0), // real climbing resumes from the gap-boundary point onward
                sample(510.0)
            ),
            profile
        )

        assertEquals(10.0, metrics.ascentM!!, 0.0001) // only 500 -> 505 -> 510, never 100 -> 500
        assertEquals(0.0, metrics.descentM!!, 0.0001)
        // The gap-boundary's own value still counts toward the honest range.
        assertEquals(100.0, metrics.minElevationM!!, 0.0001)
        assertEquals(510.0, metrics.maxElevationM!!, 0.0001)
    }

    @Test
    fun missingSamplesInterleavedWithValidOnesAreSkippedNotTreatedAsZero() {
        val metrics = ElevationCalculator.compute(
            listOf(sample(100.0), sample(null), sample(110.0), sample(null), sample(120.0)),
            profile
        )

        assertEquals(20.0, metrics.ascentM!!, 0.0001)
        assertEquals(100.0, metrics.minElevationM!!, 0.0001)
        assertEquals(120.0, metrics.maxElevationM!!, 0.0001)
    }
}
