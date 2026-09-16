package com.mototriptracker.app.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IdGeneratorTest {

    @Test
    fun fakeIdGeneratorIsDeterministicAndSequential() {
        val ids = FakeIdGenerator(prefix = "capture")
        assertEquals("capture-1", ids.newId())
        assertEquals("capture-2", ids.newId())
    }

    @Test
    fun uuidIdGeneratorProducesDistinctIds() {
        val ids = UuidIdGenerator()
        assertNotEquals(ids.newId(), ids.newId())
    }
}
