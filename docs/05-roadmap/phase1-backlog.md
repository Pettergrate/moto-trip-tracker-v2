# Phase 1 Initial Backlog
## Moto Trip Tracker V2

**Status:** Baseline backlog for post-F0.17 implementation  
**Roadmap version:** F0.15 v0.1  
**Date:** 2026-09-15

This backlog is intentionally task-sized. It is not authorization to code before F0.17 GO.

---

## W0 — Foundation & Experiment Enablement

### FND-001 — Android project bootstrap

**Objective:** create the minimal Android app that builds/runs with the accepted platform baseline and no product logic beyond a shell.

**Constraints:** ADR-001, ADR-002, ADR-012.  
**Implements:** NFR-MNT-001..004, NFR-CMP-001/002.  
**Depends on:** F0.17 GO.

**Acceptance:** app installs/launches; Compose shell exists; dependency catalog/build config is explicit; no V1 code copied; basic local test command passes.

---

### FND-002 — Architecture and package skeleton

**Objective:** establish UI/Data/Domain/Platform boundaries and DI wiring without implementing features.

**Constraints:** ADR-002, ADR-012, ADR-013.  
**Depends on:** FND-001.

**Acceptance:** boundaries mirror F0.8; domain does not depend on Android framework types; no speculative multimodule split.

---

### FND-003 — Room schema v1 + migration harness

**Objective:** implement F0.7/F0.8 persistence model needed by Core and schema tests.

**Constraints:** ADR-003, ADR-005, ADR-006, ADR-014, ADR-020.  
**Implements:** FR-REC-005/006/008, NFR-REL-001/003/004.

**Acceptance:** schema/invariants exist; single ACTIVE capture enforced at domain/transaction boundary; migration-test harness created; destructive migration is not enabled.

---

### FND-004 — IDs, clocks and version primitives

**Objective:** implement stable identifiers, wall/elapsed time abstractions and schema/detector/location/processing version values.

**Constraints:** ADR-013, ADR-014.  
**Depends on:** FND-002.

**Acceptance:** core logic can use fake clocks; domain does not directly call Android system clock; versions are persisted/available where defined.

---

### TST-001 — Deterministic test harness

**Objective:** implement `FakeClock`, deterministic dispatchers, database factories, `LocationReplaySource`, `ActivityReplaySource` and capability fakes.

**Constraints:** ADR-013, ADR-018.  
**Implements:** NFR-TST-001..003.

**Acceptance:** a sample replay is deterministic; tests use no arbitrary sleeps for core state timing.

---

### DIA-001 — Structured diagnostic event foundation

**Objective:** persist privacy-safe diagnostic events, reason codes, correlation IDs and current algorithm versions.

**Constraints:** ADR-014, ADR-017.  
**Implements:** FR-DIA-001..004.

**Acceptance:** event schema exists; ordinary GPS points do not generate event spam; release logging does not expose coordinates.

---

### CAP-001 — Capability resolver

**Objective:** derive `FULL_AUTO`, `ASSISTED_AUTO`, `MANUAL` and `LOCATION_DEGRADED` from current permissions/services/capabilities.

**Constraints:** ADR-008, ADR-009.  
**Implements:** NFR-PRV-002/003, F0.11 capability matrix.

**Acceptance:** deterministic unit coverage for clean install, denied permissions, approximate location, disabled location services and notification denial.

---

### EXP-001 — Diagnostic harness shell

**Objective:** provide internal controls/log export needed to execute F0.6 experiments.

**Constraints:** ADR-017, ADR-018.  
**Depends on:** TST-001, DIA-001, CAP-001.

**Acceptance:** session metadata, ground-truth markers and diagnostic export can be captured without becoming user-facing Core UX.

---

## W1 — Manual Recording Vertical Slice

### TRK-001 — Foreground tracking service + manual start

**Objective:** explicit Start creates/reuses the only ACTIVE TripCapture and starts location FGS tracking.

**Constraints:** ADR-003, ADR-004, ADR-015, ADR-020.  
**Implements:** FR-DET-005/010, FR-REC-008.

**Acceptance:** one active capture only; repeated Start is idempotent; service rehydrates state from Room.

---

### TRK-002 — FLP location ingestion + RawTrackPoint persistence

**Objective:** record timestamped precise locations and quality metadata into Raw Track.

**Constraints:** ADR-003, ADR-006, ADR-016.  
**Implements:** FR-REC-001..005/007/008.

**Acceptance:** ordering preserved; invalid/poor points are assessed without rewriting raw evidence; offline works.

---

### NOT-001 — Active trip notification controls

**Objective:** persistent notification exposes Pause/Resume/Finish with safe idempotent commands.

**Constraints:** ADR-004, ADR-015.  
**Implements:** FR-NOT-001..004, NFR-SAFE-002.

**Acceptance:** actions work with UI backgrounded; duplicate intents do not corrupt state.

---

### TRK-003 — Manual pause/resume

**Objective:** manual pause explicitly owns paused state until user resumes/finishes; no silent auto-resume.

**Constraints:** ADR-007, ADR-015.  
**Implements:** FR-DET-007..009.

**Acceptance:** pause interval persisted; movement while paused can produce warning evidence but cannot auto-cancel pause.

---

### TRK-004 — Finish capture transaction

**Objective:** idempotently finalize capture, create/update logical Trip composition and enqueue derived processing.

**Constraints:** ADR-003, ADR-005, ADR-010, ADR-015, ADR-020.

**Acceptance:** repeated Finish is safe; no second ACTIVE capture remains; partial failure rolls back structural mutation.

---

### PRC-001 — Point assessment + Processed Track v1

**Objective:** derive accepted/rejected point assessments and Processed Track without altering Raw Track.

**Constraints:** ADR-006, ADR-014, ADR-016.

**Acceptance:** processing is reproducible for a version; gaps are explicit; no fabricated interpolation.

---

### PRC-002 — Core distance/time/speed metrics

**Objective:** calculate distance, duration, moving/stopped/paused time and speed metrics from approved data sources.

**Implements:** FR-MET-001..008/012.  
**Constraints:** ADR-006, ADR-014/016.

**Acceptance:** deterministic golden tests; max speed rejects documented spike cases; display polyline is not metric source.

---

### PRC-003 — Elevation baseline

**Objective:** provide elevation range and quality-aware provisional gain/loss processing without pretending the deferred algorithm is final.

**Implements:** FR-MET-009/010.  
**Constraints:** ADR-006, ADR-014.

**Acceptance:** algorithm/version is explicit; noisy/unsupported altitude can degrade gracefully; reopen trigger documented.

---

### UI-001 — App shell, Home and Active Trip

**Objective:** implement F0.9 primary navigation and active-trip-first Home behavior.

**Constraints:** ADR-011.  
**Implements:** NFR-SAFE-001..003.

**Acceptance:** Home/History/Favorites shell; Active Trip is special destination/state, not a fourth permanent tab; predictive back behavior validated.

---

### HIS-001 — History + Trip Detail

**Objective:** display persistent Trips and a detail view that remains useful without map availability.

**Implements:** FR-HIS-001..004, FR-HIS-008, FR-MET-001..012.

**Acceptance:** chronological history; rename; metrics and route summary available offline; map failure does not hide Trip data.

---

### MAP-001 — Map provider decision + isolated renderer

**Type:** spike + implementation after decision.

**Objective:** choose a provider based on documented constraints, record ADR if needed, and implement the provider behind a renderer abstraction.

**Constraints:** ADR-019.

**Acceptance:** provider decision is explicit; recording has no dependency on map tiles/network; large-track strategy has tests/benchmark path.

---

### REC-001 — Same-boot process recovery

**Objective:** recover an active capture after process death within the same boot using persisted state and explicit gaps.

**Constraints:** ADR-003, ADR-004, ADR-015/016/020.

**Acceptance:** process recreation does not duplicate ACTIVE capture; evidence records gap/recovery; Raw Track remains intact.

---

## W2 — Automatic Detection & Field Freeze

### DET-001 — Activity Recognition transitions
**Objective:** register/restore Transition API signals as passive detector input.  
**Constraints:** ADR-007/008.  
**Acceptance:** IN_VEHICLE is evidence only, not motorcycle proof; registration restored after supported boot/update paths.

### DET-002 — Candidate Start engine
**Objective:** implement F0.3 candidate-start state/evidence evaluation.  
**Constraints:** ADR-007/013/018.  
**Acceptance:** deterministic replay tests; no single sample starts a Trip by itself.

### DET-003 — Candidate Stop engine
**Objective:** implement candidate-stop evidence and delayed finalization logic.  
**Acceptance:** traffic lights/short stops do not end normal Trip fixtures.

### DET-004 — Temporary-stop hysteresis
**Objective:** implement temporary hold/churn protection.  
**Acceptance:** congestion/semáforo replay stays one Trip.

### DET-005 — Manual-pause warning logic
**Objective:** detect sustained movement while manually paused and warn without auto-resume.  
**Acceptance:** manual ownership remains authoritative.

### DET-006 — Duplicate-start / post-finish suppression
**Objective:** prevent immediate duplicate or rebound starts.  
**Constraints:** ADR-015/020.

### AUTO-001 — Capability-aware automatic orchestration
**Objective:** connect detector to Full Auto or Assisted flows based on CAP-001.  
**Constraints:** ADR-004/007/008/020.

### EXP-002 — Pilot field campaign
**Objective:** execute F0.6 pilot and identify logging/profile defects before comparative measurements.

### EXP-003 — Location profile campaign
**Objective:** compare interval/min-distance/batching experiment profiles.

### EXP-004 — Detector start/stop campaign
**Objective:** measure start latency, stop latency, fragmentation and missed Trips.

### EXP-005 — False-positive campaign
**Objective:** measure car/bus/bicycle/on-foot ambiguity and adjust only with documented evidence.

### EXP-006 — Battery campaign
**Objective:** compare candidate profiles under controlled conditions.

### EXP-007 — Held-out validation campaign
**Objective:** validate selected behavior on routes/rides not used to tune the detector.

### EXP-008 — Freeze Detector/Location Profile v1
**Objective:** approve production defaults and version them.  
**Hard gate:** G4. No freeze without evidence.

---

## W3 — Editing & Everyday Use

### EDT-001 — Merge Trips
**Objective:** compose multiple TripParts without duplicating/re-writing Raw Track.  
**Constraints:** ADR-005/015.

### EDT-002 — Split Trip
**Objective:** create logical Trips from defined boundaries while preserving capture lineage.

### EDT-003 — Boundary correction
**Objective:** adjust logical start/end boundaries reversibly where specified.

### EDT-004 — Recalculation/invalidation
**Objective:** invalidate/recompute derived artifacts after structural edits using processingVersion.

### TRS-001 — Trash/restore/purge
**Objective:** non-immediate destructive delete with restore path and documented purge semantics.

### FAV-001 — Trip favorites
**Objective:** favorite/unfavorite and Favorites area for Trips.

### HIS-002 — Search/filter/sort
**Objective:** complete Core history management at scale.

### MAP-002 — Stops/markers/large tracks
**Objective:** render start/end/stop markers and long routes efficiently.

### MET-001 — Core metric presentation
**Objective:** finish user-facing Core metrics, quality/degraded labels and recalculation visibility.

---

## W4 — Reliability, Permissions & Diagnostics Hardening

### REC-002 — Sticky service restart recovery
### REC-003 — Reboot boundary reconciliation
### REC-004 — User stop / force-stop semantics
### REC-005 — GPS gap and location-degraded handling
### REC-006 — Persistence failure buffer/retry
### PERM-001 — Progressive permission onboarding
### PERM-002 — Background location disclosure/settings flow
### PERM-003 — Notifications/location-service degradation UX
### DIA-002 — Internal Debug Screen
### DIA-003 — Sanitized diagnostic ZIP
### DIA-004 — Process exit/recovery evidence
### PRV-001 — Backup/export/privacy enforcement

Each task must implement the corresponding F0.10/F0.11/F0.13 contract rather than redesign it.

---

## W5 — Core Release Hardening

### PERF-001 — Long-trip ingestion benchmark
### PERF-002 — Large-history benchmark
### PERF-003 — Map preparation/render benchmark
### REL-001 — Reliability fault-injection matrix
### MIG-001 — Migration preservation suite
### PRIV-001 — Privacy/security release audit
### CMP-001 — Android/device compatibility matrix
### ACC-001 — Accessibility validation
### RC-001 — G3 release candidate suite
### RC-002 — Core acceptance review

`RC-002` may only close when all P0/P1 blockers are resolved or explicitly reclassified through project review; an agent cannot waive a blocker on its own.
