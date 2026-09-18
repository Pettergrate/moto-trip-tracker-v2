package com.mototriptracker.app.core.notification

import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
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
}
