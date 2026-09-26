package com.mototriptracker.app.feature.active

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** REC-005 / F0.10 §21: what the Active Trip screen says about the location signal. */
class ActiveTripSignalTest {

    @Test
    fun aRecordingWithPointsAndNoOpenGapIsHealthy() {
        assertEquals(ActiveTripSignal.OK, activeTripSignal(isPaused = false, pointCount = 42, openGapReason = null))
    }

    @Test
    fun noPointsYetIsSearchingNotLost() {
        assertEquals(ActiveTripSignal.SEARCHING, activeTripSignal(isPaused = false, pointCount = 0, openGapReason = null))
    }

    @Test
    fun anOpenGapWithSignalOnIsLostNoFix() {
        assertEquals(ActiveTripSignal.LOST_NO_FIX, activeTripSignal(isPaused = false, pointCount = 42, openGapReason = "NO_FIX"))
    }

    @Test
    fun anOpenGapWithLocationServicesOffSaysThat() {
        assertEquals(
            ActiveTripSignal.LOST_LOCATION_SERVICES_OFF,
            activeTripSignal(isPaused = false, pointCount = 42, openGapReason = "LOCATION_SERVICES_OFF")
        )
    }

    @Test
    fun aPausedTripNeverReadsAsALostSignal() {
        assertEquals(ActiveTripSignal.OK, activeTripSignal(isPaused = true, pointCount = 0, openGapReason = "NO_FIX"))
    }

    @Test
    fun onlyTheProblemStatesHaveAnythingToSay() {
        assertNull(signalNotice(ActiveTripSignal.OK))
        ActiveTripSignal.entries.filter { it != ActiveTripSignal.OK }.forEach { assertEquals(it.name, true, signalNotice(it) != null) }
    }

    @Test
    fun onlyApproximateLocationWinsOverNoGpsSignalBecauseTheCauseIsKnown() {
        assertEquals(ActiveTripSignal.APPROXIMATE_ONLY, activeTripSignal(isPaused = false, pointCount = 5, openGapReason = "NO_FIX", approximateOnly = true))
        assertEquals(ActiveTripSignal.APPROXIMATE_ONLY, activeTripSignal(isPaused = false, pointCount = 0, openGapReason = null, approximateOnly = true))
    }

    @Test
    fun aPausedTripStillNeverReadsAsALoss() {
        assertEquals(ActiveTripSignal.OK, activeTripSignal(isPaused = true, pointCount = 5, openGapReason = null, approximateOnly = true))
    }
}
