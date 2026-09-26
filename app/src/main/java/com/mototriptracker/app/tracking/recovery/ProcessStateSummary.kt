package com.mototriptracker.app.tracking.recovery

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DIA-004 / F0.13 §9.1: a compact, PII-free description of what the process was doing, small enough for
 * `ActivityManager.setProcessStateSummary` (128 bytes). The platform keeps it with the process; when that
 * process dies, `ApplicationExitInfo` hands it back to the *next* one - so a `PROCESS_EXIT` row can say not
 * only "SIGNALED" but "SIGNALED while recording, paused=N, saving problem" without the app having had any
 * chance to write anything as it died. It is a diagnostic hint only, "nunca persistencia principal": what to
 * restore always comes from Room.
 *
 * Fixed vocabulary on purpose (no free text, no ids, no coordinates), e.g. `v=1;cap=Y;pause=N;sig=OK;apx=N;db=OK;app=0.1-w0`.
 */
data class ProcessStateSummary(
    val recording: Boolean = false,
    val paused: Boolean = false,
    /** `OK`, `GAP` (no fixes) or `OFF` (Location Services off). */
    val signal: String = SIGNAL_OK,
    /** Only approximate location is allowed (ADR-022). */
    val approximateOnly: Boolean = false,
    /** `OK`, `DEG` (saving late, held in memory) or `CRIT` (storage full / points lost). */
    val persistence: String = PERSISTENCE_OK
) {
    fun encode(appVersion: String): String {
        val safeVersion = appVersion.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }.take(MAX_VERSION_CHARS)
        return "v=1;cap=${yn(recording)};pause=${yn(paused)};sig=$signal;apx=${yn(approximateOnly)};db=$persistence;app=$safeVersion"
    }

    private fun yn(value: Boolean) = if (value) "Y" else "N"

    companion object {
        /** `ActivityManager.setProcessStateSummary`'s documented limit. */
        const val MAX_BYTES = 128
        const val SIGNAL_OK = "OK"
        const val SIGNAL_GAP = "GAP"
        const val SIGNAL_OFF = "OFF"
        const val PERSISTENCE_OK = "OK"
        const val PERSISTENCE_DEGRADED = "DEG"
        const val PERSISTENCE_CRITICAL = "CRIT"
        private const val MAX_VERSION_CHARS = 24
    }
}

/** Where a summary goes: the platform on API 30+, nowhere below. A seam so nothing needs a real `ActivityManager` in tests. */
interface ProcessStateSummaryPublisher {
    fun publish(encoded: String)
}

class AndroidProcessStateSummaryPublisher @Inject constructor(
    @ApplicationContext private val context: Context
) : ProcessStateSummaryPublisher {
    override fun publish(encoded: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val bytes = encoded.toByteArray(Charsets.UTF_8)
        if (bytes.size > ProcessStateSummary.MAX_BYTES) return // never truncate mid-token: better no summary than a misleading one
        runCatching { context.getSystemService(ActivityManager::class.java)?.setProcessStateSummary(bytes) }
    }
}

/**
 * Holds the current [ProcessStateSummary] and publishes it **only when it changes** (F0.13 §9.1: "se actualiza
 * solo en transiciones significativas, no por cada punto GPS"). One per process, updated by whoever owns the
 * fact (the tracking service).
 */
@Singleton
class ProcessStateTracker @Inject constructor(
    private val publisher: ProcessStateSummaryPublisher,
    private val appVersion: AppVersionName
) {
    private var current = ProcessStateSummary()
    private var lastPublished: String? = null

    @Synchronized
    fun update(transform: (ProcessStateSummary) -> ProcessStateSummary) {
        current = transform(current)
        val encoded = current.encode(appVersion.value)
        if (encoded == lastPublished) return
        lastPublished = encoded
        publisher.publish(encoded)
    }

    /** Back to "not recording": the process is still alive but has nothing in flight. */
    fun reset() = update { ProcessStateSummary() }
}

/** The app's version name as a value Dagger can inject (and a test can fake) instead of reading `BuildConfig` in a `@Singleton`. */
class AppVersionName @Inject constructor() {
    val value: String = com.mototriptracker.app.BuildConfig.VERSION_NAME
}
