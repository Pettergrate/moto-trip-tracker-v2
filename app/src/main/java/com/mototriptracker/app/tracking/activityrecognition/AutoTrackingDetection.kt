package com.mototriptracker.app.tracking.activityrecognition

import com.mototriptracker.app.domain.capability.DetectionListening
import com.mototriptracker.app.tracking.capability.CapabilityInputsProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The two things Google Play's Activity Recognition registration can do; an interface so it can be faked without Play Services. */
interface ActivityTransitionRegistration {
    fun register()
    fun unregister()
}

/**
 * PERM-002 / SET-02: makes the app listen for activity transitions exactly when [DetectionListening] says it should - Auto
 * Tracking on and the activity permission granted - and stop listening otherwise. Called wherever that can change: the
 * app starting, the device booting or the app being updated (a registration does not survive either), the person
 * switching Auto Tracking on or off, and a permission being answered. Every call applies the *desired* state, so calling it
 * again is harmless, and calls are serialised so two changes racing cannot leave the last one undone.
 *
 * When it cannot tell (the capability read fails) it stops listening: not being sure is not a reason to keep collecting.
 */
@Singleton
class AutoTrackingDetection @Inject constructor(
    private val registration: ActivityTransitionRegistration,
    private val capabilityInputsProvider: CapabilityInputsProvider
) {
    private val mutex = Mutex()

    /** @return whether the app is now listening. */
    suspend fun sync(): Boolean = mutex.withLock {
        val listening = try {
            DetectionListening.shouldListen(capabilityInputsProvider.current())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            false
        }
        if (listening) registration.register() else registration.unregister()
        listening
    }
}
