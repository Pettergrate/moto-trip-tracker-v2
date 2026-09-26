package com.mototriptracker.app.feature.debug

import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.diagnostics.DiagnosticFormulas
import com.mototriptracker.app.diagnostics.DiagnosticSnapshot

/**
 * DIA-002 / F0.13 §10.1's "Events" section: a timeline filterable by category and severity, and searchable by
 * reason code. [minSeverity] is a floor (`WARN` shows WARN and ERROR).
 */
data class EventFilter(
    val category: DiagnosticCategory? = null,
    val minSeverity: DiagnosticSeverity? = null,
    val query: String = ""
)

/** One timeline row, already reduced to what is safe to show: codes, counters and a shortened capture label - no coordinates, no real ids. */
data class EventRow(
    val eventId: String,
    val occurredAt: Long,
    val category: DiagnosticCategory,
    val eventType: String,
    val severity: DiagnosticSeverity,
    val reasonCode: String?,
    val shortCaptureId: String?,
    val metadata: String?
)

data class DebugUiState(
    val snapshot: DiagnosticSnapshot? = null,
    val events: List<EventRow> = emptyList(),
    /** How many stored events matched the filter before the display cap. */
    val matchingCount: Int = 0,
    val filter: EventFilter = EventFilter()
)

/** The rules behind the timeline, pure so they can be pinned by tests. */
object DebugEventFiltering {

    /** Shown at once; the table itself is bounded (14 days / 20,000) but a screen should not build thousands of rows. */
    const val DISPLAY_CAP = 200

    fun matches(event: DiagnosticEventEntity, filter: EventFilter): Boolean {
        if (filter.category != null && event.category != filter.category) return false
        if (filter.minSeverity != null && event.severity.ordinal < filter.minSeverity.ordinal) return false
        val query = filter.query.trim()
        if (query.isEmpty()) return true
        // "búsqueda por reason code" - the event type is searchable too, it is what people actually type.
        return event.reasonCode?.contains(query, ignoreCase = true) == true || event.eventType.contains(query, ignoreCase = true)
    }

    fun toRow(event: DiagnosticEventEntity) = EventRow(
        eventId = event.eventId,
        occurredAt = event.occurredAt,
        category = event.category,
        eventType = event.eventType,
        severity = event.severity,
        reasonCode = event.reasonCode,
        shortCaptureId = event.captureId?.let(DiagnosticFormulas::shortId),
        metadata = event.metadata.entries.joinToString(" ") { "${it.key}=${it.value}" }.ifEmpty { null }
    )
}
