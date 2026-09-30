package com.mototriptracker.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BearingTest {

    private val equator = GeoPoint(0.0, 0.0)

    @Test
    fun dueNorthIsZeroDegrees() {
        assertEquals(0.0, bearingDegrees(equator, GeoPoint(1.0, 0.0)), 0.01)
    }

    @Test
    fun dueEastIsNinetyDegrees() {
        assertEquals(90.0, bearingDegrees(equator, GeoPoint(0.0, 1.0)), 0.01)
    }

    @Test
    fun dueSouthIsOneEightyDegrees() {
        assertEquals(180.0, bearingDegrees(equator, GeoPoint(-1.0, 0.0)), 0.01)
    }

    @Test
    fun dueWestIsTwoSeventyDegrees() {
        assertEquals(270.0, bearingDegrees(equator, GeoPoint(0.0, -1.0)), 0.01)
    }

    @Test
    fun theSamePointTwiceIsTheHonestDegenerateZeroNotACrash() {
        assertEquals(0.0, bearingDegrees(equator, equator), 0.01)
    }

    @Test
    fun resultIsAlwaysInTheZeroToThreeSixtyRange() {
        val bearing = bearingDegrees(GeoPoint(10.0, -20.0), GeoPoint(9.9, -20.1))
        assertEquals(true, bearing in 0.0..360.0)
    }
}
