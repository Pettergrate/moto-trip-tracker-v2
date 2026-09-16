package com.mototriptracker.app.core.model

/**
 * Conceptual taxonomies from docs/03-architecture/domain-data-model.md (F0.7) §5.
 * These are plain Kotlin enums with zero Android dependency (ADR-013) so they
 * can be shared by core.database entities, domain logic and UI alike.
 */

/** [TripCaptureEntity] lifecycle. RECOVERED is not a final status — see F0.7 §5. */
enum class CaptureStatus { ACTIVE, COMPLETED, ABORTED }

/** [TripEntity] lifecycle. */
enum class TripStatus { ACTIVE, COMPLETED, SUPERSEDED, TRASHED }

/** How a capture/trip started. IMPORTED is reserved for a future import feature. */
enum class StartSource { AUTO, MANUAL, RECOVERY, IMPORTED }

/** How a capture/trip ended. */
enum class EndSource { AUTO, MANUAL, RECOVERY, ABORTED }

/** General provenance tag for data not covered by a more specific taxonomy. */
enum class DataOrigin { SYSTEM, USER, DETECTED, IMPORTED }

/** [TripEditOperationEntity] type. */
enum class EditOperationType { MERGE, SPLIT, BOUNDARY_EDIT, RESTORE }

/** Role of a [TripEntity] within a [TripLineageLinkEntity]. */
enum class LineageRole { INPUT, OUTPUT }

/** Assessment outcome for a raw point once a processing pipeline runs. */
enum class TrackPointDecision { ACCEPTED, SUSPECT, REJECTED }

/** Whether a [TripStopEntity] came from the detector or a manual user action. */
enum class StopOrigin { DETECTED, USER }
