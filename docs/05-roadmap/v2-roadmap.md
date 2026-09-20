# F0.15 — V2 Roadmap
## Moto Trip Tracker V2

**Status:** Closed baseline  
**Version:** 0.1  
**Date:** 2026-09-15

---

## 1. Purpose

This roadmap converts the accepted Phase 0 product, research, architecture, reliability, privacy, testing, observability and ADR baselines into an implementation sequence that Codex/Claude Code can execute without inventing product behavior or architecture.

The roadmap is **dependency-driven, not calendar-driven**. It defines order, gates and bounded deliverables. It does not promise dates.

### Core rule

> A task is ready for implementation only when its product behavior, architecture constraints, acceptance criteria and validation method are already known.

If implementation exposes a missing decision, the task stops and creates a focused research/ADR/documentation issue instead of silently choosing a new architecture.

---

## 2. Inputs and constraints

F0.15 is derived from:

- F0.1–F0.2: product and requirements;
- F0.3: detector state model and behavioral contract;
- F0.4–F0.6: Android, GPS and field-validation constraints;
- F0.7: domain/data model;
- F0.8: system architecture;
- F0.9: UX/navigation;
- F0.10: reliability/recovery;
- F0.11: privacy/permissions;
- F0.12: testing gates;
- F0.13: observability/diagnostics;
- F0.14: ADR-001 through ADR-020.

Accepted ADRs constrain implementation. A roadmap task cannot override them.

---

## 3. Release strategy

Implementation is split into three release trains.

### Train A — Core Recorder

Goal: a reliable offline-first motorcycle trip recorder with manual and automatic flows, recoverability, route visualization, history, editing and diagnostics.

This is the **Phase 1 implementation target**.

### Train B — Ride Journal & Routes

Goal: route organization, repeated-route intelligence, richer analytics, tags/notes/media and import/export improvements.

This begins only after Core reliability gates are satisfied.

### Train C — Motorcycle Ownership

Goal: multiple motorcycles, mileage attribution, maintenance/fuel and later optional cloud features.

This remains Post-Core/Future unless scope is explicitly changed.

---

## 4. Phase 1 waves

### Wave W0 — Foundation & Experiment Enablement

**Objective:** create the smallest architecture that can support deterministic tests and real field evidence.

Exit condition: project builds, architecture boundaries exist, Room schema exists, diagnostic/test harness exists, capability state is observable, and no production tracking behavior has been invented.

Primary tasks:

- `FND-001` Android project bootstrap.
- `FND-002` architecture/package skeleton and DI baseline.
- `FND-003` Room schema v1 and migration test harness.
- `FND-004` core IDs/time/version primitives.
- `TST-001` deterministic test infrastructure.
- `DIA-001` structured diagnostic event foundation.
- `CAP-001` capability/permission resolver.
- `EXP-001` internal diagnostic harness shell.

**Gate:** G0/G1 from F0.12 must be operational before W1 expands the codebase.

---

### Wave W1 — Manual Recording Vertical Slice

**Objective:** prove the complete data path using explicit user start/finish before automatic detection is introduced.

The vertical slice must cover:

`User Start → FGS → FLP → RawTrackPoint → Room → Finish → Processing → Trip → History/Detail`

Primary tasks:

- `TRK-001` foreground tracking service and manual start.
- `TRK-002` location source and raw point persistence.
- `TRK-003` manual pause/resume semantics.
- `TRK-004` idempotent finish transaction.
- `NOT-001` active-trip notification and actions.
- `PRC-001` point assessment and processed-track pipeline.
- `PRC-002` distance/time/speed metrics.
- `PRC-003` elevation baseline with explicit quality limitations.
- `UI-001` app shell/Home/active-trip state.
- `HIS-001` trip history and detail.
- `MAP-001` map abstraction plus provider decision spike.
- `REC-001` same-boot process-death recovery.

**Exit condition:** E2E-01 Manual happy path passes offline and survives process recreation without corrupting evidence.

---

### Wave W2 — Automatic Detection & Field Freeze

**Objective:** implement the documented detector contract and collect real evidence before freezing production parameters.

Primary tasks:

- `DET-001` Activity Recognition transition integration.
- `DET-002` Candidate Start engine.
- `DET-003` Candidate Stop engine.
- `DET-004` temporary-stop/hysteresis behavior.
- `DET-005` manual-pause ownership and forgotten-pause warning logic.
- `DET-006` auto-start suppression/duplicate-start protection.
- `AUTO-001` Full Auto / Assisted / Manual orchestration.
- `EXP-002` pilot field campaign.
- `EXP-003` sampling/min-distance/batching campaign.
- `EXP-004` start/stop validation campaign.
- `EXP-005` false-positive vehicle-mode campaign.
- `EXP-006` battery campaign.
- `EXP-007` validation campaign on held-out rides.
- `EXP-008` freeze Detector/Location Profile v1.

**Hard gate:** detector thresholds and location production profile are not considered final until `EXP-008` passes. This is Gate G4 from F0.12.

---

### Wave W3 — Editing, Data Integrity & Everyday Use

**Objective:** make recorded trips safely manageable without compromising raw evidence.

Primary tasks:

- `EDT-001` merge Trips using TripPart lineage.
- `EDT-002` split Trip.
- `EDT-003` boundary correction.
- `EDT-004` recalculation/invalidation after structural edits.
- `TRS-001` delete-to-trash and restore.
- `FAV-001` trip favorites.
- `HIS-002` rename/search/filter/sort.
- `MAP-002` markers/stops/large-track rendering.
- `MET-001` complete Core metric presentation.

**Exit condition:** merge/split/boundary edits are transactional, reversible where specified, preserve Raw Track and pass property/invariant tests.

---

### Wave W4 — Reliability, Permissions & Diagnostics Hardening

**Objective:** close the failure modes that make a tracker untrustworthy in daily use.

Primary tasks:

- `REC-002` sticky service reconstruction.
- `REC-003` reboot boundary reconciliation.
- `REC-004` user-stop/force-stop handling.
- `REC-005` GPS gap and degraded-location behavior.
- `REC-006` persistence-failure bounded buffer/retry.
- `PERM-001` progressive permission onboarding.
- `PERM-002` background-location disclosure/settings flow.
- `PERM-003` notification/location-service degradation UX.
- `DIA-002` Debug Screen.
- `DIA-003` sanitized diagnostic export.
- `DIA-004` ApplicationExitInfo/recovery evidence.
- `PRV-001` backup exclusion/export/privacy verification.

**Exit condition:** F0.10 recovery matrix, F0.11 capability modes and F0.13 evidence requirements are demonstrably implemented.

---

### Wave W5 — Core Release Hardening

**Objective:** produce a Core build that can be trusted before Post-Core feature expansion.

Primary tasks:

- `PERF-001` long-trip ingestion benchmark.
- `PERF-002` large-history benchmark.
- `PERF-003` map preparation/render benchmark.
- `REL-001` reliability fault-injection suite.
- `MIG-001` migration preservation suite.
- `PRIV-001` privacy/security release audit.
- `CMP-001` Android compatibility matrix.
- `ACC-001` accessibility pass.
- `RC-001` full G3 release-candidate suite.
- `RC-002` Core acceptance review.

**Exit condition:** no P0/P1 blocker, G0–G4 applicable gates pass, and accepted known limitations are documented.

---

## 5. Dependency spine

The critical dependency path is:

```text
FND-001
  ↓
FND-002 + FND-003 + TST-001 + DIA-001
  ↓
TRK-001 → TRK-002 → TRK-004
  ↓                 ↓
PRC-001 → PRC-002   HIS-001
  ↓
DET-001 → DET-002/DET-003 → AUTO-001
  ↓
EXP-002…EXP-008
  ↓
EDT/REC/PERM hardening
  ↓
G3/G4 release gates
  ↓
Core acceptance
```

Parallel work is allowed only when dependencies and file ownership are explicit. Parallel agents must not edit the same architectural seam without coordination.

---

## 6. Epic map

| Epic | Purpose | Core requirements / constraints |
|---|---|---|
| FOUNDATION | buildable, testable baseline | NFR-MNT-001..004, NFR-TST-001..003, ADR-002/012/013 |
| STORAGE | source of truth + invariants | FR-REC-005/006/008, NFR-REL-001..004, ADR-003/005/006/014/020 |
| TRACKING | active recording | FR-DET-005..010, FR-REC-001..008, FR-NOT-001..004, ADR-004 |
| PROCESSING | reliable derived metrics | FR-MET-001..012, ADR-006/014/016 |
| DETECTION | automatic start/stop | FR-DET-001..004, ADR-007/008/018/020 |
| EXPERIMENTS | field evidence/freeze | F0.6 EXP-001..008, ADR-018 |
| UX | safe ride-first interaction | F0.9, NFR-SAFE-001..003, ADR-011 |
| HISTORY | browse/manage Trips | FR-HIS-001..008, FR-FAV-001..002 |
| EDITING | merge/split/correct | FR-EDT-001..006, ADR-005/015 |
| MAPS | route presentation | FR-MAP-001..006, ADR-019 |
| RECOVERY | interruption resilience | NFR-REL-001..004, F0.10, ADR-015/016/020 |
| PRIVACY | permissions/data ownership | NFR-PRV-001..004, F0.11, ADR-008/009/017 |
| DIAGNOSTICS | explain failures | FR-DIA-001..004, F0.13, ADR-017 |
| RELEASE | performance/compatibility | NFR-PERF, NFR-CMP, F0.12 gates |

---

## 7. Phase 1 task sizing rule

A normal implementation task should ideally:

- have one primary objective;
- touch one coherent architectural responsibility;
- have explicit dependencies;
- cite requirement and ADR IDs;
- identify expected files/packages;
- include deterministic acceptance tests;
- fit in a single reviewable change;
- avoid unrelated dependency upgrades/refactors.

If a task must simultaneously design architecture, implement multiple vertical features and invent tests, it is too large and must be split.

---

## 8. Definition of Ready (DoR)

A task is **Ready** only when all applicable items are true:

- [ ] Objective and user/system outcome are explicit.
- [ ] Requirement IDs are identified.
- [ ] Relevant ADRs are identified.
- [ ] Dependencies are complete or intentionally mocked.
- [ ] In-scope and out-of-scope behavior are written.
- [ ] Expected files/packages or allowed architectural area are known.
- [ ] Acceptance criteria are objectively testable.
- [ ] Required automated/manual validation is known.
- [ ] No unresolved product or architecture decision is being delegated to the agent.
- [ ] If field evidence is required, the task is labeled experiment/research rather than production freeze.

A task failing DoR returns to documentation/research rather than entering implementation.

---

## 9. Definition of Done (DoD)

A task is **Done** only when all applicable items are true:

- [ ] Acceptance criteria pass.
- [ ] Build/static checks pass.
- [ ] New deterministic tests pass.
- [ ] Relevant existing regression tests pass.
- [ ] Room migrations/invariants are preserved if storage changed.
- [ ] Diagnostics/reason codes are added for meaningful failure paths.
- [ ] Permission/privacy implications were reviewed.
- [ ] No unrelated architecture or dependency change was introduced.
- [ ] Docs/ADR are updated when behavior or contract changed.
- [ ] Manual/device validation was performed when required.
- [ ] Agent summary lists files changed, tests run, known limitations and follow-up work.

“Code compiles” is not Definition of Done.

---

## 10. Agent execution contract

Implementation agents must:

1. read the task card and linked source docs first;
2. identify conflicts before changing code;
3. follow Accepted ADRs;
4. avoid broad refactors outside task scope;
5. not upgrade dependencies unless the task explicitly permits it;
6. not add network/backend/analytics behavior to Core;
7. preserve Raw Track and data lineage;
8. preserve the single-active-capture invariant;
9. add tests before claiming completion;
10. stop and report when a missing decision prevents safe implementation.

### Recommended two-agent workflow

- **Implementation agent (e.g. Codex):** executes one bounded task.
- **Review agent (e.g. Claude Code):** checks implementation against requirements, ADRs, tests and scope; it does not redesign unless it finds a documented conflict.

Review findings become either fixes to the same task or new bounded backlog items.

---

## 11. Prompt-ready task card structure

Every agent prompt should contain:

```text
Task ID / title
Objective
Why this task exists
Source documents
Requirements implemented
ADRs that constrain the solution
Dependencies / prerequisites
In scope
Out of scope
Expected files/packages
Implementation constraints
Acceptance criteria
Automated tests required
Manual/device validation required
Definition of Done
Expected response format
```

The reusable template lives at:

`docs/05-roadmap/agent-task-template.md`

The first Phase 1 backlog lives at:

`docs/05-roadmap/phase1-backlog.md`

---

## 12. Branch/review policy baseline

F0.15 does not prescribe a hosted CI provider, but the implementation workflow should preserve these rules:

- one bounded task per branch/change set;
- no merge with failing G0/G1 checks;
- architecture changes require ADR review first;
- schema changes require migration tests;
- detector/location default changes require version bump and replay/field evidence;
- P0/P1 regressions block integration/release;
- generated diagnostic/replay data containing location is not committed unless sanitized and intentionally approved as a test fixture.

---

## 13. Post-Core roadmap

Post-Core work remains sequenced behind Core acceptance.

### Train B1 — Routes & analytics

- `RTE-001` Route entity UI and manual assignment.
- `RTE-002` route trip history.
- `RTE-003` similar-route research/algorithm spike.
- `RTE-004` route comparison.
- `ANA-001` aggregate distance/time/history.
- `ANA-002` records and trend presentation.

### Train B2 — Ride journal context

- `CTX-001` notes.
- `CTX-002` tags.
- `CTX-003` manual markers.
- `CTX-004` Photo Picker integration.
- `FAV-002` route favorites.

### Train B3 — Export/import/sharing

- `EXP-101` GPX export.
- `EXP-102` backup export schema.
- `EXP-103` restore/import.
- `EXP-104` GPX import.
- `EXP-105` shareable trip summary.
- `EXP-106` privacy zones for shared artifacts.

### Train C — Motorcycle ownership

- `MOTO-001` Motorcycle profile.
- `MOTO-002` multiple motorcycles/Trip assignment.
- `MOTO-003` per-motorcycle distance.
- `MOTO-004` maintenance domain.
- `MOTO-005` fuel domain.

Cloud sync, public social features, navigation and competitive speed features remain out of Core and require explicit scope/ADR review before introduction.

---

## 14. Risks carried into implementation

| Risk | Roadmap response |
|---|---|
| `IN_VEHICLE` is not motorcycle-specific | field validation + false-positive campaign before freeze |
| OEM/process behavior can interrupt tracking | W4 recovery hardening + device matrix |
| GPS thresholds can overfit one route | Pilot → held-out Validation → EXP-008 |
| long trips/history can stress Room/UI | W5 performance gates |
| permissions can reduce automation | explicit capability modes and assisted/manual fallbacks |
| map provider choice is deferred | abstraction first; provider decision before MAP-001 closes |
| elevation is noisy | baseline quality labeling; algorithm remains reopenable |
| agents can drift architecture | ADR citations + DoR + bounded prompts + review agent |

---

## 15. What F0.15 intentionally does not freeze

F0.15 does not fabricate answers for deliberately open items:

- final detector thresholds;
- production GPS sampling/min-distance/batching profile;
- exact map provider;
- final elevation smoothing/gain algorithm;
- final diagnostic retention after measurement;
- exact calendar dates or story-point estimates;
- cloud architecture;
- future Gradle multimodule extraction.

These have explicit evidence/reopen paths.

---

## 16. Phase 1 start sequence

After F0.17 returns GO, implementation should begin in this order:

1. `FND-001`
2. `FND-002`
3. `FND-003`
4. `FND-004`
5. `TST-001`
6. `DIA-001`
7. `CAP-001`
8. `EXP-001`

Only after that foundation is green should the manual tracking vertical slice begin.

---

## 17. F0.15 closure criteria

- [x] Core implementation is split into dependency-ordered waves.
- [x] Phase 1 has bounded, stable task IDs.
- [x] Field experiments are integrated as a hard detector/location freeze gate.
- [x] Requirements, ADRs, testing and observability are represented in the roadmap.
- [x] DoR and DoD are explicit.
- [x] Agent execution rules are explicit.
- [x] A reusable prompt/task template exists.
- [x] A prompt-ready initial Phase 1 backlog exists.
- [x] Post-Core/Future work is separated from Core.
- [x] No deferred technical decision was falsely frozen.

### Decision v0.15

> **F0.15 queda CERRADO como roadmap baseline v0.1.**

Next workstream: **F0.17 — Phase 1 Readiness Review**.

F0.16 was removed by project-owner decision on 2026-09-15. V1 review is not a prerequisite for Phase 1. V2 is evaluated against its own documentation; original workstream IDs are preserved.

**Update, 2026-09-19:** F0.16 reinstated, narrowly scoped to a V1-vs-V2 feature parity checklist and V1-to-V2 data migration planning only — see `docs/00-master/f0-16-v1-reference.md`. Still not a prerequisite for anything already closed (F0.17's GO stands); executed as backlog tasks `REF-001`/`REF-002`.
