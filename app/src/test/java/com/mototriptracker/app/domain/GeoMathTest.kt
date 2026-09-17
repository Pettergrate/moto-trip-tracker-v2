package com.mototriptracker.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GeoMathTest {

    @Test
    fun sameCoordinateIsZeroDistance() {
        assertEquals(0.0, haversineMeters(9.9345217, -84.2212314, 9.9345217, -84.2212314), 0.0)
    }

    @Test
    fun matchesAnIndependentlyComputedHaversineValue() {
        val expected = independentHaversine(10.0, -20.0, 10.001, -20.001)
        assertEquals(expected, haversineMeters(10.0, -20.0, 10.001, -20.001), 0.001)
    }

    @Test
    fun oneDegreeOfLatitudeIsRoughlyOneHundredElevenKilometers() {
        val distance = haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, distance, 100.0)
    }

    /** A second, independently written implementation of the same formula, not calling the code under test. */
    private fun independentHaversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
