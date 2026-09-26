package com.mototriptracker.app.feature.active

import com.mototriptracker.app.tracking.persistence.PersistenceLevel
import com.mototriptracker.app.tracking.persistence.PersistenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** REC-006 / F0.10 §14: what the Active Trip screen says when the recording cannot save. */
class PersistenceNoticeTest {

    @Test
    fun aHealthyRecordingHasNothingToSay() {
        assertNull(persistenceNotice(PersistenceState.HEALTHY))
    }

    @Test
    fun aDegradedRecordingSaysPointsAreHeldAndWillBeSaved() {
        val notice = persistenceNotice(PersistenceState(PersistenceLevel.DEGRADED))
        assertTrue(notice!!.contains("held in memory"))
    }

    @Test
    fun fullStorageTellsTheRiderWhatTheyCanDoAboutIt() {
        val notice = persistenceNotice(PersistenceState(PersistenceLevel.CRITICAL, storageFull = true))
        assertTrue(notice!!.contains("Free up space"))
    }

    @Test
    fun aLossWithoutFullStorageSaysPointsWereLostAndWillShowAsAGap() {
        val notice = persistenceNotice(PersistenceState(PersistenceLevel.CRITICAL, storageFull = false))
        assertTrue(notice!!.contains("lost"))
        assertNotEquals(persistenceNotice(PersistenceState(PersistenceLevel.CRITICAL, storageFull = true)), notice)
    }

    @Test
    fun theStateCarriesNoCountersThatWouldChangeOnEveryFix() {
        // Equal states are what let the notification refresh only on real changes.
        assertEquals(PersistenceState(PersistenceLevel.DEGRADED), PersistenceState(PersistenceLevel.DEGRADED))
    }
}
