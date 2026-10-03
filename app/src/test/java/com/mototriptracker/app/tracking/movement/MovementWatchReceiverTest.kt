package com.mototriptracker.app.tracking.movement

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.LocationServices
import com.mototriptracker.app.testing.FakeMovementWatching
import com.mototriptracker.app.tracking.activityrecognition.ActivityRecognitionRegistrar
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * DET-011: what the geofence's receiver does with what arrives. The platform's wire format (the serialized transition
 * Play Services adds to the intent) cannot be fabricated in a unit test - same limit as the Activity Recognition one -
 * so the decision is tested apart from reading it, and the reading is confirmed on the device.
 */
@RunWith(RobolectricTestRunner::class)
class MovementWatchReceiverTest {

    private val watch = FakeMovementWatching()
    private val receiver = MovementWatchReceiver().apply { movementWatch = watch }

    @Test
    fun anExitIsPassedOnToTheWatch() = runTest {
        receiver.handle(MovementWatchReceiver.Reading(hasError = false, errorCode = 0, isExit = true))

        assertEquals(listOf("onExit"), watch.calls)
    }

    @Test
    fun aDeliveryWithNoGeofencingEventAtAllIsRecordedNotDropped() = runTest {
        receiver.handle(null)

        assertEquals(listOf("onEmptyBroadcast(NO_EVENT)"), watch.calls)
    }

    @Test
    fun anErrorFromPlayServicesIsRecordedWithItsCode() = runTest {
        receiver.handle(MovementWatchReceiver.Reading(hasError = true, errorCode = 1000, isExit = false))

        assertEquals(listOf("onEmptyBroadcast(ERROR_1000)"), watch.calls)
    }

    @Test
    fun aTransitionThatIsNotAnExitIsRecordedNotActedOn() = runTest {
        receiver.handle(MovementWatchReceiver.Reading(hasError = false, errorCode = 0, isExit = false))

        assertEquals(listOf("onEmptyBroadcast(OTHER_TRANSITION)"), watch.calls)
    }

    @Test
    fun anIntentThatCarriesNoGeofencingDataReadsAsNothing() {
        assertNull(receiver.readBroadcast(Intent()))
    }
}

/** DET-011: the geofence's PendingIntent - the same mutability trap that silenced Activity Recognition for two weeks. */
@RunWith(RobolectricTestRunner::class)
class GeofenceMovementWatchPendingIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registration = GeofenceMovementWatchRegistration(context, LocationServices.getGeofencingClient(context))

    private fun flagsOf(pendingIntent: PendingIntent) = shadowOf(pendingIntent).flags

    @Test
    fun theWatchPendingIntentIsNeverImmutableSoPlayServicesCanAddTheTransition() {
        assertEquals("must not be immutable", 0, flagsOf(registration.pendingIntent()) and PendingIntent.FLAG_IMMUTABLE)
    }

    @Test
    fun fromAndroid12ItAsksForMutableExplicitly() {
        val expected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

        assertEquals(expected, flagsOf(registration.pendingIntent()) and PendingIntent.FLAG_MUTABLE)
    }

    @Test
    fun itTargetsTheMovementWatchReceiverWithItsOwnActionAndItsOwnRequestCode() {
        val shadow = shadowOf(registration.pendingIntent())

        assertEquals(MovementWatchReceiver::class.java.name, shadow.savedIntents.single().component?.className)
        assertEquals(MovementWatchReceiver.ACTION_MOVEMENT_WATCH, shadow.savedIntents.single().action)
        assertEquals(GeofenceMovementWatchRegistration.REQUEST_CODE, shadow.requestCode)
        assertNotEquals("distinct from the Activity Recognition registration's", 1002, shadow.requestCode)
        assertNotEquals(1001, shadow.requestCode)
        // And the two registrations really are different PendingIntents.
        val activity = ActivityRecognitionRegistrar(context, ActivityRecognition.getClient(context)).pendingIntent()
        assertNotEquals(activity, registration.pendingIntent())
    }
}
