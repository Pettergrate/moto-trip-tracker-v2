package com.mototriptracker.app.core.notification

import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.feature.common.formatDistanceKm
import com.mototriptracker.app.feature.common.formatDurationCompact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNotificationManager

/**
 * DET-005: proves [TrackingNotificationController.postForgottenPauseReminder]
 * actually reaches a real, correctly-shaped Android notification (via
 * Robolectric's shadow), not just that it compiles - the coordinator-level
 * tests already prove *when* this gets called; this proves *what* it does.
 */
@RunWith(RobolectricTestRunner::class)
class TrackingNotificationControllerTest {

    private val controller = TrackingNotificationController(ApplicationProvider.getApplicationContext())

    @Test
    fun postForgottenPauseReminderPostsARealDismissibleNotification() {
        controller.postForgottenPauseReminder()

        val manager = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(NotificationManager::class.java)
        val notification = (shadowOf(manager) as ShadowNotificationManager)
            .getNotification(TrackingNotificationController.REMINDER_NOTIFICATION_ID)

        assertNotNull("expected a reminder notification to be posted", notification)
        assertEquals(
            "Still paused?",
            notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        )
        assertTrue(
            "a one-shot reminder must be dismissible, not an ongoing notification",
            (notification.flags and Notification.FLAG_ONGOING_EVENT) == 0
        )
    }

    @Test
    fun postForgottenPauseReminderNeverReusesTheOngoingTrackingNotificationId() {
        assertTrue(TrackingNotificationController.REMINDER_NOTIFICATION_ID != TrackingNotificationController.NOTIFICATION_ID)
    }

    // --- NOT-001: Pause/Resume/Finish notification actions ---------------

    @Test
    fun trackingNotificationShowsPlaceholderTextBeforeLiveDataIsKnown() {
        val notification = controller.buildTrackingNotification()

        assertEquals(
            "Recording your trip",
            notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        )
    }

    @Test
    fun trackingNotificationShowsLiveDistanceAndDurationOnceKnown() {
        val notification = controller.buildTrackingNotification(distanceMeters = 5_400.0, elapsedMs = 37 * 60_000L)

        // Built from the same formatters the notification uses, not a
        // hardcoded literal - the decimal separator is locale-dependent
        // (e.g. "5,4 km" on a Spanish-locale device vs "5.4 km" in a
        // default-locale JVM test run).
        val expected = "${formatDistanceKm(5_400.0)} · ${formatDurationCompact(37 * 60_000L)}"
        assertEquals(expected, notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test
    fun trackingNotificationHasExactlyPauseAndFinishActions() {
        val notification = controller.buildTrackingNotification()

        val labels = notification.actions.map { it.title.toString() }
        assertEquals(listOf("Pause", "Finish"), labels)
    }

    @Test
    fun pausedTrackingNotificationHasExactlyResumeAndFinishActions() {
        val notification = controller.buildPausedTrackingNotification(elapsedMs = 42 * 60_000L)

        val labels = notification.actions.map { it.title.toString() }
        assertEquals(listOf("Resume", "Finish"), labels)
        assertEquals("42m", notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test
    fun trackingAndPausedNotificationsBothCarryAContentIntentToOpenTheApp() {
        assertNotNull(controller.buildTrackingNotification().contentIntent)
        assertNotNull(controller.buildPausedTrackingNotification().contentIntent)
    }

    /** DET-007: same shape, distinct content from the pause reminder. */
    @Test
    fun postForgottenFinishReminderPostsARealDismissibleNotification() {
        controller.postForgottenFinishReminder()

        val manager = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(NotificationManager::class.java)
        val notification = (shadowOf(manager) as ShadowNotificationManager)
            .getNotification(TrackingNotificationController.REMINDER_NOTIFICATION_ID)

        assertNotNull("expected a reminder notification to be posted", notification)
        assertEquals(
            "Still recording?",
            notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        )
        assertTrue(
            "a one-shot reminder must be dismissible, not an ongoing notification",
            (notification.flags and Notification.FLAG_ONGOING_EVENT) == 0
        )
    }
}
