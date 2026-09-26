package com.mototriptracker.app.feature.active

import com.mototriptracker.app.tracking.persistence.PersistenceLevel
import com.mototriptracker.app.tracking.persistence.PersistenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun fullStorageWithNothingLostYetSaysPointsAreHeldAndWillBeLostAndWhatToDo() {
        val notice = persistenceNotice(PersistenceState(PersistenceLevel.CRITICAL, storageFull = true, pointsLost = false))
        assertTrue(notice!!.contains("held in memory"))
        assertTrue(notice.contains("free up space"))
        assertFalse("nothing is lost yet - do not say it is", notice.contains("are lost"))
    }

    @Test
    fun fullStorageWithPointsAlreadyLostSaysSoAndWhatToDo() {
        val notice = persistenceNotice(PersistenceState(PersistenceLevel.CRITICAL, storageFull = true, pointsLost = true))
        assertTrue(notice!!.contains("some points are lost"))
        assertTrue(notice.contains("Free up space"))
    }

    @Test
    fun aLossWithoutFullStorageSaysPointsWereLostAndWillShowAsAGap() {
        val notice = persistenceNotice(PersistenceState(PersistenceLevel.CRITICAL, storageFull = false, pointsLost = true))
        assertTrue(notice!!.contains("lost"))
        assertTrue(notice.contains("gap"))
    }

    @Test
    fun theStateCarriesNoCountersThatWouldChangeOnEveryFix() {
        // Equal states are what let the notification refresh only on real changes.
        assertEquals(PersistenceState(PersistenceLevel.DEGRADED), PersistenceState(PersistenceLevel.DEGRADED))
    }
}
