package com.mototriptracker.app.feature.common

import com.mototriptracker.app.domain.capability.CapabilityIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PERM-003: what the person reads for each problem, and when a "no" is respected versus when Settings opens. */
class CapabilityIssueCopyTest {

    @Test
    fun everyProblemHasATitleAMessageAndOneActionLabel() {
        CapabilityIssue.entries.forEach { issue ->
            val copy = CapabilityIssueCopy.of(issue)
            assertTrue("$issue title", copy.title.isNotBlank())
            assertTrue("$issue message", copy.message.isNotBlank())
            assertTrue("$issue action", copy.actionLabel.isNotBlank())
        }
    }

    @Test
    fun theTitlesNameTheCauseNotAGenericDegraded() {
        val titles = CapabilityIssue.entries.map { CapabilityIssueCopy.of(it).title }

        assertEquals("each problem reads differently", titles.size, titles.toSet().size)
        assertFalse(titles.any { it.contains("degraded", ignoreCase = true) })
        assertEquals("Precise location needed", CapabilityIssueCopy.of(CapabilityIssue.PRECISE_LOCATION_MISSING).title)
    }

    @Test
    fun theStartAnywayWarningExistsOnlyWhereStartingWouldRecordNoRoute() {
        assertNotNull(CapabilityIssueCopy.of(CapabilityIssue.LOCATION_SERVICES_OFF).startAnywayMessage)
        assertNotNull(CapabilityIssueCopy.of(CapabilityIssue.PRECISE_LOCATION_MISSING).startAnywayMessage)
        assertNull("Start itself asks for the permission", CapabilityIssueCopy.of(CapabilityIssue.LOCATION_PERMISSION_MISSING).startAnywayMessage)
        assertNull("notifications never get in the way of Start", CapabilityIssueCopy.of(CapabilityIssue.NOTIFICATIONS_DENIED).startAnywayMessage)
    }

    @Test
    fun aWarningBeforeStartingSaysPlainlyThatNoRouteWillBeRecorded() {
        listOf(CapabilityIssue.LOCATION_SERVICES_OFF, CapabilityIssue.PRECISE_LOCATION_MISSING).forEach { issue ->
            val message = CapabilityIssueCopy.of(issue).startAnywayMessage.orEmpty()
            assertTrue("$issue: $message", message.contains("will not record a route"))
            assertTrue("$issue offers to start anyway", message.contains("start anyway"))
        }
    }

    @Test
    fun aStartWarningExistsExactlyForTheProblemsFlaggedToWarnBeforeStart() {
        CapabilityIssue.entries.forEach { issue ->
            val hasWarning = CapabilityIssueCopy.of(issue).startAnywayMessage != null
            assertEquals("$issue", issue.warnBeforeStart, hasWarning)
            if (issue.warnBeforeStart) assertTrue("$issue warns but does not block the route", issue.blocksRoute)
        }
    }

    @Test
    fun aGrantedPermissionNeverOpensSettings() {
        assertFalse(shouldOpenSettingsAfterDenial(granted = true, answeredInMs = 5))
        assertFalse(shouldOpenSettingsAfterDenial(granted = true, answeredInMs = 10_000))
    }

    /** §19.4 "respetar `Ahora no`/denegación": someone who took a second to tap "Don't allow" is not sent to Settings. */
    @Test
    fun aDenialAPersonGaveIsRespectedAndDoesNotOpenSettings() {
        assertFalse(shouldOpenSettingsAfterDenial(granted = false, answeredInMs = 1_200))
        assertFalse(shouldOpenSettingsAfterDenial(granted = false, answeredInMs = SYSTEM_ANSWER_MS))
    }

    /** When the system will not ask any more, the button must not look dead: Settings is the only way left to say yes. */
    @Test
    fun anAnswerTooFastForAPersonMeansTheSystemDidNotAskAndSettingsOpens() {
        assertTrue(shouldOpenSettingsAfterDenial(granted = false, answeredInMs = 40))
        assertTrue(shouldOpenSettingsAfterDenial(granted = false, answeredInMs = SYSTEM_ANSWER_MS - 1))
    }

    @Test
    fun theThresholdIsBelowAnyHumanReactionTime() {
        assertNotEquals(0L, SYSTEM_ANSWER_MS)
        assertTrue(SYSTEM_ANSWER_MS < 500L)
    }

    private fun assertNotNull(value: Any?) = org.junit.Assert.assertNotNull(value)
}
