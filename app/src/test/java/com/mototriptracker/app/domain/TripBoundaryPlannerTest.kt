package com.mototriptracker.app.domain

import com.mototriptracker.app.core.database.entity.TripPartEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripBoundaryPlannerTest {

    private fun part(captureId: String, orderIndex: Int = 0, startSeq: Long? = null, endSeq: Long? = null) = TripPartEntity(
        id = "part-$captureId-$orderIndex", tripId = "trip", captureId = captureId, orderIndex = orderIndex,
        startElapsedRealtimeNanos = 0L, endElapsedRealtimeNanos = 100_000_000_000L,
        startSequenceNumber = startSeq, endSequenceNumber = endSeq
    )

    private fun bound(captureId: String, seq: Long) = TripBoundaryPlanner.Bound(captureId, seq, seq * 10_000_000_000L)

    @Test
    fun trimmingBothEndsOfASinglePartNarrowsItsRangeAndElapsedBounds() {
        val plan = TripBoundaryPlanner.plan(listOf(part("cap")), bound("cap", 2), bound("cap", 7))!!.single()

        assertEquals(2L, plan.startSequenceNumber)
        assertEquals(7L, plan.endSequenceNumber)
        assertEquals(20_000_000_000L, plan.startElapsedRealtimeNanos)
        assertEquals(70_000_000_000L, plan.endElapsedRealtimeNanos)
    }

    @Test
    fun aMultiPartTripDropsPartsOutsideTheBoundsAndOnlyAdjustsTheOuterTwo() {
        val parts = listOf(part("a", 0), part("b", 1), part("c", 2), part("d", 3))

        val plan = TripBoundaryPlanner.plan(parts, bound("b", 3), bound("c", 8))!!

        assertEquals(listOf("b", "c"), plan.map { it.captureId })
        assertEquals(3L, plan[0].startSequenceNumber)
        assertEquals("b keeps its own end - only the trip's overall end moves", null, plan[0].endSequenceNumber)
        assertEquals(null, plan[1].startSequenceNumber)
        assertEquals(8L, plan[1].endSequenceNumber)
    }

    @Test
    fun anUntouchedSideKeepsTheSourcesOwnEdgeInsteadOfSnappingToTheFirstOrLastPoint() {
        val onlyStartMoved = TripBoundaryPlanner.plan(listOf(part("cap")), bound("cap", 3), null)!!.single()
        assertEquals(3L, onlyStartMoved.startSequenceNumber)
        assertEquals(null, onlyStartMoved.endSequenceNumber)
        assertEquals(100_000_000_000L, onlyStartMoved.endElapsedRealtimeNanos)

        val onlyEndMoved = TripBoundaryPlanner.plan(listOf(part("cap")), null, bound("cap", 6))!!.single()
        assertEquals(null, onlyEndMoved.startSequenceNumber)
        assertEquals(0L, onlyEndMoved.startElapsedRealtimeNanos)
        assertEquals(6L, onlyEndMoved.endSequenceNumber)
    }

    @Test
    fun aNarrowedPartIsNarrowedFurtherNeverWidened() {
        val plan = TripBoundaryPlanner.plan(listOf(part("cap", startSeq = 4, endSeq = 9)), bound("cap", 5), bound("cap", 8))!!.single()

        assertEquals(5L, plan.startSequenceNumber)
        assertEquals(8L, plan.endSequenceNumber)
    }

    @Test
    fun boundsOutOfOrderOrOutsideEveryPartAreRejected() {
        val parts = listOf(part("a", 0), part("b", 1))
        assertNull("end before start within one part", TripBoundaryPlanner.plan(listOf(part("cap")), bound("cap", 7), bound("cap", 2)))
        assertNull("start in a later part than the end", TripBoundaryPlanner.plan(parts, bound("b", 1), bound("a", 5)))
        assertNull("unknown capture", TripBoundaryPlanner.plan(parts, bound("zzz", 1), bound("b", 5)))
        assertNull("outside a narrowed range", TripBoundaryPlanner.plan(listOf(part("cap", startSeq = 4, endSeq = 9)), bound("cap", 1), bound("cap", 6)))
    }
}
