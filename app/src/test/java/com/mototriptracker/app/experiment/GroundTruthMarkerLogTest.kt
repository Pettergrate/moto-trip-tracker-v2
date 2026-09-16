package com.mototriptracker.app.experiment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundTruthMarkerLogTest {

    @Test
    fun recordsMarkersInOrder() {
        val log = GroundTruthMarkerLog()
        log.record(GroundTruthMarker(GroundTruthMarkerType.READY_TO_START, 1_000L, 1_000L))
        log.record(GroundTruthMarker(GroundTruthMarkerType.GT_START, 2_000L, 2_000L))

        assertEquals(
            listOf(GroundTruthMarkerType.READY_TO_START, GroundTruthMarkerType.GT_START),
            log.markers.map { it.type }
        )
    }

    @Test
    fun clearEmptiesTheLog() {
        val log = GroundTruthMarkerLog()
        log.record(GroundTruthMarker(GroundTruthMarkerType.ARRIVED, 1_000L, 1_000L))

        log.clear()

        assertTrue(log.markers.isEmpty())
    }
}
