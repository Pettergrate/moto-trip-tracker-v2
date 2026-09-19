package com.mototriptracker.app.core.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mototriptracker.app.MainActivity
import com.mototriptracker.app.R
import com.mototriptracker.app.feature.common.formatDistanceKm
import com.mototriptracker.app.feature.common.formatDurationCompact
import com.mototriptracker.app.tracking.service.TrackingForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * TRK-001 built a bare, no-actions notification, just enough to satisfy
 * Android's requirement that a `location`-type foreground service show one
 * (F0.4 §6/§10). `NOT-001` is what actually adds the Pause/Resume/Finish
 * actions and the live distance/duration text F0.9 §7's wireframe asks for.
 */
class TrackingNotificationController @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /**
     * F0.9 §7's TRACKING wireframe: "● Viaje en curso · 38.4 km · 47 min"
     * with Pause/Finish actions. [distanceMeters]/[elapsedMs] are `null` for
     * the very first, synchronous `startForeground()` call in
     * `onStartCommand` (before any suspend work can read real figures) - the
     * actions and content intent work immediately regardless, since neither
     * needs live data; only the text falls back to a generic placeholder
     * until the Service's first async refresh replaces it.
     */
    fun buildTrackingNotification(distanceMeters: Double? = null, elapsedMs: Long? = null): Notification {
        ensureChannel()
        val text = if (distanceMeters != null && elapsedMs != null) {
            "${formatDistanceKm(distanceMeters)} · ${formatDurationCompact(elapsedMs)}"
        } else {
            context.getString(R.string.tracking_notification_text)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.tracking_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(buildOpenActiveTripPendingIntent())
            .addAction(
                0,
                context.getString(R.string.notification_action_pause),
                buildServiceActionPendingIntent(TrackingForegroundService.createPauseIntent(context))
            )
            .addAction(
                0,
                context.getString(R.string.notification_action_finish),
                buildServiceActionPendingIntent(TrackingForegroundService.createFinishIntent(context))
            )
            .build()
    }

    /** F0.9 §7's MANUAL_PAUSED wireframe: "Ⅱ Viaje pausado · 42 min" with Resume/Finish actions. */
    fun buildPausedTrackingNotification(elapsedMs: Long? = null): Notification {
        ensureChannel()
        val text = if (elapsedMs != null) {
            formatDurationCompact(elapsedMs)
        } else {
            context.getString(R.string.paused_tracking_notification_text)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.paused_tracking_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(buildOpenActiveTripPendingIntent())
            .addAction(
                0,
                context.getString(R.string.notification_action_resume),
                buildServiceActionPendingIntent(TrackingForegroundService.createResumeIntent(context))
            )
            .addAction(
                0,
                context.getString(R.string.notification_action_finish),
                buildServiceActionPendingIntent(TrackingForegroundService.createFinishIntent(context))
            )
            .build()
    }

    /** F0.9 §7: "Tocar el cuerpo de la notificación abre TRP-01." `CLEAR_TOP` reuses the existing task instead of stacking a duplicate `MainActivity` if the app is already running. */
    private fun buildOpenActiveTripPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_ACTIVE_TRIP, true)
        return PendingIntent.getActivity(context, REQUEST_CODE_CONTENT, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * ADR-015: every action here targets an already-idempotent
     * `TrackingForegroundService` command (`createPauseIntent`/
     * `createResumeIntent`/`createFinishIntent` already exist and are used
     * by the in-app UI too) - a duplicate tap, or the system redelivering
     * this PendingIntent, is exactly as safe as a duplicate in-app tap
     * already is. `getForegroundService` (not `getService`) is Android's
     * documented mechanism for a notification action that itself needs to
     * call `startForeground` shortly after being triggered.
     */
    private fun buildServiceActionPendingIntent(intent: Intent): PendingIntent =
        PendingIntent.getForegroundService(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

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

    /**
     * DET-007: the mirror reminder to [postForgottenPauseReminder], for a
     * manually-started capture that's been stationary far longer than a
     * normal stop - see `ForgottenFinishEngine`'s own KDoc for why this gap
     * exists at all. Shares [REMINDER_CHANNEL_ID]/[REMINDER_NOTIFICATION_ID]
     * with the pause reminder rather than adding a third channel: the two
     * are mutually exclusive for a given capture (a capture is either paused
     * or not), so there's nothing to gain from separating them, and the
     * channel's own display name was generalized to "Trip reminders" to stay
     * honest about covering both.
     */
    fun postForgottenFinishReminder() {
        ensureReminderChannel()
        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.forgotten_finish_reminder_title))
            .setContentText(context.getString(R.string.forgotten_finish_reminder_text))
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
        private const val REQUEST_CODE_CONTENT = 100
    }
}
