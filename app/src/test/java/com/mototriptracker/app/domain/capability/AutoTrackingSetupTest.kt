package com.mototriptracker.app.domain.capability

import com.mototriptracker.app.core.model.CapabilityInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PERM-002: which permission the guided setup asks for next - in the document's order, never twice, never beyond what makes sense. */
class AutoTrackingSetupTest {

    private val everything = CapabilityInputs(
        preciseLocationGranted = true, approximateLocationGranted = true, activityRecognitionGranted = true,
        backgroundLocationGranted = true, notificationsEnabled = true, locationServicesEnabled = true, autoTrackingEnabledByUser = true
    )
    private val nothing = everything.copy(
        preciseLocationGranted = false, approximateLocationGranted = false, activityRecognitionGranted = false,
        backgroundLocationGranted = false, notificationsEnabled = false
    )

    private fun next(inputs: CapabilityInputs, sdk: Int = 36, attempted: Set<SetupStep> = emptySet()) =
        AutoTrackingSetup.nextStep(AutoTrackingReadiness.requirementsFor(inputs), sdk, attempted)

    @Test
    fun withEverythingGrantedThereIsNothingToAsk() {
        assertNull(next(everything))
    }

    @Test
    fun theStepsComeInTheOrderOfTheDocument() {
        assertEquals(SetupStep.ACTIVITY_RECOGNITION, next(nothing))
        assertEquals(SetupStep.PRECISE_LOCATION, next(nothing.copy(activityRecognitionGranted = true)))
        assertEquals(SetupStep.NOTIFICATIONS, next(nothing.copy(activityRecognitionGranted = true, preciseLocationGranted = true)))
        assertEquals(
            SetupStep.BACKGROUND_LOCATION,
            next(nothing.copy(activityRecognitionGranted = true, preciseLocationGranted = true, notificationsEnabled = true))
        )
    }

    @Test
    fun onlyWhatIsMissingIsAsked() {
        assertEquals(SetupStep.BACKGROUND_LOCATION, next(everything.copy(backgroundLocationGranted = false)))
        assertEquals(SetupStep.NOTIFICATIONS, next(everything.copy(notificationsEnabled = false)))
        assertEquals(SetupStep.ACTIVITY_RECOGNITION, next(everything.copy(activityRecognitionGranted = false)))
    }

    @Test
    fun aStepAlreadyAskedInThisRunIsNotAskedAgain() {
        assertNull(next(nothing, attempted = setOf(SetupStep.ACTIVITY_RECOGNITION)))
    }

    /** Declining the activity permission ends the run: background location would be requested for a feature that cannot work. */
    @Test
    fun afterANoToActivityRecognitionNothingElseIsAsked() {
        val inputs = everything.copy(activityRecognitionGranted = false, backgroundLocationGranted = false, notificationsEnabled = false)

        assertNull(next(inputs, attempted = setOf(SetupStep.ACTIVITY_RECOGNITION)))
    }

    @Test
    fun afterANoToPreciseLocationNothingElseIsAsked() {
        val inputs = everything.copy(preciseLocationGranted = false, backgroundLocationGranted = false)

        assertNull(next(inputs, attempted = setOf(SetupStep.PRECISE_LOCATION)))
    }

    /** With notifications still off, hands-free is out of reach: the most sensitive permission is not asked for nothing. */
    @Test
    fun backgroundLocationIsNotAskedWhileNotificationsAreStillOff() {
        val inputs = everything.copy(notificationsEnabled = false, backgroundLocationGranted = false)

        assertNull(next(inputs, attempted = setOf(SetupStep.NOTIFICATIONS)))
    }

    @Test
    fun backgroundLocationIsOnlyEverTheLastThingAsked() {
        val inputs = everything.copy(backgroundLocationGranted = false)
        assertEquals(SetupStep.BACKGROUND_LOCATION, next(inputs))
        // ... and with anything before it unmet, that unmet thing comes first
        assertEquals(SetupStep.NOTIFICATIONS, next(inputs.copy(notificationsEnabled = false)))
    }

    @Test
    fun belowAndroid13NotificationsAreNotAskedAndCannotBeAsked() {
        assertNull(next(everything.copy(notificationsEnabled = false, backgroundLocationGranted = false), sdk = 32))
    }

    @Test
    fun belowAndroid10BackgroundLocationIsNotARuntimePermissionSoItIsNotAsked() {
        assertNull(next(everything.copy(backgroundLocationGranted = false), sdk = 28))
        assertEquals(SetupStep.BACKGROUND_LOCATION, next(everything.copy(backgroundLocationGranted = false), sdk = 29))
    }

    @Test
    fun locationSwitchedOffIsNotAStepBecauseOnlyTheSettingCanFixIt() {
        assertNull(next(everything.copy(locationServicesEnabled = false)))
    }

    @Test
    fun theThresholdsAreTheRealAndroidVersions() {
        assertEquals(33, AutoTrackingSetup.FIRST_SDK_WITH_NOTIFICATION_PERMISSION)
        assertEquals(29, AutoTrackingSetup.FIRST_SDK_WITH_BACKGROUND_PERMISSION)
    }
}
