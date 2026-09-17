# Moto Trip Tracker V2
## Phase 0 — Master Document

**Project type:** Greenfield Android application  
**Status:** Phase 0 documentation closed (GO, F0.17); Phase 1 implementation underway — Wave W0 complete; Wave W1 in progress (`TRK-001`, `TRK-002`, `TRK-004`, `PRC-001`, `PRC-002` done); Wave W2 started early (`DET-001`, `DET-002`, `DET-003` done)  
**Version:** 0.25
**Last updated:** 2026-09-17

---

## 1. Purpose

This document is the main index and source of truth for Phase 0 of Moto Trip Tracker V2.

V2 is a new project built from scratch using its own requirements, research and architecture. V1 is outside the current planning and implementation scope; no V1 code, architecture or behavior is inherited.

### Core rule

> No implementation before specification, no architectural decision without rationale, and no feature is considered complete without validation.

---

## 2. Product summary

Moto Trip Tracker V2 is an Android application focused on motorcycle trips.

Its central value is automatic trip detection and recording, combined with:

- GPS route recording
- map visualization
- trip history
- manual start / pause / resume / finish controls
- trip statistics
- favorites
- merge / split operations
- route organization
- offline-first behavior
- recovery after interruptions
- future analytics, route comparison and motorcycle management

Automatic recording is a core differentiator, but it is not the entire product.

---

## 3. Phase 0 objectives

Phase 0 must define the product before implementation begins.

It will establish:

1. Product definition
2. Requirements and scope
3. Trip detection behavior
4. Android platform constraints
5. GPS/location strategy
6. Field-test methodology
7. Domain and data model
8. System architecture
9. UX/navigation structure
10. Reliability and recovery requirements
11. Permissions and privacy
12. Testing strategy
13. Diagnostics and observability
14. Architecture Decision Records (ADRs)
15. V2 implementation roadmap
16. Phase 1 readiness criteria (F0.17; original workstream ID preserved)

---

## 4. Phase 0 document map

| ID | Document | Status |
|---|---|---|
| F0.1 | Product Definition | Closed baseline |
| F0.2 | Requirements & Scope | Closed baseline |
| F0.3 | Trip Detection Specification | Approved baseline |
| F0.4 | Android Platform Research | Closed v0.1 |
| F0.5 | GPS & Location Research | Closed v0.1 |
| F0.6 | Field Experiment Design | Closed design v0.1 |
| F0.7 | Domain & Data Model | Closed conceptual v0.1 |
| F0.8 | System Architecture | Closed technical v0.1 |
| F0.9 | UX & Navigation Architecture | Closed UX v0.1 |
| F0.10 | Reliability & Recovery | Closed reliability v0.1 |
| F0.11 | Privacy & Permissions | Closed privacy v0.1 |
| F0.12 | Testing Strategy | Closed testing v0.1 |
| F0.13 | Observability & Diagnostics | Closed observability v0.1 |
| F0.14 | ADR Baseline | Closed ADR v0.1 |
| F0.15 | V2 Roadmap | Closed roadmap v0.1 |
| F0.17 | Phase 1 Readiness Review | Closed — **GO, scoped to Wave W0**. See `docs/00-master/phase-1-readiness-review.md` |

---

## 5. Development workflow

The project will be developed in controlled stages.

```text
Specification
    ↓
Research
    ↓
Architecture
    ↓
Roadmap
    ↓
Small implementation task
    ↓
Validation
    ↓
Documentation update
    ↓
Next task
```

Codex and Claude Code should receive small, bounded tasks rather than broad instructions to build entire systems.

Each implementation task should eventually contain:

- objective
- context
- dependencies
- allowed scope
- expected files/modules
- acceptance criteria
- tests
- definition of done

---

## 6. V1 policy

By project-owner decision on 2026-09-15, F0.16 is removed. It is not a deliverable or a prerequisite for Phase 1. F0.17 retains its original ID.

V2 is defined from its own requirements, research and accepted architecture decisions.

> Do not inspect or replicate V1 as part of V2 planning or implementation. Any future change to this scope requires an explicit project-owner decision.

---

## 7. Current decision log

### DEC-001 — V2 is greenfield

**Decision:** Build Moto Trip Tracker V2 from scratch.

**Reason:** V1 has accumulated structural and reliability problems. Starting clean allows architecture, documentation and validation to be designed intentionally.

**Status:** Accepted.

### DEC-002 — Phase 0 precedes implementation

**Decision:** Product, research, architecture and roadmap are defined before large-scale implementation.

**Status:** Accepted.

### DEC-003 — Documentation is modular

**Decision:** Use a master Phase 0 index plus focused documents rather than one monolithic file.

**Status:** Accepted.

---

## 8. Current Phase 0 progress

### F0.1 — Product Definition

Closed baseline.

Source:

`docs/01-product/product-definition.md`

**Note (2026-09-15):** this file was created after F0.17 found the reference to it was broken (the file did not previously exist; see `docs/00-master/phase-1-readiness-review.md` Finding H1). It formalizes decisions already accepted and already used by downstream documents — it does not introduce new product scope.

---


### F0.15 — V2 Roadmap

Closed roadmap baseline v0.1.

Source:

`docs/05-roadmap/v2-roadmap.md`

Supporting artifacts:

- `docs/05-roadmap/phase1-backlog.md`
- `docs/05-roadmap/agent-task-template.md`

Key decisions: Phase 1 is dependency-driven rather than calendar-driven; Core is split into W0 Foundation, W1 Manual Recording Vertical Slice, W2 Automatic Detection + Field Freeze, W3 Editing/Everyday Use, W4 Reliability/Permissions/Diagnostics Hardening and W5 Core Release Hardening. F0.6 field validation remains a hard G4 gate before detector/location defaults are frozen. DoR/DoD and agent execution constraints prevent Codex/Claude from inventing product or architecture.

### F0.17 — Phase 1 Readiness Review

Closed — documentation review complete.

Source:

`docs/00-master/phase-1-readiness-review.md`

**Verdict: GO, scoped strictly to starting Wave W0** (`FND-001` through `EXP-001` as defined in `docs/05-roadmap/phase1-backlog.md`). At the time this review closed, it was a documentation-readiness verdict only, with no implementation acted on yet.

The review found no unresolved product or architecture decision blocking W0. It found and corrected one broken reference (`docs/01-product/product-definition.md` did not exist; see the F0.1 note above — it has since been created) and one stale cross-reference in `docs/03-architecture/system-architecture.md` §23. It also flagged, without fixing, a status-label inconsistency between the F0.2/F0.3 source documents (self-declared "Draft baseline") and this master document's document map (which lists them as Closed/Approved) — this is a documentation-coherence recommendation, not a blocker. Detector thresholds, production location sampling profile, map provider, elevation algorithm and diagnostic retention limits remain correctly deferred to their documented gates (`EXP-008`/G4, `MAP-001`, field measurement) and are not frozen by this review.

**Phase 1 progress:** the project owner authorized starting W0 on 2026-09-15. `FND-001` (Android project bootstrap), `FND-002` (architecture/package skeleton + Hilt DI wiring), `FND-003` (Room schema v1 covering all 18 F0.7 entities + migration-test harness, independently reviewed by Codex with fixes applied), `FND-004` (IDs/clocks/version primitives), `TST-001` (deterministic test harness), `CAP-001` (capability resolver — the first real `domain/` logic), `DIA-001` (structured diagnostic event foundation) and `EXP-001` (F0.6 field-test harness shell) are done — **Wave W0 is complete.** Wave W1 (Manual Recording Vertical Slice) is underway: `TRK-001` (foreground tracking service + manual start, coordinator delegates the idempotent check-then-insert to `TripCaptureDao`) is done, verified with a full unit-test pass and real on-device confirmation (Honor DNY-NX9, Android 16) of the foreground notification, exactly-one-ACTIVE-capture invariant, Start idempotency and sticky-restart rehydration. `TRK-002` (FLP location ingestion into Raw Track via a new `LocationGateway` seam) is done, unit-verified (48/48) and confirmed end-to-end on-device: real fixes at the configured 2s/high-accuracy profile, correct nullable-field handling live, idempotent Start (no duplicate location registration) and sticky-restart resume continuing `sequenceNumber` with zero collision. `TRK-004` (Finish capture transaction) followed next rather than `NOT-001`/`TRK-003` — `v2-roadmap.md` §5's dependency spine shows `TRK-002 → TRK-004` directly, and `NOT-001`'s notification Pause/Resume/Finish buttons can't be meaningfully built before the commands they trigger (`TRK-003`, `TRK-004`) exist. `TRK-004` is done, unit-verified (57/57) and confirmed end-to-end on-device, including a real WorkManager/Hilt integration bug (`HiltWorkerFactory` wasn't actually wired by the time WorkManager's on-demand initializer ran) that no unit test could have caught, since all of them use a fake `ProcessingScheduler` by design — fixed and re-verified with a real `TripProcessingWorker` run producing its expected diagnostic event in the production database. `PRC-001` (Point assessment + Processed Track v1) followed directly, continuing the critical spine and finally giving that placeholder Worker a real body: `domain/processing/ProcessingEngine` — a pure, `DomainBoundaryTest`-passing algorithm deliberately scoped to what F0.5 §7.1 licenses without invented thresholds (reject only non-monotonic timestamps; accept everything else; flag gaps past an explicitly-placeholder 30s cutoff). Done, unit-verified (71/71, including a real re-run-is-idempotent proof) and confirmed on-device: a real Start→Finish cycle produced accepted points, matching `ProcessedTrackPoint` rows and a completion diagnostic event with real counts. `PRC-002` (Core distance/time/speed metrics) followed immediately, reusing `ProcessingEngine`'s output directly: `domain/processing/TripMetricsCalculator` computes distance (haversine, excluding the edge across a gap boundary), max/average speed (excluding a gap-boundary point's reported speed — the one zero-threshold spike rejection F0.5 §12.3 actually licenses) and total duration, while deliberately leaving moving/stopped time `null` (no validated speed threshold exists to split them, per FR-MET-003/004's own "when technically reliable"). Done, unit-verified (83/83) and confirmed on-device across two real Start→Finish cycles — both produced honestly `null`/`0` metrics (identical-coordinate fixes, no device-reported speed) rather than fabricated ones, which is exactly the intended behavior when the real evidence doesn't support a number.

`DET-001` (Activity Recognition transitions) came next, at the user's explicit direction rather than the next task this session would itself have recommended — and it's a legitimate branch to take now regardless: `v2-roadmap.md` §5's dependency spine shows `PRC-001 → DET-001` directly (not gated behind `PRC-002`), so this is Wave W2 (Automatic Detection & Field Freeze) starting in parallel with W1's remaining tasks, not out of order. `tracking/activityrecognition/ActivityRecognitionRegistrar` registers the Transition API's 6-type/12-combination vocabulary via a `PendingIntent`-targeted `ActivityTransitionReceiver` (a passive trigger that keeps working even when the app process isn't running — ADR-007), with `BootReceiver` re-registering after boot/update per F0.4 §4.5. Every transition is persisted as an `ACTIVITY_RECOGNITION` `DiagnosticEvent` — no Candidate Start/Stop decision logic, which is `DET-002`/`DET-003`'s job, not this one's. Done, unit-verified (94/94). On-device verification was **honestly partial**: registration wiring and the normal-launch registration call were confirmed crash-free, but neither a real `MY_PACKAGE_REPLACED` re-registration nor a real physical-movement-triggered transition could be confirmed reaching the app within a reasonable wait on the test device (Honor DNY-NX9/Magic OS, no doze-whitelist exemption) — recorded as a manufacturer-specific battery/broadcast-throttling data point per F0.4 §16's own already-accepted risk, not treated as a code defect or worked around from one data point.

`DET-002` (Candidate Start engine) followed, at the user's direction again: `domain/detection/CandidateStartEngine` is a pure, stateful reducer over a merged stream of `DET-001`'s activity transitions and `TRK-002`'s location samples — `IN_VEHICLE` ENTER only opens a candidate; confirming it needs both a minimum elapsed time **and** real straight-line displacement from the candidate's anchor point, so neither activity evidence alone nor a single GPS jump alone can ever start a Trip (this task's own acceptance criterion). Placeholder thresholds (15s/40m/120s max window) are injected via a `CandidateStartProfile`, not hardcoded, per ADR-018's field-gate and F0.12's explicit testability requirement — none are validated against real rides yet. Like `CapabilityResolver` before `AUTO-001` existed, this has no live caller yet: turning a confirmed candidate into an actual auto-started capture is `AUTO-001`'s job. Because nothing calls it yet, there was nothing to verify on-device — done, unit-verified (110/110) entirely through deterministic replay-style tests, matching ADR-018's own design.

`DET-003` (Candidate Stop engine) followed the same day: `domain/detection/CandidateStopEngine` mirrors `CandidateStartEngine` with the trigger reversed (`IN_VEHICLE` EXIT opens a candidate) and a deliberate asymmetry — no displacement check, since F0.3 §7 treats either staying put or walking away as valid evidence the ride ended, so a `WALKING`/`ON_FOOT` ENTER confirms immediately while sustained absence of a re-`ENTER` for a 3-minute grace period (12× longer than start's 15s, per §7's own "intentionally more conservative" framing) confirms otherwise. It leans on Google's own Transition-API filtering of transient `STILL` states rather than re-implementing traffic-light tolerance from raw speed, which is this task's actual acceptance bar. Done, unit-verified (124/124), again with no live caller and nothing to verify on-device yet. See `docs/05-roadmap/phase1-backlog.md` for task-level reports, including dependency-version deviations from F0.8's exact pins discovered by attempting real builds (documented there and in `gradle/libs.versions.toml`).

## 9. Phase 0 completion condition

Phase 0 is complete only when:

- product scope is stable enough to implement
- automatic trip behavior is specified
- critical Android restrictions are researched
- data model is defined
- architecture is documented
- testing and field validation plans exist
- implementation roadmap is decomposed into small tasks
- no unresolved blocker prevents Phase 1

### F0.2 — Requirements & Scope

Initial draft created.

Source:

`docs/01-product/requirements-scope.md`

The document establishes requirement IDs, P0/P1/P2 priorities, Core/Post-Core/Future scope, research-gated decisions and traceability conventions.


### F0.8 — System Architecture

Closed technical baseline v0.1.

Source:

`docs/03-architecture/system-architecture.md`

Key decisions: Android native Kotlin/Compose, Room local source of truth, Navigation 3, Hilt, foreground location service owns live tracking, WorkManager only for deferrable processing, and a single-module bootstrap.


### F0.9 — UX & Navigation Architecture

Closed UX baseline v0.1.

Source:

`docs/03-architecture/ux-navigation.md`

Key decisions: compact primary navigation with Home/History/Favorites, Active Trip as a special persistent destination, ride-first interaction, explicit Pause/Resume/Finish, map-independent Trip Detail, capability-oriented Auto Tracking setup, and safe Merge/Split/Delete flows.


### F0.10 — Reliability & Recovery

Closed reliability baseline v0.1.

Source:

`docs/03-architecture/reliability-recovery.md`

Key decisions: persistent data is the Trip source of truth; same-boot process death attempts same-capture recovery; sticky FGS restart rehydrates from Room; reboot/user stop do not fake continuity; gaps are explicit; Finish/Merge/Split are idempotent and transactional; derived processing uses persistent unique work.


### F0.11 — Privacy & Permissions

Closed privacy/permissions baseline v0.1.

Source:

`docs/03-architecture/privacy-permissions.md`

Key decisions: Core is local-first; precise location is required for reliable tracking; Background Location exists only for Full Auto and is requested incrementally with prominent disclosure; Full Auto also requires notifications enabled by product policy; no broad storage permission, ads SDK or external analytics SDK in Core; sensitive route DB is excluded from Android cloud Auto Backup; sharing/export is explicit and privacy zones affect only shared artifacts.


### F0.12 — Testing Strategy

Closed testing baseline v0.1.

Source:

`docs/04-testing/testing-strategy.md`

Key decisions: risk-based layered testing; deterministic detector/location replay; fake clocks; Room migration/transaction tests; merge/split invariant tests; foreground-service/recovery integration tests; Compose/accessibility tests; privacy/performance checks; and commit/merge/release/detector-freeze gates.


### F0.13 — Observability & Diagnostics

Closed observability baseline v0.1.

Source:

`docs/04-testing/observability-diagnostics.md`

Key decisions: local-first evidence rather than remote telemetry; structured DiagnosticEvent separate from RawTrack/domain data; detector reason codes and versioning; Debug Screen; ApplicationExitInfo/process state summary; privacy-safe diagnostic ZIP; bounded technical retention; Perfetto/Tracing/StrictMode for development diagnostics.


### F0.14 — ADR Baseline

Closed ADR baseline v0.1.

Source:

`docs/adr/README.md`

Twenty Accepted ADRs now constrain greenfield policy, Android stack, persistence, live tracking ownership, capture/trip separation, raw/processed data, detection, permissions, local-first privacy, WorkManager scope, navigation, bootstrap modularity, domain isolation, versioning, transactional/idempotent operations, gaps, observability, testing/field freeze, map isolation and the single-active-capture invariant.
