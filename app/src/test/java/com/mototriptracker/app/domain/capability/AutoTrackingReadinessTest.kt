package com.mototriptracker.app.domain.capability

import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.core.model.CapabilityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SET-02: the state a person reads about Auto Tracking, from the switch and the permissions. */
class AutoTrackingReadinessTest {

    private val everything = CapabilityInputs(
        preciseLocationGranted = true, approximateLocationGranted = true, activityRecognitionGranted = true,
        backgroundLocationGranted = true, notificationsEnabled = true, locationServicesEnabled = true, autoTrackingEnabledByUser = true
    )

    private fun state(change: CapabilityInputs.() -> CapabilityInputs) = AutoTrackingReadiness.stateFor(everything.change())

    @Test
    fun withEverythingInPlaceAndTheSwitchOnItIsReady() {
        assertEquals(AutoTrackingState.READY, state { this })
    }

    @Test
    fun theSwitchOffIsOffWhateverThePermissionsAre() {
        assertEquals(AutoTrackingState.OFF, state { copy(autoTrackingEnabledByUser = false) })
        assertEquals(AutoTrackingState.OFF, state { copy(autoTrackingEnabledByUser = false, activityRecognitionGranted = false) })
        assertEquals("even a location problem does not turn 'off' into an alarm", AutoTrackingState.OFF, state { copy(autoTrackingEnabledByUser = false, locationServicesEnabled = false) })
    }

    @Test
    fun withoutBackgroundLocationItIsLimitedNotReady() {
        assertEquals(AutoTrackingState.LIMITED, state { copy(backgroundLocationGranted = false) })
    }

    @Test
    fun withoutNotificationsItIsLimitedBecauseAFullyAutomaticTripMustBePerceptible() {
        assertEquals(AutoTrackingState.LIMITED, state { copy(notificationsEnabled = false) })
    }

    @Test
    fun withoutActivityRecognitionItNeedsSetupAndStaysManual() {
        assertEquals(AutoTrackingState.NEEDS_SETUP, state { copy(activityRecognitionGranted = false) })
    }

    @Test
    fun withoutPreciseLocationOrWithLocationOffItIsALocationProblemBeforeAnythingElse() {
        assertEquals(AutoTrackingState.LOCATION_PROBLEM, state { copy(preciseLocationGranted = false) })
        assertEquals(AutoTrackingState.LOCATION_PROBLEM, state { copy(locationServicesEnabled = false) })
        assertEquals(
            "a location problem wins over a missing activity permission",
            AutoTrackingState.LOCATION_PROBLEM, state { copy(locationServicesEnabled = false, activityRecognitionGranted = false) }
        )
    }

    /** The screen's states are the resolver's modes, plus OFF: they can never disagree about what the app will do. */
    @Test
    fun everyStateAgreesWithTheResolverWhenTheSwitchIsOn() {
        val booleans = listOf(true, false)
        for (precise in booleans) for (services in booleans) for (activity in booleans) for (notifications in booleans) for (background in booleans) {
            val inputs = everything.copy(
                preciseLocationGranted = precise, locationServicesEnabled = services, activityRecognitionGranted = activity,
                notificationsEnabled = notifications, backgroundLocationGranted = background
            )
            val expected = when (CapabilityResolver.resolve(inputs)) {
                CapabilityMode.FULL_AUTO -> AutoTrackingState.READY
                CapabilityMode.ASSISTED_AUTO -> AutoTrackingState.LIMITED
                CapabilityMode.MANUAL -> AutoTrackingState.NEEDS_SETUP
                CapabilityMode.LOCATION_DEGRADED -> AutoTrackingState.LOCATION_PROBLEM
            }
            assertEquals("$inputs", expected, AutoTrackingReadiness.stateFor(inputs))
        }
    }

    @Test
    fun theRequirementsAreListedInTheOrderTheDocumentAsksForThem() {
        assertEquals(
            listOf(
                AutoTrackingRequirement.PRECISE_LOCATION, AutoTrackingRequirement.LOCATION_SERVICES, AutoTrackingRequirement.ACTIVITY_RECOGNITION,
                AutoTrackingRequirement.NOTIFICATIONS, AutoTrackingRequirement.BACKGROUND_LOCATION
            ),
            AutoTrackingReadiness.requirementsFor(everything).map { it.requirement }
        )
    }

    @Test
    fun eachRequirementReflectsItsOwnInputAndOnlyThatOne() {
        val none = everything.copy(
            preciseLocationGranted = false, locationServicesEnabled = false, activityRecognitionGranted = false,
            notificationsEnabled = false, backgroundLocationGranted = false
        )
        assertTrue(AutoTrackingReadiness.requirementsFor(everything).all { it.met })
        assertTrue(AutoTrackingReadiness.requirementsFor(none).none { it.met })

        val onlyActivity = AutoTrackingReadiness.requirementsFor(none.copy(activityRecognitionGranted = true))
        assertEquals(listOf(AutoTrackingRequirement.ACTIVITY_RECOGNITION), onlyActivity.filter { it.met }.map { it.requirement })
    }

    @Test
    fun theRequirementsAreShownEvenWithTheSwitchOffSoThePersonSeesWhatTurningItOnNeeds() {
        val requirements = AutoTrackingReadiness.requirementsFor(everything.copy(autoTrackingEnabledByUser = false, activityRecognitionGranted = false))

        assertFalse(requirements.first { it.requirement == AutoTrackingRequirement.ACTIVITY_RECOGNITION }.met)
        assertEquals(5, requirements.size)
    }

    @Test
    fun onlyNotificationsAndBackgroundLocationAreMarkedAsNeededForHandsFree() {
        val handsFree = AutoTrackingReadiness.requirementsFor(everything).filter { it.neededForHandsFree }.map { it.requirement }

        assertEquals(listOf(AutoTrackingRequirement.NOTIFICATIONS, AutoTrackingRequirement.BACKGROUND_LOCATION), handsFree)
    }
}
