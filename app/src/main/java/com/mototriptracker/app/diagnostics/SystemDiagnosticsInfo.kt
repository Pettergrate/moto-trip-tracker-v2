package com.mototriptracker.app.diagnostics

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mototriptracker.app.core.notification.TrackingNotificationController.Companion.NOTIFICATION_ID
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * DIA-002: the handful of platform facts the snapshot needs that are not app state. A seam, so the provider can be
 * tested without a real `PowerManager`/`NotificationManager`, and so an OEM that will not answer degrades to `null`
 * ("unknown") instead of an invented value.
 */
interface SystemDiagnosticsInfo {
    /** Manufacturer and model only. */
    fun deviceSummary(): String
    fun androidApi(): Int
    fun batterySaverOn(): Boolean?

    /** Whether the tracking foreground notification is showing right now; `null` if it cannot be told. */
    fun trackingNotificationShown(): Boolean?
}

class AndroidSystemDiagnosticsInfo @Inject constructor(
    @ApplicationContext private val context: Context
) : SystemDiagnosticsInfo {

    override fun deviceSummary(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    override fun androidApi(): Int = Build.VERSION.SDK_INT

    override fun batterySaverOn(): Boolean? =
        runCatching { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode }.getOrNull()

    override fun trackingNotificationShown(): Boolean? = runCatching {
        context.getSystemService(NotificationManager::class.java)?.activeNotifications?.any { it.id == NOTIFICATION_ID }
    }.getOrNull()
}

/** DIA-002: how the trip-processing work is doing, read-only, without importing WorkManager into the provider. */
interface ProcessingWorkReader {
    suspend fun counts(): ProcessingSection
}

class WorkManagerProcessingWorkReader @Inject constructor(
    private val workManager: WorkManager
) : ProcessingWorkReader {

    override suspend fun counts(): ProcessingSection {
        // WorkManager tags every request with its worker's class name; that is the tag to ask for.
        val infos = runCatching {
            workManager.getWorkInfosByTagFlow(TripProcessingWorker::class.java.name).first()
        }.getOrDefault(emptyList())
        return ProcessingSection(
            pending = infos.count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED },
            running = infos.count { it.state == WorkInfo.State.RUNNING },
            succeeded = infos.count { it.state == WorkInfo.State.SUCCEEDED },
            failed = infos.count { it.state == WorkInfo.State.FAILED },
            maxAttemptCount = infos.maxOfOrNull { it.runAttemptCount } ?: 0
        )
    }
}
