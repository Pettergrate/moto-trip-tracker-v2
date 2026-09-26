package com.mototriptracker.app.tracking.recovery

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** REC-004: the one fact about how the previous process ended that the recovery policy cares about. */
data class ProcessExit(val timestampMillis: Long, val wasUserRequested: Boolean)

/**
 * DIA-004: one recorded end of a previous process, reduced to what is safe and useful to keep as diagnostic
 * evidence. Deliberately **without** `ApplicationExitInfo.description`: it is free text the platform
 * composes, and F0.13 §4.1 allows codes and counters only.
 *
 * @property id stable for a given exit (pid + timestamp), so recording it twice cannot duplicate it.
 * @property reason a stable name for the platform's reason code (`CRASH`, `USER_REQUESTED`, `SIGNALED`...).
 * @property importance how visible the process was when it ended (`FOREGROUND_SERVICE` says a recording was being kept alive).
 * @property status the exit code or signal number the platform reports.
 * @property stateSummary what [ProcessStateSummary] last said before the process died - the app's own
 *   compact state, sanitized to an allowlist; `null` when none was set or it could not be read.
 */
data class ProcessExitRecord(
    val id: String,
    val timestampMillis: Long,
    val reason: String,
    val importance: String,
    val status: Int,
    val stateSummary: String?
)

/**
 * REC-004/F0.10 §24: `ApplicationExitInfo` is a *diagnostic* input - "no es
 * source of truth del Trip y no está garantizado que todos los OEM reporten
 * cada causa con igual detalle" - so an absent or unrecognised answer must
 * always mean "do nothing special", never "assume the worst".
 */
interface ProcessExitReasonReader {
    /** The most recent recorded exit of this app's *previous* processes, or `null` if none/unavailable (API < 30). */
    fun latestExit(): ProcessExit?

    /** DIA-004: the platform's recent history of previous-process exits, newest first; empty if unavailable (API < 30). */
    fun recentExits(): List<ProcessExitRecord> = emptyList()
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

    override fun recentExits(): List<ProcessExitRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val infos = runCatching {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
        }.getOrNull() ?: return emptyList()
        return infos.map { info ->
            ProcessExitRecord(
                id = "process-exit-${info.pid}-${info.timestamp}",
                timestampMillis = info.timestamp,
                reason = reasonName(info.reason),
                importance = importanceName(info.importance),
                status = info.status,
                stateSummary = decodeStateSummary(info.processStateSummary)
            )
        }
    }

    companion object {
        /** How many recent exits to look at per run; the platform only keeps a short ring anyway. */
        const val MAX_RECORDS = 16

        /** A stable name for [ApplicationExitInfo]'s `REASON_*` codes; anything unrecognised is `UNRECOGNISED_<n>`, never guessed. */
        @SuppressLint("InlinedApi")
        fun reasonName(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
            else -> "UNRECOGNISED_$reason"
        }

        /** The importance the process had when it ended, by name; `IMPORTANCE_FOREGROUND_SERVICE` is the one that says "a recording was running". */
        fun importanceName(importance: Int): String = when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
            else -> "IMPORTANCE_$importance"
        }

        /**
         * The summary is this app's own text ([ProcessStateSummary]), but it is read back from a process that no
         * longer exists, so it is treated as untrusted: only the exact allowlist survives, capped at the API's 128 bytes.
         */
        fun decodeStateSummary(bytes: ByteArray?): String? {
            if (bytes == null || bytes.isEmpty() || bytes.size > ProcessStateSummary.MAX_BYTES) return null
            val text = bytes.toString(Charsets.UTF_8)
            return text.takeIf { candidate -> candidate.all { it in ALLOWED } }
        }

        private val ALLOWED: Set<Char> = (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('=', ';', '.', '_', '-')).toSet()
    }
}
