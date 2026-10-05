package com.mototriptracker.app.tracking.recovery

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.tracking.activityrecognition.ActivityRecognitionRegistrar
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DET-011 was removed; a geofence it registered survives on a phone that ran it. These pin that the leftover is found by
 * what the old registration made (request code, action, receiver class name - none of which exist as code any more), is
 * removed once, and that a start with nothing left over does nothing.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyGeofenceCleanupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val removed = mutableListOf<PendingIntent>()
    private var failWith: Exception? = null

    private val cleanup = LegacyGeofenceCleanup(context) { pendingIntent ->
        failWith?.let { throw it }
        removed += pendingIntent
    }

    /** What the removed registration created, built the way it built it (the receiver class no longer exists, so by name). */
    private fun registerLikeTheRemovedBuildDid(): PendingIntent = PendingIntent.getBroadcast(
        context, 1003,
        Intent().setClassName(context.packageName, "com.mototriptracker.app.tracking.movement.MovementWatchReceiver")
            .setAction("com.mototriptracker.app.action.MOVEMENT_WATCH"),
        PendingIntent.FLAG_UPDATE_CURRENT or ActivityRecognitionRegistrar.mutableFlag()
    )

    @Test
    fun withNothingLeftOverItDoesNothingAndAsksNothingOfPlayServices() = runTest {
        assertFalse(cleanup.removeIfPresent())

        assertEquals(emptyList<PendingIntent>(), removed)
    }

    @Test
    fun aLeftoverRegistrationIsRemovedOnceAndItsPendingIntentIsCancelled() = runTest {
        registerLikeTheRemovedBuildDid()

        assertTrue(cleanup.removeIfPresent())

        assertEquals("Play Services was asked to drop the geofence", 1, removed.size)
        assertNull("and the PendingIntent itself is gone, so the next start finds nothing", cleanup.existingLegacyPendingIntent())
        assertFalse(cleanup.removeIfPresent())
        assertEquals(1, removed.size)
    }

    @Test
    fun aRemovalThatFailsLeavesTheMarkerSoTheNextStartTriesAgain() = runTest {
        registerLikeTheRemovedBuildDid()
        failWith = IllegalStateException("Play Services unavailable")

        assertFalse(cleanup.removeIfPresent())

        assertNotNull(cleanup.existingLegacyPendingIntent())
        failWith = null
        assertTrue(cleanup.removeIfPresent())
        assertEquals(1, removed.size)
    }

    @Test
    fun theLookupNeverCreatesAPendingIntentThatWasNotThere() {
        assertNull(cleanup.existingLegacyPendingIntent())
        assertNull("still nothing: looking must not register anything", cleanup.existingLegacyPendingIntent())
    }
}
