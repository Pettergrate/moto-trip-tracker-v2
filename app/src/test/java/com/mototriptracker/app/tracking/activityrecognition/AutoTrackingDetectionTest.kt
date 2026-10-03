package com.mototriptracker.app.tracking.activityrecognition

import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.testing.FakeActivityTransitionRegistration
import com.mototriptracker.app.testing.FakeCapabilityInputsProvider
import com.mototriptracker.app.testing.FakeMovementWatching
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PERM-002: "off" has to mean the app stops listening, and "on" with the permission has to start it. */
class AutoTrackingDetectionTest {

    private val everything = CapabilityInputs(
        preciseLocationGranted = true, approximateLocationGranted = true, activityRecognitionGranted = true,
        backgroundLocationGranted = true, notificationsEnabled = true, locationServicesEnabled = true, autoTrackingEnabledByUser = true
    )

    private val registration = FakeActivityTransitionRegistration()
    private val provider = FakeCapabilityInputsProvider(everything)
    private val movementWatch = FakeMovementWatching()
    private val detection = AutoTrackingDetection(registration, provider, movementWatch)

    @Test
    fun onWithThePermissionItRegisters() = runBlocking {
        assertTrue(detection.sync())

        assertEquals(true, registration.isRegistered)
    }

    @Test
    fun switchedOffItUnregistersInsteadOfKeepingTheRegistration() = runBlocking {
        detection.sync()

        provider.set(everything.copy(autoTrackingEnabledByUser = false))
        assertFalse(detection.sync())

        assertEquals(false, registration.isRegistered)
    }

    /** The case of the owner's phone before this: permission granted, switch off - and the app was listening. */
    @Test
    fun withThePermissionGrantedButTheSwitchOffItDoesNotListen() = runBlocking {
        provider.set(everything.copy(autoTrackingEnabledByUser = false))

        detection.sync()

        assertEquals(false, registration.isRegistered)
        assertFalse(registration.calls.contains(FakeActivityTransitionRegistration.REGISTER))
    }

    @Test
    fun onWithoutThePermissionItDoesNotRegister() = runBlocking {
        provider.set(everything.copy(activityRecognitionGranted = false))

        assertFalse(detection.sync())

        assertFalse(registration.calls.contains(FakeActivityTransitionRegistration.REGISTER))
    }

    @Test
    fun aPermissionGrantedLaterStartsTheListening() = runBlocking {
        provider.set(everything.copy(activityRecognitionGranted = false))
        detection.sync()

        provider.set(everything)
        detection.sync()

        assertEquals(true, registration.isRegistered)
    }

    /** Not being sure is not a reason to keep collecting. */
    @Test
    fun whenTheCapabilityReadFailsItStopsListening() = runBlocking {
        detection.sync()

        provider.throwOnRead = true
        assertFalse(detection.sync())

        assertEquals(false, registration.isRegistered)
    }

    // --- DET-011: the movement watch follows the same on/off, and can never take Activity Recognition down with it ---

    @Test
    fun theMovementWatchIsToldWhetherTheAppIsListening() = runBlocking {
        detection.sync()
        provider.set(everything.copy(autoTrackingEnabledByUser = false))
        detection.sync()

        assertEquals(listOf("sync(true)", "sync(false)"), movementWatch.calls)
    }

    @Test
    fun aMovementWatchThatFailsDoesNotUndoTheActivityRegistrationNorTheAnswer() = runBlocking {
        movementWatch.failWith = IllegalStateException("platform refused")

        assertTrue(detection.sync())

        assertEquals("activity detection is registered regardless", true, registration.isRegistered)
    }

    @Test
    fun applyingTheSameStateAgainIsHarmless() = runBlocking {
        detection.sync()
        detection.sync()
        detection.sync()

        assertEquals(true, registration.isRegistered)
        assertEquals(listOf(FakeActivityTransitionRegistration.REGISTER), registration.calls.distinct())
    }
}
