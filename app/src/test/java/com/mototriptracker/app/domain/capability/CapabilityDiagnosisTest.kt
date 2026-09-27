package com.mototriptracker.app.domain.capability

import com.mototriptracker.app.core.model.CapabilityInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PERM-003: the diagnosis says *why* the app is degraded, most important first, and only about what a manual trip needs. */
class CapabilityDiagnosisTest {

    private val allGood = CapabilityInputs(
        preciseLocationGranted = true, approximateLocationGranted = true, activityRecognitionGranted = true,
        backgroundLocationGranted = true, notificationsEnabled = true, locationServicesEnabled = true, autoTrackingEnabledByUser = true
    )

    private fun issues(change: (CapabilityInputs) -> CapabilityInputs) = CapabilityDiagnosis.issuesFor(change(allGood))

    @Test
    fun whenEverythingIsFineThereIsNothingToSay() {
        assertEquals(emptyList<CapabilityIssue>(), CapabilityDiagnosis.issuesFor(allGood))
    }

    @Test
    fun locationServicesOffIsReportedOnItsOwn() {
        assertEquals(listOf(CapabilityIssue.LOCATION_SERVICES_OFF), issues { it.copy(locationServicesEnabled = false) })
    }

    @Test
    fun approximateOnlyIsReportedAsPreciseLocationMissing() {
        assertEquals(
            listOf(CapabilityIssue.PRECISE_LOCATION_MISSING),
            issues { it.copy(preciseLocationGranted = false, approximateLocationGranted = true) }
        )
    }

    @Test
    fun noLocationPermissionAtAllIsOneProblemNotTwo() {
        val found = issues { it.copy(preciseLocationGranted = false, approximateLocationGranted = false) }

        assertEquals("the bigger problem only - not also 'precise missing'", listOf(CapabilityIssue.LOCATION_PERMISSION_MISSING), found)
    }

    @Test
    fun deniedNotificationsAreReportedButDoNotBlockTheRoute() {
        val found = issues { it.copy(notificationsEnabled = false) }

        assertEquals(listOf(CapabilityIssue.NOTIFICATIONS_DENIED), found)
        assertFalse(CapabilityIssue.NOTIFICATIONS_DENIED.blocksRoute)
    }

    @Test
    fun everyLocationProblemBlocksTheRoute() {
        listOf(CapabilityIssue.LOCATION_PERMISSION_MISSING, CapabilityIssue.LOCATION_SERVICES_OFF, CapabilityIssue.PRECISE_LOCATION_MISSING)
            .forEach { assertTrue(it.name, it.blocksRoute) }
    }

    @Test
    fun severalProblemsComeMostImportantFirstSoOneClearActionCanLead() {
        val found = issues {
            it.copy(
                preciseLocationGranted = false, approximateLocationGranted = false,
                locationServicesEnabled = false, notificationsEnabled = false
            )
        }

        assertEquals(
            listOf(CapabilityIssue.LOCATION_PERMISSION_MISSING, CapabilityIssue.LOCATION_SERVICES_OFF, CapabilityIssue.NOTIFICATIONS_DENIED),
            found
        )
    }

    @Test
    fun approximateOnlyOutranksDeniedNotifications() {
        val found = issues { it.copy(preciseLocationGranted = false, notificationsEnabled = false) }

        assertEquals(listOf(CapabilityIssue.PRECISE_LOCATION_MISSING, CapabilityIssue.NOTIFICATIONS_DENIED), found)
    }

    /** Nothing in the app can turn Auto Tracking on yet: a missing background/AR permission is not this screen's business (PERM-002). */
    @Test
    fun permissionsOnlyAutoTrackingNeedsAreNeverListed() {
        val found = issues { it.copy(backgroundLocationGranted = false, activityRecognitionGranted = false, autoTrackingEnabledByUser = false) }

        assertEquals(emptyList<CapabilityIssue>(), found)
    }

    @Test
    fun theDiagnosisIsIndependentOfTheAutoTrackingToggle() {
        val on = CapabilityDiagnosis.issuesFor(allGood.copy(notificationsEnabled = false, autoTrackingEnabledByUser = true))
        val off = CapabilityDiagnosis.issuesFor(allGood.copy(notificationsEnabled = false, autoTrackingEnabledByUser = false))

        assertEquals(on, off)
    }
}
