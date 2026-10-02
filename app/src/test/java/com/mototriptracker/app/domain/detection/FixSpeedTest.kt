package com.mototriptracker.app.domain.detection

import com.mototriptracker.app.core.model.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FixSpeedTest {

    private fun fix(
        elapsedMs: Long,
        latitude: Double = 10.0,
        speedMps: Float? = null,
        speedAccuracyMps: Float? = null,
        accuracyM: Float = 5f
    ) = LocationSample(
        wallTimeEpochMs = elapsedMs,
        elapsedRealtimeNanos = elapsedMs * 1_000_000,
        receivedAtElapsedRealtimeNanos = elapsedMs * 1_000_000,
        latitude = latitude,
        longitude = -84.0,
        horizontalAccuracyM = accuracyM,
        requestProfileId = "test",
        speedMps = speedMps,
        speedAccuracyMps = speedAccuracyMps
    )

    /** ~1 m of latitude per 0.000009 degrees. */
    private fun northBy(meters: Double) = 10.0 + meters / 111_195.0

    @Test
    fun theReportedSpeedIsUsedWhenTheFixCarriesOne() {
        assertEquals(7.5f, FixSpeed.effectiveSpeedMps(null, fix(0, speedMps = 7.5f)))
    }

    @Test
    fun aZeroReportedSpeedIsARealAnswerNotAMissingOne() {
        // Standing still must read as 0, not fall through to a derived number from jittery positions.
        val previous = fix(0)
        val current = fix(2_000, latitude = northBy(30.0), speedMps = 0f)

        assertEquals(0f, FixSpeed.effectiveSpeedMps(previous, current))
    }

    @Test
    fun aSpeedTheFixItselfDistrustsFallsBackToThePositions() {
        val previous = fix(0)
        val current = fix(2_000, latitude = northBy(20.0), speedMps = 9f, speedAccuracyMps = 8f)

        val speed = FixSpeed.effectiveSpeedMps(previous, current)

        assertEquals(10f, speed!!, 0.5f) // 20 m in 2 s
    }

    @Test
    fun withNoReportedSpeedTheSpeedBetweenTwoFixesStandsIn() {
        val previous = fix(0)
        val current = fix(2_000, latitude = northBy(20.0))

        assertEquals(10f, FixSpeed.effectiveSpeedMps(previous, current)!!, 0.5f)
    }

    @Test
    fun noReportedSpeedAndNoPreviousFixSaysNothing() {
        assertNull(FixSpeed.effectiveSpeedMps(null, fix(0)))
    }

    @Test
    fun movementInsideTheFixesOwnAccuracyIsNotSpeed() {
        // 8 m between two 5 m fixes is jitter, not a vehicle (DP-001).
        val previous = fix(0)
        val current = fix(2_000, latitude = northBy(8.0))

        assertNull(FixSpeed.effectiveSpeedMps(previous, current))
    }

    @Test
    fun aFixTooCoarseToTrustSaysNothingEvenWithAReportedSpeed() {
        assertNull(FixSpeed.effectiveSpeedMps(fix(0), fix(2_000, speedMps = 12f, accuracyM = 250f)))
    }

    @Test
    fun aGapBetweenFixesTooLongOrTooShortToDeriveFromSaysNothing() {
        val previous = fix(0)

        assertNull("too soon", FixSpeed.effectiveSpeedMps(previous, fix(500, latitude = northBy(40.0))))
        assertNull("too long ago", FixSpeed.effectiveSpeedMps(previous, fix(20_000, latitude = northBy(400.0))))
    }
}
