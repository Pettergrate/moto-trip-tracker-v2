package com.mototriptracker.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class RouteSimplifierTest {

    @Test
    fun emptyAndSinglePointListsAreReturnedUnchanged() {
        assertEquals(emptyList<GeoPoint>(), simplifyRoute(emptyList()))
        val single = listOf(GeoPoint(10.0, -20.0))
        assertEquals(single, simplifyRoute(single))
    }

    @Test
    fun twoPointsAreReturnedUnchangedRegardlessOfTolerance() {
        val points = listOf(GeoPoint(10.0, -20.0), GeoPoint(10.001, -20.001))

        assertEquals(points, simplifyRoute(points, toleranceMeters = 1_000.0))
    }

    @Test
    fun aPerfectlyStraightLineCollapsesToJustItsEndpoints() {
        // 20 collinear points (constant longitude step) at the same latitude.
        val points = (0..19).map { GeoPoint(10.0, -20.0 + it * 0.0001) }

        val simplified = simplifyRoute(points, toleranceMeters = 5.0)

        assertEquals(listOf(points.first(), points.last()), simplified)
    }

    @Test
    fun smallJitterWithinToleranceIsDroppedAsNoise() {
        // A straight line with sub-meter zig-zag noise on every other point -
        // real GPS jitter, not a real turn.
        val points = (0..19).map { i ->
            val jitter = if (i % 2 == 0) 0.0 else 0.000001 // ~0.1m at this latitude
            GeoPoint(10.0 + jitter, -20.0 + i * 0.0001)
        }

        val simplified = simplifyRoute(points, toleranceMeters = 5.0)

        assertEquals(listOf(points.first(), points.last()), simplified)
    }

    @Test
    fun aRealCornerBeyondToleranceIsKept() {
        // An L-shaped corner: straight east, then straight north - the
        // corner point sits far from the straight line between the two ends.
        val points = listOf(
            GeoPoint(10.0, -20.0),
            GeoPoint(10.0, -19.999),
            GeoPoint(10.0, -19.998), // corner
            GeoPoint(10.001, -19.998),
            GeoPoint(10.002, -19.998)
        )

        val simplified = simplifyRoute(points, toleranceMeters = 5.0)

        assertTrue("the corner point must survive simplification", simplified.contains(points[2]))
    }

    @Test
    fun neverDropsTheFirstOrLastPoint() {
        val points = (0..99).map { GeoPoint(10.0 + it * 0.00001, -20.0 + it * 0.00003) }

        val simplified = simplifyRoute(points, toleranceMeters = 50.0)

        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
    }

    /**
     * FR-MAP-003's own "usable for large numbers of TrackPoints" as an
     * actual, repeatable check, not just an assertion in a doc comment.
     * The synthetic route below mimics a real road: long straight stretches
     * connected by turns, not adversarial worst-case zig-zag noise - the
     * same shape RDP is good at collapsing in practice.
     */
    @Test
    fun simplifiesTenThousandPointsQuicklyAndReducesTheCountSubstantially() {
        val points = mutableListOf<GeoPoint>()
        var lat = 10.0
        var lon = -84.0
        var headingLat = 0.00001
        var headingLon = 0.00001
        for (i in 0 until 10_000) {
            if (i % 400 == 0) {
                // A turn every ~400 points - swap which axis is moving, like a real street grid.
                val temp = headingLat
                headingLat = headingLon
                headingLon = temp
            }
            lat += headingLat
            lon += headingLon
            points += GeoPoint(lat, lon)
        }

        var simplified: List<GeoPoint> = emptyList()
        val elapsedMs = measureTimeMillis {
            simplified = simplifyRoute(points, toleranceMeters = 8.0)
        }

        assertTrue(
            "expected simplification of 10,000 realistic points to finish well under 2s, took ${elapsedMs}ms",
            elapsedMs < 2_000
        )
        assertTrue(
            "expected long straight stretches to collapse substantially - got ${simplified.size} of ${points.size}",
            simplified.size < points.size / 2
        )
        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
    }
}
