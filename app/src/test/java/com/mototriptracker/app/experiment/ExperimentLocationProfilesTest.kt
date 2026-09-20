package com.mototriptracker.app.experiment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExperimentLocationProfilesTest {

    @Test
    fun findByIdReturnsTheMatchingRealProfile() {
        assertEquals(ExperimentLocationProfiles.DEFAULT, ExperimentLocationProfiles.findById("tracking-manual-v0"))
        assertEquals(ExperimentLocationProfiles.S1_A, ExperimentLocationProfiles.findById("S1-A"))
        assertEquals(ExperimentLocationProfiles.S1_B, ExperimentLocationProfiles.findById("S1-B"))
        assertEquals(ExperimentLocationProfiles.S1_C, ExperimentLocationProfiles.findById("S1-C"))
    }

    @Test
    fun findByIdReturnsNullForAnUnknownOrMistypedId() {
        assertNull(ExperimentLocationProfiles.findById("s1-a"))
        assertNull(ExperimentLocationProfiles.findById("S2-A"))
        assertNull(ExperimentLocationProfiles.findById("whatever-the-tester-typed"))
    }

    @Test
    fun campaignS1KeepsMinDistanceZeroAndBatchingOffPerF06Section10() {
        listOf(ExperimentLocationProfiles.S1_A, ExperimentLocationProfiles.S1_B, ExperimentLocationProfiles.S1_C).forEach {
            assertEquals(0f, it.minUpdateDistanceMeters)
            assertEquals(0L, it.maxUpdateDelayMillis)
        }
    }
}
