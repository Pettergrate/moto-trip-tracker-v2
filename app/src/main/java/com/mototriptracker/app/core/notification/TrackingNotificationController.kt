package com.mototriptracker.app.core.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
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

    companion object {
        const val CHANNEL_ID = "trip_tracking"
        const val NOTIFICATION_ID = 1001
    }
}
