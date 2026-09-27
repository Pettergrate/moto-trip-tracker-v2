package com.mototriptracker.app.domain.capability

import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.core.model.CapabilityMode

/**
 * SET-02 / `ux-navigation.md` §15: what a person should read about Auto Tracking. [CapabilityResolver] answers "which
 * mode" (FULL_AUTO ... LOCATION_DEGRADED); this turns that, plus the person's own switch, into the five things the
 * app actually has to say.
 *
 * The one addition to the document's four states is [OFF]: with the switch off nothing automatic can start, whatever
 * the permissions are, and saying "manual only - enable the optional permissions" to someone who chose to switch it
 * off would be a nag. (The resolver reports MANUAL in that case too, so it cannot tell "off" from "needs a permission".)
 */
enum class AutoTrackingState {
    /** The person switched it off (or never switched it on): only manual trips. */
    OFF,

    /** FULL_AUTO: everything the product's baseline asks for is in place. */
    READY,

    /** ASSISTED_AUTO: detection can work but hands-free start is not fully possible (background location or notifications missing). */
    LIMITED,

    /** On, but a permission automatic detection needs (Activity Recognition) is missing: still manual, honestly. */
    NEEDS_SETUP,

    /** LOCATION_DEGRADED: without precise location and Location switched on, no mode records a trustworthy route. */
    LOCATION_PROBLEM
}

/** What has to be true, in the order `privacy-permissions.md` §19.3 asks for it. */
enum class AutoTrackingRequirement {
    PRECISE_LOCATION,
    LOCATION_SERVICES,
    ACTIVITY_RECOGNITION,
    NOTIFICATIONS,
    BACKGROUND_LOCATION
}

/**
 * @property neededForHandsFree false for what any automatic mode needs; true for what only the hands-free (Full Auto)
 * bar adds - notifications, so the trip is perceptible and controllable (§9), and background location, so a trip can
 * start while the app is closed.
 */
data class RequirementStatus(val requirement: AutoTrackingRequirement, val met: Boolean, val neededForHandsFree: Boolean)

object AutoTrackingReadiness {

    fun stateFor(inputs: CapabilityInputs): AutoTrackingState {
        if (!inputs.autoTrackingEnabledByUser) return AutoTrackingState.OFF
        return when (CapabilityResolver.resolve(inputs)) {
            CapabilityMode.FULL_AUTO -> AutoTrackingState.READY
            CapabilityMode.ASSISTED_AUTO -> AutoTrackingState.LIMITED
            CapabilityMode.MANUAL -> AutoTrackingState.NEEDS_SETUP
            CapabilityMode.LOCATION_DEGRADED -> AutoTrackingState.LOCATION_PROBLEM
        }
    }

    /** Every requirement with whether it is met, whatever the switch says - the person can see what turning it on will need. */
    fun requirementsFor(inputs: CapabilityInputs): List<RequirementStatus> = listOf(
        RequirementStatus(AutoTrackingRequirement.PRECISE_LOCATION, inputs.preciseLocationGranted, neededForHandsFree = false),
        RequirementStatus(AutoTrackingRequirement.LOCATION_SERVICES, inputs.locationServicesEnabled, neededForHandsFree = false),
        RequirementStatus(AutoTrackingRequirement.ACTIVITY_RECOGNITION, inputs.activityRecognitionGranted, neededForHandsFree = false),
        RequirementStatus(AutoTrackingRequirement.NOTIFICATIONS, inputs.notificationsEnabled, neededForHandsFree = true),
        RequirementStatus(AutoTrackingRequirement.BACKGROUND_LOCATION, inputs.backgroundLocationGranted, neededForHandsFree = true)
    )
}
