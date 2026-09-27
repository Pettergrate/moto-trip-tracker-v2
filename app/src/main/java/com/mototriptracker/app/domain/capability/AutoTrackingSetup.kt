package com.mototriptracker.app.domain.capability

/** The permissions the guided Auto Tracking setup asks for, one at a time (`privacy-permissions.md` §19.3). */
enum class SetupStep {
    /** Explained first (§8.3): it is the least sensitive and the one automatic detection cannot exist without. */
    ACTIVITY_RECOGNITION,

    /** "Verificar Fine Location": normally already there from the first Start (`PERM-001`). */
    PRECISE_LOCATION,

    /** "Verificar POST_NOTIFICATIONS (API 33+)": hands-free trips must be perceptible and controllable (§9). */
    NOTIFICATIONS,

    /** Last, with the prominent disclosure (§7.3), and only when everything before it is in place. */
    BACKGROUND_LOCATION
}

/**
 * PERM-002 / `privacy-permissions.md` §7.2 and §19.3: which permission the guided setup asks for next, given what is
 * already held and what the person has already been asked in this run.
 *
 * Two rules go beyond the order of the document, both from least privilege (`NFR-PRV-002`):
 * - **A "no" ends the run** for anything that depends on it. Asking for background location after someone declined the
 *   activity permission would request the most sensitive permission for a feature that cannot work.
 * - **Background location is offered only when the three before it are in place**, for the same reason: it is what
 *   makes a trip start while the app is closed, so it is pointless (and not justified) if the detector cannot trigger
 *   or the trip would not be perceptible.
 *
 * A pure decision (ADR-013): what to *show* for each step, and how to ask the platform, is the screen's business.
 */
object AutoTrackingSetup {

    /** `Build.VERSION_CODES.TIRAMISU`: the first Android with a runtime permission for notifications. */
    const val FIRST_SDK_WITH_NOTIFICATION_PERMISSION = 33

    /** `Build.VERSION_CODES.Q`: the first Android where background location is a permission of its own. */
    const val FIRST_SDK_WITH_BACKGROUND_PERMISSION = 29

    /**
     * @param attempted steps the person was already asked about in this run (whatever they answered): they are not
     * asked twice, and a step that was not granted stops what depends on it.
     * @return the next step, or `null` when the run is over (everything granted, or nothing more that makes sense to ask).
     */
    fun nextStep(requirements: List<RequirementStatus>, sdkInt: Int, attempted: Set<SetupStep> = emptySet()): SetupStep? {
        fun unmet(requirement: AutoTrackingRequirement) = requirements.any { it.requirement == requirement && !it.met }

        if (unmet(AutoTrackingRequirement.ACTIVITY_RECOGNITION)) {
            return SetupStep.ACTIVITY_RECOGNITION.takeIf { it !in attempted }
        }
        if (unmet(AutoTrackingRequirement.PRECISE_LOCATION)) {
            return SetupStep.PRECISE_LOCATION.takeIf { it !in attempted }
        }
        if (unmet(AutoTrackingRequirement.NOTIFICATIONS)) {
            // Below Android 13 they can only be turned back on in Settings, and nothing here can ask; and if they were
            // asked about and are still off, hands-free is out of reach, so the background permission is not asked either.
            if (sdkInt >= FIRST_SDK_WITH_NOTIFICATION_PERMISSION && SetupStep.NOTIFICATIONS !in attempted) return SetupStep.NOTIFICATIONS
            return null
        }
        if (unmet(AutoTrackingRequirement.BACKGROUND_LOCATION)) {
            return SetupStep.BACKGROUND_LOCATION.takeIf { sdkInt >= FIRST_SDK_WITH_BACKGROUND_PERMISSION && it !in attempted }
        }
        return null
    }
}
