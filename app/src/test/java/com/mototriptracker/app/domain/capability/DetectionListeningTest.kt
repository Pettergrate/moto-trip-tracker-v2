package com.mototriptracker.app.domain.capability

import com.mototriptracker.app.core.model.CapabilityInputs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PERM-002: the app listens for activity transitions only while Auto Tracking is on and the permission is granted. */
class DetectionListeningTest {

    private val everything = CapabilityInputs(
        preciseLocationGranted = true, approximateLocationGranted = true, activityRecognitionGranted = true,
        backgroundLocationGranted = true, notificationsEnabled = true, locationServicesEnabled = true, autoTrackingEnabledByUser = true
    )

    @Test
    fun onWithThePermissionItListens() {
        assertTrue(DetectionListening.shouldListen(everything))
    }

    @Test
    fun offItDoesNotListenWhateverThePermissionsAre() {
        assertFalse(DetectionListening.shouldListen(everything.copy(autoTrackingEnabledByUser = false)))
    }

    @Test
    fun onWithoutThePermissionItCannotListen() {
        assertFalse(DetectionListening.shouldListen(everything.copy(activityRecognitionGranted = false)))
    }

    /** The detector should be ready when location or notifications come back, so neither is part of the rule (whether a trip may start is the resolver's own decision). */
    @Test
    fun locationAndNotificationsDoNotDecideWhetherItListens() {
        assertTrue(DetectionListening.shouldListen(everything.copy(locationServicesEnabled = false)))
        assertTrue(DetectionListening.shouldListen(everything.copy(preciseLocationGranted = false, backgroundLocationGranted = false)))
        assertTrue(DetectionListening.shouldListen(everything.copy(notificationsEnabled = false)))
    }
}
