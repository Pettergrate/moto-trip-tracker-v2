package com.mototriptracker.app.tracking.location

import com.mototriptracker.app.experiment.ExperimentLocationProfiles
import org.junit.Assert.assertEquals
import org.junit.Test

class InMemoryLocationProfileSelectorTest {

    @Test
    fun defaultsToTheProductionProfileBeforeAnythingIsSelected() {
        val selector = InMemoryLocationProfileSelector()
        assertEquals(ExperimentLocationProfiles.DEFAULT, selector.current())
    }

    @Test
    fun selectingAProfileMakesItTheCurrentOne() {
        val selector = InMemoryLocationProfileSelector()
        selector.select(ExperimentLocationProfiles.S1_A)
        assertEquals(ExperimentLocationProfiles.S1_A, selector.current())
    }

    @Test
    fun selectingNullResetsToTheDefaultProfile() {
        val selector = InMemoryLocationProfileSelector()
        selector.select(ExperimentLocationProfiles.S1_C)

        selector.select(null)

        assertEquals(ExperimentLocationProfiles.DEFAULT, selector.current())
    }
}
