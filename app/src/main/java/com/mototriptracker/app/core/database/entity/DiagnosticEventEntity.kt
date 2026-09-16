package com.mototriptracker.app.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.ProcessingVersion

/**
 * Physical form of F0.13 §4's DiagnosticEvent contract (F0.13 itself only
 * asked for the conceptual model, deferring the physical one to "Fase 1" —
 * this is that). Evidence, not telemetry (ADR-017): local-only, no
 * automatic transmission, and structurally excludes what F0.13 §4.1
 * prohibits.
 *
 * Deliberately has **no foreign key** to `TripCaptureEntity`/`TripEntity`:
 * [captureId]/[tripId] are correlation hints, not referential constraints.
 * Diagnostic data has its own independent retention policy (F0.13 §12:
 * ~14 days / ~20,000 events) that must not be entangled with domain data's
 * lifecycle — purging a Trip must not need to touch this table, and this
 * table's own purge must never cascade into domain data (F0.13 §2: "Los
 * datos persistentes de dominio siguen siendo la fuente de verdad").
 *
 * **Load-bearing rule for every future producer (F0.13 §3.1):** do not
 * insert one of these per accepted RawTrackPoint. Location events are for
 * quality *changes*, gaps, rejections and summaries — not a duplicate of
 * the raw track. There is no code enforcing this yet because there is no
 * producer yet (TRK-002/PRC-001); this is the contract they must follow.
 *
 * **Privacy guardrail, enforced by
 * `DiagnosticEventPrivacyTest`:** no field here may be a coordinate or
 * user free-text field (F0.13 §4.1 — no lat/lon, no Trip/Motorcycle name,
 * no user notes). [metadata] is a small, allowlisted map for codes/counters
 * only (`accuracyBucket=POOR`, `rejectedPoints=4`), never arbitrary values.
 *
 * [detectorVersion]/[locationProfileVersion]/[processingVersion]/
 * [schemaVersion]/[appVersion] are a version *stamp* — the versions active
 * when this event was recorded, on every event regardless of category, not
 * "the version this specific event is about" (F0.13 §4 doesn't mark any of
 * them optional).
 */
@Entity(
    tableName = "diagnostic_event",
    indices = [
        Index(value = ["category"]),
        Index(value = ["severity"]),
        Index(value = ["occurredAt"]),
        Index(value = ["captureId"]),
        Index(value = ["tripId"]),
        Index(value = ["correlationId"])
    ]
)
data class DiagnosticEventEntity(
    @PrimaryKey val eventId: String,
    val occurredAt: Long,
    val elapsedRealtimeNanos: Long?,
    val category: DiagnosticCategory,
    val eventType: String,
    val severity: DiagnosticSeverity,
    val source: String,
    val captureId: String?,
    val tripId: String?,
    val correlationId: String?,
    val stateBefore: String?,
    val stateAfter: String?,
    val reasonCode: String?,
    val metadata: Map<String, String>,
    val appVersion: String,
    val schemaVersion: Int,
    val detectorVersion: DetectorVersion,
    val locationProfileVersion: LocationProfileVersion,
    val processingVersion: ProcessingVersion
)
