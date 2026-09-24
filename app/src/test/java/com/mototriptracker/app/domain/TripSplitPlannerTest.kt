package com.mototriptracker.app.domain

import com.mototriptracker.app.core.database.entity.TripPartEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TripSplitPlannerTest {

    private fun part(
        captureId: String,
        orderIndex: Int = 0,
        startNanos: Long = 0L,
        endNanos: Long = 100_000_000_000L,
        startSeq: Long? = null,
        endSeq: Long? = null
    ) = TripPartEntity(
        id = "part-$captureId-$orderIndex", tripId = "trip", captureId = captureId, orderIndex = orderIndex,
        startElapsedRealtimeNanos = startNanos, endElapsedRealtimeNanos = endNanos,
        startSequenceNumber = startSeq, endSequenceNumber = endSeq
    )

    private val sequences = (0L..9L).toList()

    @Test
    fun cuttingInsideASinglePartSplitsItIntoTwoNonOverlappingRanges() {
        val plan = TripSplitPlanner.plan(
            parts = listOf(part("cap")),
            cut = TripSplitPlanner.Cut("cap", sequenceNumber = 4, elapsedRealtimeNanos = 40_000_000_000L),
            captureSequences = sequences
        )!!

        val first = plan.first.single()
        val second = plan.second.single()
        assertEquals(null, first.startSequenceNumber)
        assertEquals("first half stops one point before the cut", 3L, first.endSequenceNumber)
        assertEquals(40_000_000_000L, first.endElapsedRealtimeNanos)
        assertEquals("second half starts exactly at the cut", 4L, second.startSequenceNumber)
        assertEquals(null, second.endSequenceNumber)
        assertEquals(40_000_000_000L, second.startElapsedRealtimeNanos)
        assertEquals(100_000_000_000L, second.endElapsedRealtimeNanos)
    }

    @Test
    fun theTwoHalvesDurationsAddUpToTheOriginals() {
        val original = part("cap", startNanos = 5_000_000_000L, endNanos = 95_000_000_000L)

        val plan = TripSplitPlanner.plan(listOf(original), TripSplitPlanner.Cut("cap", 6, 61_000_000_000L), sequences)!!

        val total = (plan.first + plan.second).sumOf { it.endElapsedRealtimeNanos!! - it.startElapsedRealtimeNanos }
        assertEquals(original.endElapsedRealtimeNanos!! - original.startElapsedRealtimeNanos, total)
    }

    @Test
    fun aMultiPartTripSendsEarlierPartsToTheFirstHalfAndLaterPartsToTheSecond() {
        val parts = listOf(part("cap-a", 0), part("cap-b", 1), part("cap-c", 2))

        val plan = TripSplitPlanner.plan(parts, TripSplitPlanner.Cut("cap-b", 5, 50_000_000_000L), sequences)!!

        assertEquals(listOf("cap-a", "cap-b"), plan.first.map { it.captureId })
        assertEquals(listOf("cap-b", "cap-c"), plan.second.map { it.captureId })
        assertEquals("cap-b's slice is cut, cap-a/cap-c pass through untouched", parts[0], plan.first[0])
        assertEquals(parts[2], plan.second[1])
    }

    @Test
    fun cuttingAtTheVeryFirstPointOfALaterPartMovesTheWholePartToTheSecondHalf() {
        val parts = listOf(part("cap-a", 0), part("cap-b", 1))

        val plan = TripSplitPlanner.plan(parts, TripSplitPlanner.Cut("cap-b", sequenceNumber = 0, elapsedRealtimeNanos = 1_000_000_000L), sequences)!!

        assertEquals("no empty leftover slice of cap-b in the first half", listOf("cap-a"), plan.first.map { it.captureId })
        assertEquals(listOf(parts[1]), plan.second)
    }

    @Test
    fun aCutInsideANarrowedPartRespectsItsExistingSequenceBounds() {
        // e.g. the second half of an earlier split: this part only covers 4..9.
        val narrowed = part("cap", startSeq = 4, endSeq = 9, startNanos = 40_000_000_000L)

        val plan = TripSplitPlanner.plan(listOf(narrowed), TripSplitPlanner.Cut("cap", 7, 70_000_000_000L), sequences)!!

        assertEquals(4L, plan.first.single().startSequenceNumber)
        assertEquals(6L, plan.first.single().endSequenceNumber)
        assertEquals(7L, plan.second.single().startSequenceNumber)
        assertEquals(9L, plan.second.single().endSequenceNumber)
    }

    @Test
    fun cuttingAtTheFirstPointOfANarrowedPartIsRecognisedEvenThoughItsSequenceIsNotZero() {
        val narrowed = part("cap", startSeq = 4, endSeq = 9)
        val other = part("cap-x", 0)

        val plan = TripSplitPlanner.plan(listOf(other, narrowed.copy(orderIndex = 1)), TripSplitPlanner.Cut("cap", 4, 40_000_000_000L), sequences)!!

        assertEquals(listOf("cap-x"), plan.first.map { it.captureId })
    }

    @Test
    fun aCutOutsideEveryPartIsRejected() {
        assertNull(TripSplitPlanner.plan(listOf(part("cap", startSeq = 0, endSeq = 3)), TripSplitPlanner.Cut("cap", 7, 1L), sequences))
        assertNull(TripSplitPlanner.plan(listOf(part("cap")), TripSplitPlanner.Cut("other-capture", 2, 1L), sequences))
    }

    @Test
    fun aCutThatWouldLeaveTheFirstHalfEmptyIsRejected() {
        // Very first point of the very first part: nothing would remain before it.
        assertNull(TripSplitPlanner.plan(listOf(part("cap")), TripSplitPlanner.Cut("cap", 0, 0L), sequences))
    }

    @Test
    fun theSecondHalfAlwaysContainsTheCutPointItself() {
        val plan = TripSplitPlanner.plan(listOf(part("cap")), TripSplitPlanner.Cut("cap", 9, 90_000_000_000L), sequences)
        assertNotNull(plan)

        assertEquals(9L, plan!!.second.single().startSequenceNumber)
    }
}
