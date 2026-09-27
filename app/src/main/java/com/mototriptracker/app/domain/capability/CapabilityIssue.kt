package com.mototriptracker.app.domain.capability

import com.mototriptracker.app.core.model.CapabilityInputs

/**
 * PERM-003 / `privacy-permissions.md` §9, §18, §19.4: *why* the app cannot do its job as well as it should right
 * now. [CapabilityResolver] answers "which mode" (FULL_AUTO ... LOCATION_DEGRADED); this answers the question a
 * person actually has when it says "degraded": what is wrong, and what one thing would fix it.
 *
 * Scoped to what matters for recording a trip by hand - the Manual baseline of §18. What only concerns Auto Tracking
 * (background location, Activity Recognition) is deliberately not listed: nothing in the app can turn Auto Tracking on
 * yet, so telling someone about a permission for a feature they cannot reach would be noise (that belongs to `PERM-002`).
 *

 * @property blocksRoute the trip can still be *started* but no usable route will be recorded (ADR-022 / F0.10 §12.1) -
 * as opposed to a problem that only degrades the experience.
 * @property warnBeforeStart tapping START TRIP with this problem present deserves a one-time warning first. Not every
 * route-blocking problem: with no location permission at all, Start itself asks for it, so there is nothing to warn about.
 */
enum class CapabilityIssue(val blocksRoute: Boolean, val warnBeforeStart: Boolean) {
    /** Neither precise nor approximate location is allowed: nothing can be recorded. */
    LOCATION_PERMISSION_MISSING(blocksRoute = true, warnBeforeStart = false),

    /** Location Services are switched off system-wide. */
    LOCATION_SERVICES_OFF(blocksRoute = true, warnBeforeStart = true),

    /** Only approximate location is allowed: the platform hands the app a ~2 km block, useless as a route (ADR-022). */
    PRECISE_LOCATION_MISSING(blocksRoute = true, warnBeforeStart = true),

    /** Notifications are off: the trip runs, but its notification - with Pause and Finish - is not shown. */
    NOTIFICATIONS_DENIED(blocksRoute = false, warnBeforeStart = false)
}

object CapabilityDiagnosis {

    /**
     * The problems in [inputs], **most important first**, so a screen can show one clear action now and the next
     * one after that is fixed ("mostrar una sola acción clara para resolver", §19.4). Empty when nothing is wrong.
     *
     * Order, and why: with no permission at all nothing else can be fixed in-app, so that leads; Location Services
     * off next (system-wide, and the whole recording is blind); approximate-only after (recording is possible but
     * useless as a route); notifications last (the trip still records). When no location permission is held at all,
     * "precise missing" is not listed separately - it is already the bigger problem.
     */
    fun issuesFor(inputs: CapabilityInputs): List<CapabilityIssue> = buildList {
        if (!inputs.approximateLocationGranted) add(CapabilityIssue.LOCATION_PERMISSION_MISSING)
        if (!inputs.locationServicesEnabled) add(CapabilityIssue.LOCATION_SERVICES_OFF)
        if (inputs.approximateLocationGranted && !inputs.preciseLocationGranted) add(CapabilityIssue.PRECISE_LOCATION_MISSING)
        if (!inputs.notificationsEnabled) add(CapabilityIssue.NOTIFICATIONS_DENIED)
    }
}
