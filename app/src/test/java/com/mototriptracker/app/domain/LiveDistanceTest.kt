package com.mototriptracker.app.domain

import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** The live figure on the notification and Active Trip screen. */
class LiveDistanceTest {

    private fun point(seq: Long, latitude: Double, approximate: Boolean? = null) = RawTrackPointEntity(
        captureId = "capture-1", sequenceNumber = seq, capturedAt = seq * 1_000, elapsedRealtimeNanos = seq * 1_000_000_000L,
        receivedAtElapsedRealtimeNanos = null, latitude = latitude, longitude = -20.0, horizontalAccuracyM = 5f,
        altitudeEllipsoidM = null, altitudeMslM = null, verticalAccuracyM = null, speedMps = null, speedAccuracyMps = null,
        bearingDeg = null, bearingAccuracyDeg = null, provider = "fused", isMock = false, requestProfileId = "test",
        callbackBatchId = null, detectorStateSnapshot = "TRACKING", isApproximateLocation = approximate
    )

    @Test
    fun sumsTheStraightLegsBetweenPreciseFixes() {
        val meters = liveDistanceMeters(listOf(point(0, 10.000), point(1, 10.001)))

        assertEquals(111.0, meters, 1.0)
    }

    /** Found on the phone: a stationary phone showed 1,0 km, then 1,9 km, after losing precise location. */
    @Test
    fun approximateOnlyFixesAddNoDistanceEvenWhenTheyJumpAKilometre() {
        val meters = liveDistanceMeters(
            listOf(point(0, 10.000), point(1, 10.008, approximate = true), point(2, 10.000, approximate = true), point(3, 10.000))
        )

        assertEquals("the jumps between approximate blocks are not travel", 0.0, meters, 0.001)
    }

    @Test
    fun aPointWithAnUnknownMarkerCountsLikeBefore() {
        val meters = liveDistanceMeters(listOf(point(0, 10.000, approximate = null), point(1, 10.001, approximate = null)))

        assertEquals(111.0, meters, 1.0)
    }
}
