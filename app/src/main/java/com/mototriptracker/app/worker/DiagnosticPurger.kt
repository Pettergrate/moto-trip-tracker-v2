package com.mototriptracker.app.worker

import com.mototriptracker.app.BuildConfig
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.tracking.persistence.RawPointWriter
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * DIA-004 / F0.13 §12.2: "conservar hasta 14 días; máximo objetivo inicial de 20,000 eventos; purgar por
 * antigüedad y luego por capacidad; nunca purgar datos de dominio/raw por esta política". The goal is that
 * a recent problem stays investigable without the app becoming an unbounded logging system - which it
 * would, now that several tasks write events per outage, per gap and per process exit.
 *
 * It only ever deletes `diagnostic_event` rows. That table has no foreign key to Trips, captures or raw
 * points on purpose (`DiagnosticEventEntity`), so a purge cannot reach domain data by construction; a test
 * pins it anyway.
 *
 * **One deliberate exception: [EXEMPT_TYPES].** Trip Detail's "some points could not be saved" note is derived
 * from `DATA_LOSS_DETECTED` rows. Purging them after 14 days would silently remove a warning about a trip
 * that still exists, so they are kept. They are tiny and rare (one per outage that lost points), so this
 * does not defeat the bound - it is a *documented* carve-out, not an oversight. The proper home for that fact
 * is domain data (a later schema change); until then, the events stay.
 */
class DiagnosticPurger @Inject constructor(
    private val diagnosticEventDao: DiagnosticEventDao,
    private val clock: Clock,
    private val idGenerator: IdGenerator
) {
    data class Result(val purgedByAge: Int, val purgedByCapacity: Int) {
        val total: Int get() = purgedByAge + purgedByCapacity
    }

    /** The limits are parameters only so a test can exercise the capacity rule without inserting 20,000 rows. */
    suspend fun purge(retentionMs: Long = RETENTION_MS, maxEvents: Int = MAX_EVENTS): Result {
        val cutoff = clock.wallClockMillis() - retentionMs
        val byAge = diagnosticEventDao.deleteOlderThan(cutoff, EXEMPT_TYPES)
        val excess = diagnosticEventDao.count() - maxEvents
        val byCapacity = if (excess > 0) diagnosticEventDao.deleteOldest(excess, EXEMPT_TYPES) else 0
        val result = Result(byAge, byCapacity)
        // Silent when there was nothing to do: a daily "nothing happened" row would be the noise this exists to prevent.
        if (result.total > 0) recordPurge(result)
        return result
    }

    private suspend fun recordPurge(result: Result) {
        diagnosticEventDao.insert(
            DiagnosticEventEntity(
                eventId = idGenerator.newId(),
                occurredAt = clock.wallClockMillis(),
                elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                category = DiagnosticCategory.PROCESSING_WORKER,
                eventType = EVENT_PURGE_COMPLETED,
                severity = DiagnosticSeverity.INFO,
                source = "diagnostic-purger",
                captureId = null,
                tripId = null,
                correlationId = null,
                stateBefore = null,
                stateAfter = null,
                reasonCode = if (result.purgedByCapacity > 0) "RETENTION_AGE_AND_CAPACITY" else "RETENTION_AGE",
                metadata = mapOf(
                    "purgedByAge" to result.purgedByAge.toString(),
                    "purgedByCapacity" to result.purgedByCapacity.toString()
                ),
                appVersion = BuildConfig.VERSION_NAME,
                schemaVersion = 1,
                detectorVersion = DetectorVersion(0),
                locationProfileVersion = LocationProfileVersion(0),
                processingVersion = ProcessingVersion(0)
            )
        )
    }

    companion object {
        /** F0.13 §12.2's baseline, "sujetos a medición durante Fase 1". */
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(14)
        const val MAX_EVENTS = 20_000
        const val EVENT_PURGE_COMPLETED = "DIAGNOSTIC_PURGE_COMPLETED"

        /** See the class KDoc: a domain-visible note depends on these, so they outlive the window. */
        val EXEMPT_TYPES: List<String> = listOf(RawPointWriter.EVENT_DATA_LOSS)
    }
}
