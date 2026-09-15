# F0.1 — Product Definition
## Moto Trip Tracker V2

**Status:** Closed baseline (formalized 2026-09-15)
**Version:** 1.0
**Project:** Moto Trip Tracker V2
**Phase:** 0 — Discovery & Design
**Depends on:** none (root workstream)

---

## 0. Note on this document's origin

This document did not exist as a standalone file until now. `docs/00-master/phase-0-master.md` referenced it since the start of Phase 0, but the file was never created — a broken reference identified and corrected by `docs/00-master/phase-1-readiness-review.md` (Finding H1).

This document does **not** introduce new product decisions. It consolidates decisions already accepted and already in active use by downstream documents (`docs/01-product/requirements-scope.md`, `docs/03-architecture/*`, `docs/adr/ADR-001-greenfield-v2.md`, and the roadmap), drawn from:

- `docs/00-master/phase-0-master.md` §2 "Product summary";
- `docs/01-product/requirements-scope.md` §6-9 (Core/Post-Core/Future/Out-of-scope boundaries, which already state they derive from F0.1);
- `docs/adr/ADR-001-greenfield-v2.md`;
- the historical consolidated snapshot `Fase_0_Documento_Maestro_Moto_Trip_Tracker_V2.docx` §4 (used only as a cross-check that nothing here contradicts the original intent, not as an authoritative source).

Because it formalizes already-accepted, already-used content rather than opening new decisions, it is recorded directly as **Closed baseline** rather than Draft.

---

## 1. Purpose

Convert the idea of Moto Trip Tracker V2 into a concrete product definition before any API, library or line of code is chosen. This is the root document: every later workstream (F0.2 requirements, F0.3 detection, F0.4-F0.13 research/architecture/testing, F0.14 ADRs, F0.15 roadmap) traces back to the decisions recorded here.

---

## 2. Problem

Manually recording motorcycle rides requires the rider to remember to start and stop a tracker. Automatic detectors built on a single speed/distance threshold tend to fail against traffic lights, congestion, short stops and ordinary GPS noise — either missing real rides or fragmenting them into false trips.

## 3. Product proposal

An Android application that automatically detects motorized trips compatible with a motorcycle ride, records the route, processes trip statistics, and keeps a consultable, correctable history — while also offering complete manual control (start/pause/resume/finish) for when automation is unavailable, disabled, or wrong.

## 4. Differentiator

Automation is not an invisible on/off switch driven by one rigid threshold. The detector is a stateful machine built on accumulated, multi-signal evidence with hysteresis between start and stop conditions, full diagnostic explainability in development builds, and safe fallback to manual control. See `docs/01-product/trip-detection-spec.md` (F0.3) for the full behavioral contract.

## 5. Product principles

- **Automatic by default, manual whenever the user needs it.** Neither mode is optional; both must work.
- **Offline-first.** Losing Internet connectivity must never stop an active trip.
- **Low interaction while riding.** The product must not require touching the phone to work correctly.
- **User's data first.** History, export and backup remain under the user's control.
- **Raw data separated from processed data** wherever that separation preserves traceability and lets algorithms improve without losing evidence.
- **Recoverable.** An interruption (process death, reboot, permission change) must not silently destroy a trip.
- **Explainable.** The detector must be able to show, in development builds, why it made a start/stop decision.
- **Battery-aware.** Passive detection and active tracking use deliberately different power policies.
- **Greenfield.** V2 is built from its own requirements and research; it does not inherit V1 code or architecture (`ADR-001`).

## 6. Initial user

An individual motorcyclist, using their own Android phone. This is not designed, in Core scope, as a multi-user, fleet or commercial product.

## 7. Core scope (high-level)

The following capabilities define the intended Core V2 baseline at the product-vision level. `docs/01-product/requirements-scope.md` (F0.2) converts this into numbered, priority-tagged functional/non-functional requirements (`FR-*`, `NFR-*`) — that document, not this one, is the authoritative traceability source for implementation tasks.

- Automatic trip candidate detection, start and stop.
- Manual start, pause, resume and finish.
- Offline-capable GPS route recording, with raw and processed track kept as separate concepts.
- Trip metrics: distance, duration, moving/stopped/paused time, filtered speed, elevation where reliable.
- Persistent active-trip notification with direct controls.
- Trip map, trip history, trip detail.
- Favorite trips, delete trips.
- Merge and split trips.
- Basic recovery after interruption.
- Diagnostic/event logging sufficient to investigate detection and recording failures.

## 8. Post-Core scope (high-level)

Expected after the recording foundation is stable: reusable Routes, route favorites, search/filtering, notes/tags, markers, calendar view, advanced charts, aggregate analytics, multiple Motorcycle profiles, GPX export, backup/restore, shareable trip cards, privacy zones, widgets/Quick Settings, route similarity detection.

## 9. Future scope (high-level)

Potential expansions with no implementation commitment: maintenance tracking, fuel/economy tracking, weather enrichment, road/off-road classification, cloud sync, GPX import, advanced route comparison, photo/location integration, additional external integrations.

## 10. Explicitly out of scope for Core

Turn-by-turn navigation; complex route planner; public social network or chat; public live tracking; public speed rankings or gamified speed competition; marketplace; full vehicle-maintenance platform; mandatory cloud account; mandatory Internet connection for trip recording.

## 11. V1 policy

V1 is legacy/reference only and is not inspected or replicated as part of V2 planning or implementation (`ADR-001`; `docs/00-master/phase-0-master.md` §6). Any change to this policy requires an explicit project-owner decision.

## 12. Traceability

- Converted into numbered requirements by `docs/01-product/requirements-scope.md` (F0.2).
- Converted into detection behavior by `docs/01-product/trip-detection-spec.md` (F0.3).
- Formalized structurally by `docs/adr/ADR-001-greenfield-v2.md` and `docs/adr/ADR-009-local-first-no-backend-core.md`.
- Referenced by `docs/00-master/phase-0-master.md` §2 and §4 (document map).

## 13. F0.1 closure

- [x] Problem, proposal, differentiator and principles are explicit.
- [x] Initial user is defined.
- [x] Core/Post-Core/Future boundaries are defined and match what F0.2 already builds on.
- [x] Explicit out-of-scope list exists.
- [x] V1 policy is stated.
- [x] Content matches — does not contradict — the historical consolidated snapshot and every downstream document that already depends on F0.1.

**Decision:** F0.1 is recorded as **Closed baseline**, formalizing decisions already in effect since the start of Phase 0.
