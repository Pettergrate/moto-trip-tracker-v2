package com.mototriptracker.app.domain.capability

import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.core.model.CapabilityMode

/**
 * F0.11 §18-19: resolves raw permission/service/setting inputs into the four
 * user-facing capability modes. Pure decision table, zero Android dependency
 * (ADR-013) — whatever reads real permissions/services into a
 * [CapabilityInputs] lives elsewhere (a future platform-layer gateway, not
 * yet built); this only decides, never queries Android itself.
 *
 * Precedence, in order:
 * 1. [CapabilityInputs.locationServicesEnabled]/[CapabilityInputs.preciseLocationGranted]
 *    missing → LOCATION_DEGRADED. Without precise location, no mode can
 *    produce a trustworthy route (F0.5 GPS-002), so this overrides
 *    everything else — including a "clean install" with every field false,
 *    which is intentional: Android's own permission API can't distinguish
 *    "never asked" from "denied" either (`checkSelfPermission` returns the
 *    same DENIED for both), so this resolver doesn't invent a fifth state
 *    for it. A friendlier first-run copy for that specific case is a UI/
 *    onboarding concern (PERM-001/ONB-*), not this resolver's.
 * 2. [CapabilityInputs.autoTrackingEnabledByUser] off, or Activity
 *    Recognition not granted → MANUAL (F0.9 §16: disabling Auto Tracking
 *    stops all automatic start behavior; F0.11 §5: AR denial has no
 *    approved Assisted fallback yet).
 * 3. Otherwise: FULL_AUTO only if background location AND notifications are
 *    both available (F0.11 §18 FULL_AUTO_READY, §9 "Full Auto no se declara
 *    READY sin capacidad de mostrar la notificación"); ASSISTED_AUTO
 *    otherwise — Activity Recognition can still suggest a trip even when
 *    Full Auto's stricter transparency/capability bar isn't met.
 */
object CapabilityResolver {

    fun resolve(inputs: CapabilityInputs): CapabilityMode {
        if (!inputs.locationServicesEnabled || !inputs.preciseLocationGranted) {
            return CapabilityMode.LOCATION_DEGRADED
        }
        if (!inputs.autoTrackingEnabledByUser || !inputs.activityRecognitionGranted) {
            return CapabilityMode.MANUAL
        }
        return if (inputs.backgroundLocationGranted && inputs.notificationsEnabled) {
            CapabilityMode.FULL_AUTO
        } else {
            CapabilityMode.ASSISTED_AUTO
        }
    }
}
