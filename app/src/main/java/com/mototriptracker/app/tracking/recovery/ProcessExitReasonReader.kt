package com.mototriptracker.app.tracking.recovery

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** REC-004: the one fact about how the previous process ended that the recovery policy cares about. */
data class ProcessExit(val timestampMillis: Long, val wasUserRequested: Boolean)

/**
 * REC-004/F0.10 §24: `ApplicationExitInfo` is a *diagnostic* input - "no es
 * source of truth del Trip y no está garantizado que todos los OEM reporten
 * cada causa con igual detalle" - so an absent or unrecognised answer must
 * always mean "do nothing special", never "assume the worst".
 */
interface ProcessExitReasonReader {
    /** The most recent recorded exit of this app's *previous* processes, or `null` if none/unavailable (API < 30). */
    fun latestExit(): ProcessExit?
}

class AndroidProcessExitReasonReader @Inject constructor(
    @ApplicationContext private val context: Context
) : ProcessExitReasonReader {

    override fun latestExit(): ProcessExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        val newest = runCatching {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return null
        // Task Manager "Stop" (Android 13+) and Settings "Force stop" both report REASON_USER_REQUESTED.
        return ProcessExit(newest.timestamp, wasUserRequested = newest.reason == ApplicationExitInfo.REASON_USER_REQUESTED)
    }
}
