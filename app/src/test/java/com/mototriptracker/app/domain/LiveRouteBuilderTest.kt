package com.mototriptracker.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRouteBuilderTest {

    private fun straightLine(count: Int) = (0 until count).map { GeoPoint(10.0, -20.0 + it * 0.0001) }

    @Test
    fun emptyPointsProduceAnEmptyResultAndLeaveStateUntouched() {
        val previous = LiveRouteState()

        val result = buildLiveRoute(previous, emptyList())

        assertEquals(emptyList<GeoPoint>(), result.displayPoints)
        assertSame(previous, result.nextState)
    }

    @Test
    fun theFirstCallAlwaysSimplifiesEvenBelowTheThrottle() {
        val points = straightLine(5)

        val result = buildLiveRoute(LiveRouteState(), points, throttlePoints = 10)

        // A perfectly straight line collapses to just its endpoints once simplified.
        assertEquals(listOf(points.first(), points.last()), result.nextState.simplifiedPrefix)
        assertEquals(5, result.nextState.simplifiedUpToCount)
        assertEquals(listOf(points.first(), points.last()), result.displayPoints)
    }

    @Test
    fun belowTheThrottleTheStateIsUnchangedAndNewPointsAreAppendedRawToTheLastSimplifiedPrefix() {
        val first10 = straightLine(10)
        val afterFirst = buildLiveRoute(LiveRouteState(), first10, throttlePoints = 10)

        // Two more points arrive - 12 total, only +2 since the last simplification (10) - below the throttle of 10.
        val twoMore = first10 + listOf(GeoPoint(10.0, -19.9), GeoPoint(10.0, -19.8))
        val result = buildLiveRoute(afterFirst.nextState, twoMore, throttlePoints = 10)

        assertSame("state is untouched - no resimplification happened", afterFirst.nextState, result.nextState)
        // Its simplified prefix (first/last of the straight line) plus the two new raw points, verbatim.
        assertEquals(afterFirst.nextState.simplifiedPrefix + listOf(GeoPoint(10.0, -19.9), GeoPoint(10.0, -19.8)), result.displayPoints)
        // The true latest point is never stale, even though the prefix wasn't recomputed.
        assertEquals(GeoPoint(10.0, -19.8), result.displayPoints.last())
    }

    @Test
    fun reachingTheThrottleTriggersAFreshResimplificationOverAllPoints() {
        val first10 = straightLine(10)
        val afterFirst = buildLiveRoute(LiveRouteState(), first10, throttlePoints = 10)

        // 10 more points arrive - exactly the throttle - triggers a fresh simplification over all 20.
        val all20 = straightLine(20)
        val result = buildLiveRoute(afterFirst.nextState, all20, throttlePoints = 10)

        assertTrue("a fresh resimplification ran", result.nextState !== afterFirst.nextState)
        assertEquals(20, result.nextState.simplifiedUpToCount)
        assertEquals(listOf(all20.first(), all20.last()), result.nextState.simplifiedPrefix)
    }

    @Test
    fun theCurrentPositionIsAlwaysTheTrueLatestRawPointRegardlessOfThrottling() {
        var state = LiveRouteState()
        val allPoints = mutableListOf<GeoPoint>()
        for (i in 0 until 25) {
            allPoints.add(GeoPoint(10.0 + i * 0.00001, -20.0 + i * 0.0002)) // a real turn, not collinear
            val result = buildLiveRoute(state, allPoints, throttlePoints = 10)
            state = result.nextState
            assertEquals("current position must never lag, at raw point $i", allPoints.last(), result.displayPoints.last())
        }
    }
}
