# F0.16 — V1 Reference (Feature Parity & Data Migration Planning)

**Status:** Reinstated, scoped v0.1
**Date:** 2026-09-19
**Reopens:** the narrow "no V1 inspection" clause of `ADR-001` / `phase-0-master.md` §6, by explicit project-owner decision on 2026-09-19.
**Does not reopen:** `DEC-001` (V2 is greenfield). V2's architecture, domain model and decisions remain independently derived from its own requirements (F0.1-F0.15) and are not being revisited against V1.

---

## 1. Why this exists, and why it's narrower than a full retrospective

F0.16 was removed on 2026-09-15 (`ADR-001`, `phase-0-master.md` §6, `v2-roadmap.md` §15/16) specifically to keep V2's early design uncontaminated by V1's accumulated structural and reliability problems. That reasoning still holds for architecture. It does not hold for two practical questions that only a real V1 codebase can answer, and that only matter now that V2 has enough of its own architecture built (Wave W0/W1 done, Wave W2 underway) to compare against something concrete:

1. **Is V2 on track to cover what V1's users already rely on?** ("feature parity")
2. **What happens to the trips already recorded in V1's real, installed pilot build when/if a user moves to V2?** ("data migration")

The project owner selected exactly these two scopes and explicitly declined a third option offered ("lessons learned from V1" as a retrospective on V1's own architecture/mistakes), preferring instead to keep learning from V2's own work as it happens. This document is deliberately silent on V1's internal architecture, code quality, or the reasons it accumulated debt — that ground was already covered by `ADR-001` and is out of scope here. It only asks "what does V1 do" and "what data does V1 hold", not "how is V1 built" or "what should V2 learn from how V1 was built."

This document defines **methodology**, the same way F0.6 defines a field-experiment protocol without itself running an experiment. The actual checklist and the actual migration design are separate, trackable Phase 1 backlog tasks (`REF-001`, `REF-002`), not sections of this file.

## 2. What was verified about V1 to ground this document

Real V1 codebase at `C:\proyectos\moto-trip-tracker` (separate project, application ID `com.tumarca.mototracker`, distinct from V2's `com.mototriptracker.app`). It has a real installed **pilot build** on a real phone with real recorded trips, explicitly excluded from Android's automatic backup for privacy and which must never be uninstalled as part of any F0.16 follow-on work.

Confirmed by reading `README.md` and `docs/data-model.md` (not yet a full read of `docs/roadmap.md`, `docs/architecture.md`, or the ADRs — deferred to `REF-001`/`REF-002` execution, since a methodology document doesn't need the full detail, only enough to describe the shape of the comparison):

- **Feature set (through "Etapa 8" of V1's own roadmap):** automatic start/stop via Activity Recognition (validated in real rides), manual start/stop, History + Trip Detail with local route rendering and an already-integrated MapLibre Compose base map, custom trip naming (`customName`, ≤60 chars), favorites (`isFavorite`), process-death/reboot recovery that closes an in-flight trip as `INTERRUPTED` using last-persisted evidence (never invents duration, never auto-resumes after reboot — philosophically close to V2's own `REC-001`), permission-revocation and location-disabled handling (`INTERRUPTED`/`PERMISSION_REVOKED`, `INTERRUPTED`/`LOCATION_DISABLED`), and privacy-scoped auto-start diagnostic logging that explicitly avoids storing coordinates, routes, speeds, timestamps or exception messages.
- **Data model (conceptual, `docs/data-model.md`):** a **flat** model — one `Trip` row carries identity, status, start/end reasons, and already-computed summary statistics (distance, durations, speeds, altitude bounds, point count) directly, with no raw/processed separation and no independent statistics versioning. `TrackPoint` rows are **already-filtered/accepted** points tied 1:1 to a `Trip` by `(tripId, sequence)`; V1 never persisted a separate raw/unfiltered stream, so points it rejected during capture are not recoverable from the database — they were simply never written. Settings live in DataStore, not Room. Deletion cascades via a plain SQLite foreign key, with no separate lineage or edit-operation trail.
- **This is structurally different from V2**, which (per `ADR-005`/`ADR-006`/`ADR-014`) already has separate tables for capture identity, raw points, processed points, point-level quality assessment, derived/versioned statistics, trip parts/lineage (for merge/split), and edit operations — none of which V1 has an equivalent for. A V1 import is therefore not a table-to-table copy; V1's `Trip` maps to a *synthesis* of several V2 concepts, and V1's `TrackPoint` maps most closely to V2's **processed** track points (not raw — V1 never captured a raw stream to import).

That asymmetry is the single most important real fact this document contributes: it tells `REF-002` up front that "migrate V1 data" cannot mean "insert V1 rows into V2 tables," and it must instead mean "reconstruct the closest honest V2 representation of what V1 recorded, and clearly mark what cannot be reconstructed" (e.g. no raw track, no rejected-point history, no per-point quality assessment, no `processingVersion` provenance).

## 3. Methodology — Feature Parity Checklist (executed by `REF-001`)

1. Enumerate V1's user-facing features from its own `README.md` and `docs/roadmap.md` (Etapa 0-8), at the feature level, not the implementation level.
2. For each V1 feature, classify it against V2's actual current state (not V2's aspirational docs) into exactly one of:
   - **Covered** — a Phase 1 task already shipped it (cite the task ID and commit).
   - **Planned** — a Phase 1 backlog task exists for it (cite the task ID).
   - **Gap** — V1 has it, V2's backlog does not mention it. Needs a new backlog entry or an explicit owner decision to exclude it.
   - **Deliberately excluded** — V2's product docs (F0.1/F0.2) already decided against it. Cite the source.
3. Produce a single checklist table (feature | V1 evidence | V2 status | task ID / decision). This table is `REF-001`'s deliverable, not something to pre-build here without having read V1's full roadmap and V2's full backlog side by side.
4. Do not use this checklist to justify copying V1's implementation approach for any "Gap" item — closing a gap still goes through V2's normal specification → architecture → task flow. Parity is about *what*, never *how*.

## 4. Methodology — Data Migration Planning (executed by `REF-002`)

1. Treat this as **planning/design**, not implementation — per the project owner's scope selection, `REF-002`'s deliverable is a design document (entity-by-entity mapping, open questions, explicit unrecoverable-data list), not working migration code, unless a later explicit decision asks for execution.
2. Map V1's flat `Trip` fields to the closest honest V2 equivalent across V2's actual tables (`TripEntity`, `TripCaptureEntity`, `TripStatisticsEntity`, etc.), field by field, flagging every V1 field that has no direct V2 home and every V2 field that has no V1 source (e.g. `processingVersion`, raw/processed point separation, point-level quality assessment, lineage links).
3. Build an explicit `endReason` (V1) → `EndSource`-style (V2) mapping table; the enums are not identical (V1: `AUTO_STOP, MANUAL_STOP, PERMISSION_REVOKED, LOCATION_DISABLED, SERVICE_STOPPED, PROCESS_RECOVERY, ERROR`), and any case that doesn't map cleanly must be called out rather than silently coerced.
4. Decide, as an explicit written recommendation (not a silent default): whether a V1 trip imports as a normal V2 Trip with statistics computed once at import time and never re-versioned against `processingVersion`, or with some other honest "imported legacy data" marker so it's never confused with a trip V2 itself measured end-to-end.
5. Decide whether import is a one-time user-triggered action, and whether it's mandatory, optional, or out of scope for the initial V2 release — this is a product decision the design doc should surface, not resolve unilaterally.
6. Explicitly list what cannot be migrated at all given V1's schema (raw/unfiltered points, rejected points, per-point quality assessment, edit/lineage history) so users are never told a migration is "complete" when it is necessarily partial.
7. Never touch, modify, or uninstall the real V1 pilot install as part of this design work; any real import execution (if ever approved) must read a copy of the V1 database, never the live one in use for real riding.

## 5. What this document explicitly does not do

- It does not re-litigate `DEC-001`/`ADR-001`'s core greenfield decision.
- It does not evaluate V1's architecture, code quality, or the causes of its technical debt (that was option 1 in the scope decision and was explicitly declined).
- It does not itself produce the parity checklist or the migration design — see `REF-001`/`REF-002`.
- It does not authorize copying V1 code into V2.

## 6. Traceability

- `ADR-001` — narrow reopening note added, core decision unchanged.
- `phase-0-master.md` §6 — dated note recording this reinstatement.
- `v2-roadmap.md` §15/16 — dated note recording this reinstatement.
- `phase1-backlog.md` — `REF-001`, `REF-002` added.
