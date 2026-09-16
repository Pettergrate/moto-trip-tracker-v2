package com.mototriptracker.app.domain.capability

import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.core.model.CapabilityMode
import com.mototriptracker.app.testing.FakeCapabilityProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CAP-001 acceptance: "deterministic unit coverage for clean install,
 * denied permissions, approximate location, disabled location services and
 * notification denial." One test per named scenario, plus the FULL_AUTO
 * happy path and the Auto-Tracking-off case F0.9 §16 requires.
 */
class CapabilityResolverTest {

    @Test
    fun cleanInstallResolvesToLocationDegraded() {
        // Android's permission API can't tell "never asked" from "denied"
        // either — see CapabilityResolver's KDoc for why this is correct,
        // not just a missing fifth state.
        val result = CapabilityResolver.resolve(FakeCapabilityProvider.cleanInstall())
        assertEquals(CapabilityMode.LOCATION_DEGRADED, result)
    }

    @Test
    fun fullGrantResolvesToFullAuto() {
        val result = CapabilityResolver.resolve(FakeCapabilityProvider.fullAuto())
        assertEquals(CapabilityMode.FULL_AUTO, result)
    }

    @Test
    fun deniedActivityRecognitionResolvesToManualEvenWithEverythingElseGranted() {
        val inputs = FakeCapabilityProvider.fullAuto().copy(activityRecognitionGranted = false)
        assertEquals(CapabilityMode.MANUAL, CapabilityResolver.resolve(inputs))
    }

    @Test
    fun deniedBackgroundLocationResolvesToAssistedAutoNotManual() {
        val inputs = FakeCapabilityProvider.fullAuto().copy(backgroundLocationGranted = false)
        assertEquals(CapabilityMode.ASSISTED_AUTO, CapabilityResolver.resolve(inputs))
    }

    @Test
    fun approximateLocationOnlyResolvesToLocationDegraded() {
        val inputs = FakeCapabilityProvider.fullAuto().copy(
            preciseLocationGranted = false,
            approximateLocationGranted = true
        )
        assertEquals(CapabilityMode.LOCATION_DEGRADED, CapabilityResolver.resolve(inputs))
    }

    @Test
    fun disabledLocationServicesResolvesToLocationDegradedEvenWithAllPermissionsGranted() {
        val inputs = FakeCapabilityProvider.fullAuto().copy(locationServicesEnabled = false)
        assertEquals(CapabilityMode.LOCATION_DEGRADED, CapabilityResolver.resolve(inputs))
    }

    @Test
    fun deniedNotificationsResolvesToAssistedAutoNotFullAuto() {
        val inputs = FakeCapabilityProvider.fullAuto().copy(notificationsEnabled = false)
        assertEquals(CapabilityMode.ASSISTED_AUTO, CapabilityResolver.resolve(inputs))
    }

    @Test
    fun autoTrackingDisabledByUserResolvesToManualEvenWithAllPermissionsGranted() {
        val inputs = FakeCapabilityProvider.fullAuto().copy(autoTrackingEnabledByUser = false)
        assertEquals(CapabilityMode.MANUAL, CapabilityResolver.resolve(inputs))
    }

    @Test
    fun deniedBothBackgroundLocationAndNotificationsStillResolvesToAssistedAuto() {
        // Multiple simultaneous reasons Full Auto doesn't qualify still land
        // on the same fallback, not a worse one — Activity Recognition alone
        // is enough to keep Assisted Auto's "suggest a trip" behavior alive.
        val inputs = FakeCapabilityProvider.fullAuto().copy(
            backgroundLocationGranted = false,
            notificationsEnabled = false
        )
        assertEquals(CapabilityMode.ASSISTED_AUTO, CapabilityResolver.resolve(inputs))
    }

    @Test
    fun deniedActivityRecognitionTakesPrecedenceOverGoodLocation() {
        val inputs = CapabilityInputs(
            preciseLocationGranted = true,
            approximateLocationGranted = true,
            activityRecognitionGranted = false,
            backgroundLocationGranted = false,
            notificationsEnabled = false,
            locationServicesEnabled = true,
            autoTrackingEnabledByUser = true
        )
        assertEquals(CapabilityMode.MANUAL, CapabilityResolver.resolve(inputs))
    }
}
