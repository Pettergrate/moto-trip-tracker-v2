package com.mototriptracker.app.diagnostics

import com.mototriptracker.app.tracking.persistence.PersistenceLevel
import com.mototriptracker.app.tracking.persistence.PersistenceState
import java.security.MessageDigest

/**
 * DIA-002: the small pure rules behind the snapshot, kept apart from anything Android so they can be pinned
 * by tests (ADR-013's spirit; this package is not `domain/`, but nothing here needs a framework).
 */
object DiagnosticFormulas {

    /**
     * F0.13 §10.1 "ID abreviado/no reversible para lectura humana": the first 8 hex digits of a SHA-256. Enough
     * to tell captures apart on a screen or across an export, and it does not disclose the real id.
     */
    fun shortId(id: String): String =
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { "%02x".format(it) }

    /**
     * The median gap between consecutive fixes, from their `elapsedRealtimeNanos` (newest first or oldest first
     * - only the differences matter). The median, not the mean: one long silence must not read as "the interval".
     * `null` with fewer than two points, and non-positive gaps (a repeated or out-of-order stamp) are ignored.
     */
    fun effectiveIntervalMs(elapsedRealtimeNanos: List<Long>): Long? {
        if (elapsedRealtimeNanos.size < 2) return null
        val gaps = elapsedRealtimeNanos.sorted().zipWithNext { a, b -> (b - a) / 1_000_000 }.filter { it > 0 }
        if (gaps.isEmpty()) return null
        val sorted = gaps.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }

    /**
     * F0.10 §21 mapped from facts the app really has. `RECOVERY_REQUIRED` is a capture that cannot safely
     * continue without reconciliation; the caller says so through [recoveryRequired] (an ACTIVE capture nobody is
     * recording into). `PERSISTENCE_CRITICAL` outranks everything: if new data may not be getting saved, that is
     * the thing to read first.
     */
    fun health(
        persistence: PersistenceState,
        gapActive: Boolean,
        approximateOnly: Boolean,
        recoveryRequired: Boolean
    ): HealthState = when {
        persistence.level == PersistenceLevel.CRITICAL -> HealthState.PERSISTENCE_CRITICAL
        recoveryRequired -> HealthState.RECOVERY_REQUIRED
        persistence.level == PersistenceLevel.DEGRADED || gapActive || approximateOnly -> HealthState.DEGRADED
        else -> HealthState.HEALTHY
    }
}
