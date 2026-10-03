package com.mototriptracker.app.tracking.activityrecognition

import com.mototriptracker.app.domain.capability.DetectionListening
import com.mototriptracker.app.tracking.capability.CapabilityInputsProvider
import com.mototriptracker.app.tracking.movement.MovementWatching
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
    private val capabilityInputsProvider: CapabilityInputsProvider,
    /** DET-011: follows the same on/off - armed while listening, removed when not. Observation mode: it only records. */
    private val movementWatch: MovementWatching
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
        // DET-011: after the registration, and never allowed to undo it - a movement watch that fails must not
        // take Activity Recognition (the thing that actually starts trips) down with it.
        try {
            movementWatch.sync(listening)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Deliberately swallowed: the watcher records its own failures (`MOVEMENT_WATCH_ARM_FAILED`), and this class
            // stays free of Android so its on/off logic can be tested as plain Kotlin.
        }
        listening
    }
}
