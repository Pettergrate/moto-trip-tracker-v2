package com.mototriptracker.app.domain.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** REC-006 / §14.2: bounded, ordered, and never silent about what it dropped. */
class BoundedWriteBufferTest {

    @Test
    fun itemsLeaveInTheOrderTheyWentIn() {
        val buffer = BoundedWriteBuffer<Int>(capacity = 5)
        listOf(1, 2, 3).forEach { buffer.offer(it) }

        assertEquals(1, buffer.removeFirst())
        assertEquals(2, buffer.peek())
        assertEquals(2, buffer.removeFirst())
        assertEquals(3, buffer.removeFirst())
        assertTrue(buffer.isEmpty)
    }

    @Test
    fun aFullBufferDropsTheOldestAndCountsIt() {
        val buffer = BoundedWriteBuffer<Int>(capacity = 3)
        listOf(1, 2, 3).forEach { assertNull(buffer.offer(it)) }

        assertEquals("the displaced item is handed back so the caller can record what was lost", 1, buffer.offer(4))

        assertEquals(3, buffer.size)
        assertEquals(1, buffer.droppedTotal)
        assertEquals(listOf(2, 3, 4), generateSequence { if (buffer.isEmpty) null else buffer.removeFirst() }.toList())
    }

    @Test
    fun theSizeNeverExceedsTheCapacityNoMatterHowMuchIsOffered() {
        val buffer = BoundedWriteBuffer<Int>(capacity = 10)

        repeat(10_000) { buffer.offer(it) }

        assertEquals(10, buffer.size)
        assertEquals(9_990, buffer.droppedTotal)
        assertEquals(10, buffer.highWaterMark)
    }

    @Test
    fun theHighWaterMarkSurvivesTheBufferDraining() {
        val buffer = BoundedWriteBuffer<Int>(capacity = 10)
        repeat(7) { buffer.offer(it) }
        repeat(7) { buffer.removeFirst() }

        assertEquals(7, buffer.highWaterMark)
        assertEquals(0, buffer.droppedTotal)
    }

    @Test
    fun discardingAllReportsHowManyWereLostAndLeavesTheOverflowCounterAlone() {
        val buffer = BoundedWriteBuffer<Int>(capacity = 10)
        repeat(4) { buffer.offer(it) }

        assertEquals(4, buffer.discardAll())

        assertTrue(buffer.isEmpty)
        assertEquals("discardAll is a loss for the caller to record, not an overflow", 0, buffer.droppedTotal)
    }

    @Test
    fun aBufferOfZeroCapacityIsRejected() {
        val result = runCatching { BoundedWriteBuffer<Int>(capacity = 0) }
        assertFalse(result.isSuccess)
    }
}
