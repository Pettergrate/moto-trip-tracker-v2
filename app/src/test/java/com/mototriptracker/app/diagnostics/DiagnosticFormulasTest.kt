package com.mototriptracker.app.diagnostics

import com.mototriptracker.app.tracking.persistence.PersistenceLevel
import com.mototriptracker.app.tracking.persistence.PersistenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DIA-002: the small rules behind the debug snapshot. */
class DiagnosticFormulasTest {

    private fun ms(millis: Long) = millis * 1_000_000L

    @Test
    fun theShortIdIsEightHexDigitsStableAndDoesNotDiscloseTheRealId() {
        val id = "3f2a91c4-77aa-4b6e-9d21-0c5e8e1d9b10"

        val short = DiagnosticFormulas.shortId(id)

        assertEquals(8, short.length)
        assertTrue(short.all { it in "0123456789abcdef" })
        assertEquals("same input, same label - so it can be correlated", short, DiagnosticFormulas.shortId(id))
        assertFalse(id.contains(short) && short.length > 8)
        assertNotEquals(short, DiagnosticFormulas.shortId("3f2a91c4-77aa-4b6e-9d21-0c5e8e1d9b11"))
    }

    @Test
    fun theEffectiveIntervalIsTheMedianGapNotTheMean() {
        // 2 s, 2 s, 2 s and then one 60 s silence: the mean would say ~17 s, the interval is 2 s.
        val stamps = listOf(0L, 2_000, 4_000, 6_000, 66_000).map(::ms)

        assertEquals(2_000L, DiagnosticFormulas.effectiveIntervalMs(stamps))
    }

    @Test
    fun anEvenNumberOfGapsAveragesTheTwoMiddleOnes() {
        val stamps = listOf(0L, 1_000, 4_000).map(::ms) // gaps 1000 and 3000

        assertEquals(2_000L, DiagnosticFormulas.effectiveIntervalMs(stamps))
    }

    @Test
    fun theOrderTheStampsComeInDoesNotMatter() {
        val newestFirst = listOf(6_000L, 4_000, 2_000, 0).map(::ms) // how the DAO returns them

        assertEquals(2_000L, DiagnosticFormulas.effectiveIntervalMs(newestFirst))
    }

    @Test
    fun fewerThanTwoPointsOrOnlyRepeatedStampsGiveNoIntervalRatherThanAGuess() {
        assertNull(DiagnosticFormulas.effectiveIntervalMs(emptyList()))
        assertNull(DiagnosticFormulas.effectiveIntervalMs(listOf(ms(5))))
        assertNull(DiagnosticFormulas.effectiveIntervalMs(listOf(ms(5), ms(5), ms(5))))
    }

    private val healthy = PersistenceState.HEALTHY

    @Test
    fun aQuietHealthyRecordingIsHealthy() {
        assertEquals(HealthState.HEALTHY, DiagnosticFormulas.health(healthy, gapActive = false, approximateOnly = false, recoveryRequired = false))
    }

    @Test
    fun aGapOrApproximateOnlyOrSavingLateIsDegraded() {
        assertEquals(HealthState.DEGRADED, DiagnosticFormulas.health(healthy, gapActive = true, approximateOnly = false, recoveryRequired = false))
        assertEquals(HealthState.DEGRADED, DiagnosticFormulas.health(healthy, gapActive = false, approximateOnly = true, recoveryRequired = false))
        assertEquals(
            HealthState.DEGRADED,
            DiagnosticFormulas.health(PersistenceState(PersistenceLevel.DEGRADED), gapActive = false, approximateOnly = false, recoveryRequired = false)
        )
    }

    @Test
    fun persistenceTroubleThatLosesDataOutranksEverythingElse() {
        val critical = PersistenceState(PersistenceLevel.CRITICAL, storageFull = true)

        assertEquals(
            HealthState.PERSISTENCE_CRITICAL,
            DiagnosticFormulas.health(critical, gapActive = true, approximateOnly = true, recoveryRequired = true)
        )
    }

    @Test
    fun anOrphanedCaptureIsRecoveryRequiredAheadOfAMereGap() {
        assertEquals(HealthState.RECOVERY_REQUIRED, DiagnosticFormulas.health(healthy, gapActive = true, approximateOnly = false, recoveryRequired = true))
    }
}
