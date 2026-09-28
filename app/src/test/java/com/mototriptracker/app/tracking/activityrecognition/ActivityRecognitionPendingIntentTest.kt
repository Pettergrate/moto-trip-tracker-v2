package com.mototriptracker.app.tracking.activityrecognition

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.ActivityRecognition
import com.mototriptracker.app.tracking.receiver.ActivityTransitionReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Found on the phone: Auto Tracking recorded nothing for two weeks because the registration used an *immutable*
 * PendingIntent. Play Services adds the transition to the intent as an extra when it delivers; an immutable
 * PendingIntent refuses, so every delivery arrived empty and the receiver discarded it. `dumpsys activity intents`
 * showed `flags=0x4000000` (`FLAG_IMMUTABLE`) for the app. These pin the fix.
 */
@RunWith(RobolectricTestRunner::class)
class ActivityRecognitionPendingIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registrar = ActivityRecognitionRegistrar(context, ActivityRecognition.getClient(context))

    private fun flagsOf(pendingIntent: PendingIntent) = shadowOf(pendingIntent).flags

    /** Never immutable, whatever the Android version - that was the bug. */
    @Test
    fun theRegistrationPendingIntentIsNeverImmutableSoPlayServicesCanAddTheTransition() {
        assertEquals("must not be immutable", 0, flagsOf(registrar.pendingIntent()) and PendingIntent.FLAG_IMMUTABLE)
    }

    /** From Android 12 the flag has to be asked for explicitly (before it, a PendingIntent is mutable by default and there is no such flag). */
    @Test
    fun fromAndroid12ItAsksForMutableExplicitly() {
        val expected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

        assertEquals(expected, flagsOf(registrar.pendingIntent()) and PendingIntent.FLAG_MUTABLE)
        assertEquals(expected, ActivityRecognitionRegistrar.mutableFlag())
    }

    /** A PendingIntent that already exists keeps the mutability it was created with, so the flag alone would not have fixed a phone that ran an older build. */
    @Test
    fun itUsesANewRequestCodeSoAnOlderImmutableRegistrationCannotBeReused() {
        val current = shadowOf(registrar.pendingIntent()).requestCode

        assertNotEquals("1001 was the immutable one", 1001, current)
        assertEquals(1002, current)
    }

    /** Mutable is only safe because the intent names its target: whoever holds the PendingIntent can add extras but not redirect it. */
    @Test
    fun theIntentIsExplicitSoAMutablePendingIntentCannotBeRedirected() {
        val intent = shadowOf(registrar.pendingIntent()).savedIntent

        assertNotNull(intent.component)
        assertEquals(ActivityTransitionReceiver::class.java.name, intent.component?.className)
        assertEquals(ActivityTransitionReceiver.ACTION_ACTIVITY_TRANSITION, intent.action)
    }
}
