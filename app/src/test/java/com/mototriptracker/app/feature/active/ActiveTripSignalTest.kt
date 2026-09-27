package com.mototriptracker.app.feature.active

import com.mototriptracker.app.domain.capability.CapabilityIssue
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

    /** Found on the phone: a trip started with Location off said "Waiting for the first GPS fix" for 11 minutes. */
    @Test
    fun noPointsYetWithLocationOffSaysSoInsteadOfWaitingForAFixThatCannotCome() {
        assertEquals(
            ActiveTripSignal.SEARCHING_LOCATION_OFF,
            activeTripSignal(isPaused = false, pointCount = 0, openGapReason = null, locationServicesOff = true)
        )
    }

    @Test
    fun locationOffOnlyChangesTheWaitBeforeTheFirstFixNotARecordingThatAlreadyHasPoints() {
        // Once points exist, an outage is reported by the gap the watch opens (with its own reason), never guessed here.
        assertEquals(ActiveTripSignal.OK, activeTripSignal(isPaused = false, pointCount = 42, openGapReason = null, locationServicesOff = true))
    }

    @Test
    fun aPausedTripWithLocationOffIsStillNotALoss() {
        assertEquals(ActiveTripSignal.OK, activeTripSignal(isPaused = true, pointCount = 0, openGapReason = null, locationServicesOff = true))
    }

    @Test
    fun theWaitBeforeTheFirstFixDoesNotPromiseAGapBecauseNoneIsMarkedYet() {
        val notice = signalNotice(ActiveTripSignal.SEARCHING_LOCATION_OFF).orEmpty()
        assertEquals(notice, false, notice.contains("gap", ignoreCase = true))
        assertEquals(notice, true, notice.contains("Location is turned off"))
    }

    @Test
    fun theFixIsOfferedOnlyWhereTheRiderCanDoSomethingAboutTheCause() {
        assertEquals(CapabilityIssue.LOCATION_SERVICES_OFF, fixFor(ActiveTripSignal.SEARCHING_LOCATION_OFF))
        assertEquals(CapabilityIssue.LOCATION_SERVICES_OFF, fixFor(ActiveTripSignal.LOST_LOCATION_SERVICES_OFF))
        assertEquals(CapabilityIssue.PRECISE_LOCATION_MISSING, fixFor(ActiveTripSignal.APPROXIMATE_ONLY))
        assertNull("nothing to change for a tunnel", fixFor(ActiveTripSignal.LOST_NO_FIX))
        assertNull(fixFor(ActiveTripSignal.SEARCHING))
        assertNull(fixFor(ActiveTripSignal.OK))
    }
}
