package com.mototriptracker.app.core.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mototriptracker.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * TRK-001's minimal slice: a bare, no-actions notification, just enough to
 * satisfy Android's requirement that a `location`-type foreground service
 * show one (F0.4 §6/§10). `NOT-001` extends this controller with the
 * Pause/Resume/Finish actions from F0.9 §7 — not duplicated here.
 */
class TrackingNotificationController @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    fun buildTrackingNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.tracking_notification_title))
            .setContentText(context.getString(R.string.tracking_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * AUTO-001/F0.9 §5.3: CANDIDATE_START "no necesita una pantalla modal...
     * puede mostrarse discretamente" - a distinct, honest notification for
     * the window before a Trip is actually confirmed, so a candidate that
     * gets abandoned a few seconds later was never mislabelled as "recording
     * your trip". Same channel/ID as [buildTrackingNotification] - the
     * service swaps one for the other in place once a candidate confirms.
     */
    fun buildValidatingCandidateNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.tracking_notification_title))
            .setContentText(context.getString(R.string.validating_candidate_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * DET-005/F0.3 §8: "the app MAY issue a high-priority but non-distracting
     * reminder" for sustained movement detected while manually paused. A
     * separate, dismissible channel/ID from [buildTrackingNotification]'s
     * ongoing one on purpose - this is a one-shot nudge, not a persistent
     * FGS notification, and `NotificationManagerCompat.notify` is a documented
     * safe no-op if `POST_NOTIFICATIONS` isn't granted (API 33+), so no
     * explicit permission check is needed here.
     */
    fun postForgottenPauseReminder() {
        ensureReminderChannel()
        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.forgotten_pause_reminder_title))
            .setContentText(context.getString(R.string.forgotten_pause_reminder_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(REMINDER_NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.tracking_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun ensureReminderChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            context.getString(R.string.forgotten_pause_reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "trip_tracking"
        const val NOTIFICATION_ID = 1001
        const val REMINDER_CHANNEL_ID = "trip_reminders"
        const val REMINDER_NOTIFICATION_ID = 1002
    }
}
