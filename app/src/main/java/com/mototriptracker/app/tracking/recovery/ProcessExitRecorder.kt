package com.mototriptracker.app.tracking.recovery

import com.mototriptracker.app.BuildConfig
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.ProcessingVersion
import javax.inject.Inject

/**
 * DIA-004 / F0.13 §9 + §5.9 ("proceso anterior detectado como terminado; exit reason"): turns the platform's
 * short, OEM-dependent history of how earlier processes ended into diagnostic events that outlive it, so a
 * gap in a ride can be explained days later ("the process was killed for low memory at 14:33 while recording")
 * instead of only while the platform still remembers.
 *
 * Evidence, never a decision: this records and does nothing else. Whether a capture is sealed after a user stop
 * is [UserStopReconciler]'s separate job with its own cursor (F0.10 §24: `ApplicationExitInfo` "no es source of
 * truth del Trip").
 *
 * Idempotent two ways: a cursor (the newest exit already recorded) means a normal run only looks at what is new,
 * and each event's id is derived from the exit itself, so even a lost cursor cannot duplicate one. The cursor only
 * moves *after* the event is written, so a failed write is retried at the next start instead of skipped.
 */
class ProcessExitRecorder @Inject constructor(
    private val reader: ProcessExitReasonReader,
    private val store: HandledExitStore,
    private val diagnosticEventDao: DiagnosticEventDao
) {
    /** @return how many exits were newly recorded. */
    suspend fun record(): Int {
        val cursor = store.lastRecordedExitTimestamp()
        val fresh = reader.recentExits().filter { it.timestampMillis > cursor }.sortedBy { it.timestampMillis }
        for (exit in fresh) {
            diagnosticEventDao.insertOrIgnore(event(exit))
            store.markExitRecorded(exit.timestampMillis)
        }
        return fresh.size
    }

    private fun event(exit: ProcessExitRecord) = DiagnosticEventEntity(
        eventId = exit.id,
        // The event is about the moment the process ended, not about when this run noticed.
        occurredAt = exit.timestampMillis,
        elapsedRealtimeNanos = null,
        category = DiagnosticCategory.RECOVERY_SYSTEM,
        eventType = EVENT_PROCESS_EXIT,
        severity = severityFor(exit.reason),
        source = "process-exit-recorder",
        captureId = null,
        tripId = null,
        correlationId = null,
        stateBefore = null,
        stateAfter = null,
        reasonCode = exit.reason,
        metadata = buildMap {
            put("importance", exit.importance)
            put("status", exit.status.toString())
            exit.stateSummary?.let { put("stateSummary", it) }
        },
        appVersion = BuildConfig.VERSION_NAME,
        schemaVersion = 1,
        detectorVersion = DetectorVersion(0),
        locationProfileVersion = LocationProfileVersion(0),
        processingVersion = ProcessingVersion(0)
    )

    companion object {
        const val EVENT_PROCESS_EXIT = "PROCESS_EXIT"

        /** F0.13 §6: a crash or ANR is an error; the system reclaiming the app is a warning; the user or an update ending it is information. */
        fun severityFor(reason: String): DiagnosticSeverity = when (reason) {
            "CRASH", "CRASH_NATIVE", "ANR", "INITIALIZATION_FAILURE" -> DiagnosticSeverity.ERROR
            "LOW_MEMORY", "EXCESSIVE_RESOURCE_USAGE", "SIGNALED", "DEPENDENCY_DIED", "FREEZER" -> DiagnosticSeverity.WARN
            else -> DiagnosticSeverity.INFO
        }
    }
}
