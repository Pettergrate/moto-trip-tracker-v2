# F0.12 — Testing Strategy
## Moto Trip Tracker V2

**Status:** Closed baseline  
**Version:** 0.1  
**Phase:** 0 — Discovery & Design  
**Depends on:** F0.1–F0.11  
**Next:** F0.13 — Observability & Diagnostics

---

## 1. Purpose

This document defines how Moto Trip Tracker V2 will prove that its behavior is reliable.

The product cannot be considered trustworthy because it works during a few manual rides. The core behavior combines Android lifecycle constraints, background execution, location data, a stateful detector, persistent storage, post-processing and user-editable trip composition. Testing must therefore cover each layer independently and also verify the full system under interruption.

The strategy intentionally separates:

- fast deterministic tests that run continuously;
- Android/device integration tests;
- replay-based location/detector tests;
- end-to-end user flows;
- destructive/recovery tests;
- field validation from F0.6;
- release-candidate gates.

**Core principle:**

> A behavior that matters to trip integrity must be reproducible in a test or field protocol, not validated only by memory or visual inspection.

---

## 2. Quality model

Testing is risk-based. The highest priority is not visual polish; it is preventing silent corruption, missed trips, false trip fragmentation, incorrect statistics and unrecoverable loss of route evidence.

### Q-01 — Data integrity

Raw evidence, trip composition and user edits must remain internally consistent.

### Q-02 — Detector correctness

The detector must start and stop for the right reasons, tolerate transient stops and avoid obvious false positives/false stops.

### Q-03 — Recoverability

A process death, service restart, GPS outage, permission change or post-processing failure must not silently destroy or duplicate a Trip.

### Q-04 — Android compliance

Foreground service, permissions, notifications and background behavior must work within supported Android versions.

### Q-05 — Determinism

Domain tests should be repeatable with controlled clocks, location sequences and events.

### Q-06 — UX correctness

The UI must reflect persisted state and must not imply that an operation succeeded before the source of truth confirms it.

### Q-07 — Performance and scale

Long Trips and years of history must remain usable without loading every TrackPoint into memory.

### Q-08 — Privacy

Permissions, sharing, export, logs and privacy zones must respect F0.11.

---

## 3. Testing layers

Moto Trip Tracker V2 uses five practical test layers. The names are contractual for planning; exact Gradle source sets and libraries may evolve.

| Layer | Purpose | Typical environment | Typical trigger |
|---|---|---|---|
| T0 — Static/build | Catch compilation, lint, formatting/schema mistakes | Host/CI | Every change |
| T1 — Unit/domain | Verify deterministic business logic in isolation | Local JVM | Every change |
| T2 — Component/integration | Verify Room, repositories, workers, service adapters and Android-bound components | Local + emulator/device | Pre-merge |
| T3 — Feature/application | Verify Compose screens and complete user flows | Emulator/device | Pre-merge / nightly |
| T4 — Release/field | Verify system behavior on representative devices and real rides | Physical devices / real motorcycle | Release candidate / detector freeze |

Android distinguishes local tests from instrumented tests primarily by execution environment. Local tests belong in `test`; instrumented tests that require a device/emulator belong in `androidTest`. V2 should prefer the lowest layer that can prove the behavior, and move upward only when Android framework fidelity is necessary.

---

## 4. What belongs in fast local tests

The following logic SHOULD remain Android-independent enough to run as fast deterministic tests.

### TST-UNIT-001 — Trip detector state machine

Test every valid and invalid transition between:

- IDLE;
- CANDIDATE_START;
- TRACKING;
- TEMPORARY_HOLD / equivalent internal hold state;
- MANUAL_PAUSED;
- CANDIDATE_STOP;
- FINALIZING / processing handoff.

Tests must include repeated/duplicate inputs and out-of-order commands.

### TST-UNIT-002 — Candidate-start evidence

Given synthetic sequences of activity, movement, speed, displacement, accuracy and time, verify whether the detector:

- remains IDLE;
- enters candidate start;
- confirms a Trip;
- abandons the candidate.

Production thresholds remain field-gated by F0.6, but the algorithm must be testable with injected profiles.

### TST-UNIT-003 — Candidate-stop evidence

Verify traffic-light and congestion tolerance, prolonged stillness, GPS loss, resumed movement and manual override behavior.

### TST-UNIT-004 — Manual pause ownership

Verify that manual pause does not auto-resume and that movement while paused produces the defined warning/event behavior without silently changing state.

### TST-UNIT-005 — Capability resolver

Given combinations of:

- precise/approximate/no location;
- Activity Recognition;
- Background Location;
- notifications;
- Location Services;
- Auto Tracking setting;

verify the user-visible capability state: FULL_AUTO, ASSISTED_AUTO, MANUAL or LOCATION_DEGRADED.

### TST-UNIT-006 — Distance calculation

Test valid point sequences, duplicate coordinates, zero time delta, very low movement, gaps and rejected points.

### TST-UNIT-007 — Speed processing

Verify:

- platform speed when valid;
- fallback derived speed when allowed;
- impossible spike rejection;
- no speed calculation across explicit gaps;
- filtered maximum speed behavior.

### TST-UNIT-008 — Elevation processing

When elevation is enabled, verify smoothing, missing altitude and cumulative gain/loss rules with synthetic profiles. No test should encode a scientific rule that F0.5/F0.6 has not approved.

### TST-UNIT-009 — Point assessment

Every raw point should receive a deterministic assessment such as accepted/rejected/suspect according to processing version and reason codes.

### TST-UNIT-010 — Trip statistics

Verify elapsed duration, moving time, stopped time, manual-pause time, total distance, average speed, moving average and min/max elevation from known processed fixtures.

### TST-UNIT-011 — Merge composition

Property/invariant tests must verify that merging Trips:

- does not duplicate raw evidence;
- preserves chronological ordering;
- preserves source lineage;
- recalculates derived statistics;
- supersedes prior logical Trips without deleting captures.

### TST-UNIT-012 — Split composition

Splitting a Trip must partition the logical composition without duplicating or losing referenced raw ranges.

### TST-UNIT-013 — Boundary correction

Changing logical start/end boundaries must not mutate raw TrackPoints and must invalidate/rebuild derived data.

### TST-UNIT-014 — Idempotent commands

Repeated Finish, Pause, Resume, processing enqueue and recovery commands must not create duplicate terminal events or duplicate logical Trips.

### TST-UNIT-015 — Time handling

Use fake wall-clock and monotonic clocks to test:

- timezone changes;
- manual clock changes;
- daylight-saving changes where applicable;
- same-boot elapsed calculations;
- reboot boundary behavior.

---

## 5. Test infrastructure required by architecture

The implementation should create test seams deliberately rather than adding them after bugs appear.

### 5.1 FakeClock

Provide controllable wall-clock and monotonic time.

Do not use real sleeps to test detector timeouts, stop grace periods, pause duration or retry logic.

### 5.2 LocationReplaySource

A test-only source capable of replaying timestamped `RawTrackPoint`-equivalent observations into the detector/processor.

Minimum fields should include the relevant subset of:

- wall timestamp;
- elapsed realtime;
- latitude/longitude;
- horizontal accuracy;
- altitude and vertical accuracy when available;
- speed and speed accuracy when available;
- bearing and bearing accuracy when available;
- source/profile metadata.

### 5.3 ActivityReplaySource

Inject `IN_VEHICLE`, `STILL`, `WALKING`, `ON_BICYCLE` and related transitions/confidence/context without depending on Google Play services during unit tests.

### 5.4 FakeCapabilityProvider

Expose permissions/system capability state as testable data rather than querying Android from domain logic.

### 5.5 Deterministic coroutine dispatchers

Repositories, detector orchestration and workers should use injectable dispatchers/schedulers where this materially improves determinism.

### 5.6 Database test factories

Provide both:

- in-memory Room databases for fast DAO/integration tests;
- file-backed test databases for migration, process-recreation and transaction/recovery scenarios.

### 5.7 Diagnostic fixtures

Replay files and expected results should be versioned in the repository. A detector or processing change must be able to rerun historical scenarios against the new version.

---

## 6. Room and persistence tests

Room is a source of truth; its tests are blocking, not optional.

### TST-DB-001 — Schema constraints

Verify uniqueness, foreign keys, nullability and indexes required by F0.7/F0.8.

### TST-DB-002 — Single active capture invariant

Attempt to create conflicting active captures and verify the data/domain constraint rejects or serializes the operation.

### TST-DB-003 — TrackPoint ordering

Points must remain deterministically orderable even when timestamps collide or batches arrive late.

### TST-DB-004 — Finish transaction

Induce a failure inside the Finish transaction and verify rollback leaves a coherent ACTIVE or retryable state rather than a half-completed Trip.

### TST-DB-005 — Merge transaction

Induce failure before and after structural writes and verify no partial composition survives.

### TST-DB-006 — Split transaction

Same requirement as merge.

### TST-DB-007 — Delete / Trash / restore

Verify soft-delete/Trash rules and restore without orphaning TripParts or raw captures.

### TST-DB-008 — Reprocessing invalidation

A processing-version change or boundary edit must invalidate only the derived data that needs rebuilding.

### TST-DB-009 — Migration preservation

Every production schema migration must use exported Room schemas and migration tests. A migration is not accepted if it only opens an empty database successfully; representative historical data must survive.

### TST-DB-010 — Large dataset queries

History/detail queries must not accidentally load full raw tracks when only summary rows are needed.

---

## 7. Worker and processing tests

Post-processing is persistent work, but its business logic should be testable separately from WorkManager scheduling.

### TST-WRK-001 — Processing success

A PENDING Trip becomes READY with expected processing version and statistics.

### TST-WRK-002 — Retryable failure

Transient failure returns retry semantics and preserves source data.

### TST-WRK-003 — Permanent invalid input

Invalid/corrupt source data moves to an explicit diagnosable state instead of infinite retry.

### TST-WRK-004 — Unique work

Repeated scheduling for the same Trip/version must not run duplicate conflicting processors.

### TST-WRK-005 — Cancellation/restart

A cancelled worker can be safely re-enqueued without corrupting already committed output.

Use WorkManager's testing helpers for Android-bound scheduling/integration, while testing worker business rules directly where possible.

---

## 8. Foreground service and lifecycle integration tests

Live tracking cannot be proven only with unit tests.

### TST-FGS-001 — Manual start

User action creates/persists the active capture before UI reports successful tracking.

### TST-FGS-002 — Notification controls

Pause/Resume/Finish notification actions produce the same domain commands as the in-app controls.

### TST-FGS-003 — Background UI

Leaving the Activity or locking the screen does not end an active Trip.

### TST-FGS-004 — Process recreation

Recreate/kill the app process where feasible and verify state rehydrates from Room rather than creating a duplicate capture.

### TST-FGS-005 — Sticky service restart

Exercise a restart path where the service receives a null/empty restart intent and verify it reconciles from persisted state.

### TST-FGS-006 — User stop

When Android/user explicitly stops the app/service, V2 must not falsely claim that tracking remains healthy.

### TST-FGS-007 — Reboot boundary

A reboot with an active capture preserves prior evidence, closes/marks interruption according to F0.10 and never invents continuity.

---

## 9. Permission and capability tests

Permission testing is a product-flow concern, not merely a manifest check.

### TST-PERM-001 — Clean install

No permission dialog appears before a feature requires it.

### TST-PERM-002 — Manual-only path

A user who declines Auto Tracking permissions can still use History and manual functionality to the extent allowed by foreground location.

### TST-PERM-003 — Approximate location

Selecting approximate-only must produce LOCATION_DEGRADED behavior and must not display false precision.

### TST-PERM-004 — Background location denied

Auto Tracking must resolve to Assisted/Manual rather than loop permission prompts.

### TST-PERM-005 — Notifications denied

Apply the F0.11 product policy: Full Auto must not display READY when its user-visible tracking notification cannot be delivered normally.

### TST-PERM-006 — Permission revoked during Trip

Existing evidence remains; state becomes degraded/recovery-required according to F0.10.

### TST-PERM-007 — Location Services disabled

Treat missing service availability separately from permission denial.

### TST-PERM-008 — Manifest audit

The merged manifest must not contain broad/unapproved permissions introduced transitively by dependencies.

System permission dialogs should be tested primarily with device/emulator integration or manual release checks, not brittle pixel/text assumptions about OEM-specific system UI.

---

## 10. Compose UI and navigation tests

Compose UI tests should interact through semantics and user-visible behavior, not implementation details.

### Screens requiring baseline behavior tests

- Home / IDLE;
- Home with active Trip;
- Active Trip / TRACKING;
- Active Trip / MANUAL_PAUSED;
- Active Trip / degraded/recovery state;
- History;
- Trip Detail;
- Favorites;
- Settings / Auto Tracking capability setup;
- Merge flow;
- Split flow;
- Delete / Trash confirmation;
- permission/disclosure education screens.

### Critical UI assertions

- Back navigation never silently finishes a Trip.
- UI state after recreation is reconstructed from repositories.
- Finish confirmation remains explicit.
- destructive actions require defined confirmation.
- Trip Detail remains usable without map tiles.
- Active Trip controls are visible and sufficiently large.
- approximate/degraded state is understandable without technical permission jargon.
- loading/error states do not present stale values as current truth.

### Accessibility

Automated Compose accessibility checks should be enabled on supported API levels for critical screens. Manual TalkBack testing remains part of release validation because automation cannot prove the full experience.

---

## 11. End-to-end critical journeys

These are application-level scenarios. They may use a fake/replay location source in test builds so they remain repeatable.

### E2E-01 — Manual happy path

Open → Manual Start → record points → Pause → Resume → Finish → processing → History → Trip Detail.

### E2E-02 — Automatic happy path

IDLE → activity trigger → candidate start → confirmed Trip → movement → candidate stop → completed Trip.

### E2E-03 — Traffic light

Tracking → stopped at light → remains same Trip → movement resumes without split.

### E2E-04 — Congestion

Repeated movement/still cycles do not repeatedly start/finish Trips.

### E2E-05 — Restaurant pause

Manual Pause → extended stop → movement resumes while still paused → warning → explicit Resume → same logical Trip.

### E2E-06 — GPS gap

Tracking → no valid fixes → explicit gap → valid fixes resume → Trip continues without fabricated path.

### E2E-07 — Process death

Tracking → persisted points → process termination → recovery → same capture when policy permits → no duplicate Trip.

### E2E-08 — Finish during degraded persistence

Failure is visible and the UI must not claim completion before the transaction succeeds.

### E2E-09 — Merge

Two completed Trips → merge → one new logical Trip → prior Trips superseded → raw evidence unchanged.

### E2E-10 — Split

One completed Trip → choose split point → two logical Trips → no duplicated/lost raw range.

### E2E-11 — Permission downgrade

Full Auto ready → revoke a required capability → Home reflects degraded mode → manual/history behavior remains coherent.

### E2E-12 — Offline

Airplane/no Internet → manual tracking, storage, processing and History continue; only network-dependent map content may degrade.

---

## 12. Replay regression suite

The detector and GPS processor require a reusable regression corpus.

Each replay fixture should contain:

- fixture ID and description;
- source: synthetic, sanitized diagnostic capture or approved field capture;
- expected detector transitions;
- ground-truth Trip start/end when known;
- expected accepted/rejected point classes;
- expected gap regions;
- expected metric ranges/tolerances;
- detector/location/processing version used to create the baseline.

### Minimum replay categories

1. clean urban ride;
2. slow departure from parking;
3. long traffic light;
4. heavy congestion;
5. stop-and-go parking maneuver;
6. short legitimate ride;
7. walking with phone;
8. bicycle;
9. car/bus ambiguity;
10. poor GPS / urban canyon;
11. tunnel / location gap;
12. impossible GPS jump;
13. device stationary with GPS drift;
14. manual pause/resume;
15. process-recovery marker sequence.

A detector change that improves one fixture but regresses several established fixtures must be reviewed rather than automatically accepted.

---

## 13. Property and invariant testing

Example-based tests are insufficient for merge/split and route processing.

Where practical, generate randomized valid inputs and assert invariants such as:

- distance is never negative;
- durations are never negative;
- processed points remain chronologically ordered;
- a rejected point cannot silently contribute to official distance unless a documented rule says otherwise;
- merge(A,B) references the same raw evidence as A+B, not copies;
- split followed by a logically equivalent merge reconstructs the same referenced evidence ordering;
- boundary edits never mutate RawTrackPoint rows;
- post-processing is deterministic for identical source data + processing version;
- repeated Finish/processing commands converge to one terminal result.

---

## 14. Performance and scale testing

Performance tests are used where human perception or data integrity can degrade.

### PERF-01 — Active point ingestion

Measure sustained point persistence and processing overhead under representative active sampling without dropping data due to application backpressure.

### PERF-02 — Long Trip

Use synthetic/replayed long-duration tracks to verify memory remains bounded and Trip Detail does not require loading all raw points at once.

### PERF-03 — Large history

Populate a multi-year synthetic history and measure Home/History query behavior.

### PERF-04 — Map preparation

Generating the renderable processed polyline must not use the display polyline as the source of official metrics.

### PERF-05 — Startup / critical UI

When the project reaches stabilization, Macrobenchmark may measure app startup and critical journeys. Performance thresholds should be set from measured target hardware rather than invented during F0.12.

### PERF-06 — Battery

Battery claims are not accepted from emulator/unit tests. Passive detection and active tracking consumption are measured through the F0.6 field protocol on physical hardware.

---

## 15. Compatibility matrix

V2 cannot exhaustively test every Android device. The release strategy therefore uses risk-based OS coverage.

### Required API classes

- **Minimum supported API:** verify install/start/core manual path.
- **Background-location era:** verify permission/background behavior around Android 10/11-class restrictions.
- **Foreground-service restriction era:** verify Android 12+ behavior.
- **Notification permission era:** verify Android 13+.
- **Modern FGS/location behavior:** verify Android 14/15-class behavior.
- **Target platform:** verify Android 16 / API 36 behavior.

Exact emulator/API set may be optimized later, but no release candidate may be tested only on the newest emulator.

### Physical hardware

At minimum before a personal release candidate:

- primary real phone used for motorcycle rides;
- one additional Android device when available, preferably from another OEM or Android generation;
- actual outdoor GPS ride for the detector/location gate.

OEM-specific battery restrictions are documented as compatibility observations, not hidden by test mocks.

---

## 16. Reliability fault-injection matrix

The following failures must have a reproducible test or explicit manual protocol:

| Failure | Required result |
|---|---|
| Activity recreation | No business-state loss |
| App UI backgrounded | Active FGS continues |
| Process death | Recover/reconcile persisted active capture |
| Sticky service restart | Rehydrate from Room; no duplicate capture |
| User stops app/service | Do not falsely report healthy tracking |
| Reboot | Preserve prior evidence; no fabricated continuity |
| GPS disabled | Open gap/degraded state; no fake distance |
| Permission revoked | Preserve data and degrade capability |
| SQLite write failure | Bounded retry/buffer; no silent loss claim |
| Storage exhaustion | Escalate critical state; do not claim safe recording |
| Finish duplicated | One terminal result |
| Pause + Finish race | Serialized/coherent terminal state |
| Worker killed/retried | Idempotent processing |
| Merge failure | Full rollback of structural transaction |
| Split failure | Full rollback of structural transaction |
| Migration failure | No destructive fallback in release |
| Wall-clock changed | Same-boot duration remains monotonic |

---

## 17. Privacy and security validation

### PRIV-01 — No silent network dependency

Core recording/history should work without Internet. Any network traffic introduced later must be attributable to an approved feature/dependency.

### PRIV-02 — Logs

Release logs must not contain raw coordinates, free-form notes or exported route content by default.

### PRIV-03 — Export scope

GPX/share output contains only the intended Trip data and applies privacy-zone transformation only to the exported/shared artifact.

### PRIV-04 — Raw evidence unchanged by privacy zone

Enable/change/delete a privacy zone and confirm local Raw Track remains identical.

### PRIV-05 — Auto Backup rules

Verify sensitive database/diagnostic data is excluded from Android cloud backup as specified by F0.11.

### PRIV-06 — Dependency/manifest review

A release candidate is blocked if a new SDK introduces unreviewed permissions, network collection or data handling.

---

## 18. Test data policy

### Synthetic data

Use by default for unit/property tests.

### Diagnostic captures

Real diagnostic data may contain sensitive location history. It must remain local/private by default and must not be committed to a public repository.

### Sanitized replay fixtures

If a real capture is useful as a permanent regression fixture, create an approved sanitized/translated version that removes personally identifying route context while preserving the technical shape needed by the test.

### Golden expected values

Golden metric outputs must record the algorithm/version that produced them. Updating a golden because a test fails is not sufficient justification; the algorithmic change must be reviewed.

---

## 19. Flakiness policy

A flaky test is a defect in the test suite or synchronization, not a normal state.

Rules:

- no arbitrary `sleep()` as synchronization in automated tests;
- prefer fake clocks, test dispatchers, Compose synchronization/wait APIs and explicit observable states;
- retries may mitigate infrastructure/device disconnects, but a product assertion that requires retries must be investigated;
- quarantined tests require an issue and owner/reason;
- a test that fails intermittently cannot be counted as release evidence until stabilized.

---

## 20. Execution gates

### Gate G0 — Local change

Before considering a coding task complete:

- project compiles;
- relevant unit tests pass;
- lint/static checks relevant to changed code pass;
- no test intentionally disabled without documentation.

### Gate G1 — Feature/PR

Before merge:

- G0;
- all relevant domain/repository tests;
- targeted Room/worker/component tests;
- targeted Compose tests;
- requirement IDs and tests linked in the task report.

### Gate G2 — Integration/main

After integration or nightly as suite grows:

- full deterministic test suite;
- core instrumented suite on managed emulator/device matrix;
- replay regression corpus;
- migration tests for every supported schema path touched.

### Gate G3 — Release candidate

Before declaring a build usable as a serious personal beta:

- G2;
- clean-install permission flow;
- manual happy path and auto happy path;
- process/recovery scenarios;
- accessibility checks on critical screens;
- manifest/dependency/privacy review;
- performance smoke tests;
- no known P0 data-loss/corruption bug.

### Gate G4 — Detector/location profile freeze

Before freezing production detector/location defaults:

- F0.6 Pilot completed;
- Validation dataset completed;
- start/stop latency and false start/stop metrics reviewed;
- battery measurements reviewed;
- replay suite created from representative cases;
- EXP-008 approved.

Field validation is therefore a release input, not replaced by automated tests.

---

## 21. Severity and release policy

### P0 / Blocker

Examples:

- lost/corrupted Trip evidence;
- duplicate active capture caused by recovery;
- merge/split destroys raw source lineage;
- UI claims Trip finished when persistence failed;
- Full Auto silently records without required user-visible state;
- migration destroys user history.

**Policy:** release blocked.

### P1 / High

Examples:

- common legitimate ride missed;
- traffic light frequently splits Trips;
- pause/resume incorrectly changes logical Trip;
- major History/Trip Detail flow broken;
- severe performance issue on supported hardware.

**Policy:** normally release blocked unless explicitly accepted for a non-production experimental build.

### P2 / Medium

Examples:

- secondary filter/sort defect;
- visual issue that does not hide critical state;
- non-critical metric/chart defect.

### P3 / Low

Cosmetic/polish issues with no integrity or safety impact.

---

## 22. Definition of Done for implementation tasks

A future Codex/Claude task that changes product behavior is not Done merely because it compiles.

The task should report:

1. requirements implemented;
2. tests added/updated;
3. commands executed;
4. test results;
5. manual/device validation performed when needed;
6. known limitations;
7. documentation/ADR changes if behavior or architecture changed.

A task that cannot test a behavior must explicitly explain why and identify the later gate that will validate it.

---

## 23. Minimum traceability convention

Tests SHOULD use stable IDs or names that can be mapped to requirements and incidents.

Example:

```text
Requirement: FR-DET-004 Temporary stop tolerance
Tests:
- DET_TRAFFIC_LIGHT_001
- DET_CONGESTION_002
- REPLAY_URBAN_STOPGO_004
Field:
- EXP-SCN-TRAFFIC-LIGHT
```

A future test catalog generated in CI may automate this mapping, but F0.12 only defines the convention.

---

## 24. Sources and platform basis

Official Android references consulted for this baseline:

- Android Developers — Test apps on Android: https://developer.android.com/training/testing
- Android Developers — Fundamentals of testing Android apps: https://developer.android.com/training/testing/fundamentals
- Android Developers — What to test: https://developer.android.com/training/testing/fundamentals/what-to-test
- Android Developers — Testing strategies: https://developer.android.com/training/testing/fundamentals/strategies
- Android Developers — Compose testing: https://developer.android.com/develop/ui/compose/testing
- Android Developers — Compose accessibility testing: https://developer.android.com/develop/ui/compose/accessibility/testing
- Android Developers — Big test stability: https://developer.android.com/training/testing/instrumented-tests/stability
- Android Developers — Room MigrationTestHelper: https://developer.android.com/reference/androidx/room/testing/MigrationTestHelper
- Android Developers — WorkManager integration testing: https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing
- Android Developers — Macrobenchmark / Baseline Profile measurement: https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile

These sources define platform testing capabilities and best practices. Product-specific thresholds and field acceptance targets remain governed by F0.6 and later measurements.

---

## 25. Decisions accepted in F0.12

1. Testing is layered; most domain behavior must be provable without a real ride.
2. Detector/location behavior receives a versioned replay regression suite.
3. Fake clocks and replay sources are architecture requirements for testability.
4. Room migrations and transaction rollback are release-blocking test areas.
5. Merge/Split require invariant/property testing in addition to example tests.
6. Process death, user stop, reboot, permission loss and GPS gaps require explicit integration/manual protocols.
7. Compose UI tests use semantics/user behavior, not implementation internals.
8. Accessibility combines automated checks and manual assistive-technology validation.
9. Performance thresholds are measured on target hardware rather than invented in Phase 0.
10. Battery and detector-profile freeze require F0.6 physical field validation.
11. Flaky tests are tracked defects; arbitrary sleeps are prohibited as a synchronization strategy.
12. A release candidate is blocked by unresolved P0 integrity/recovery/privacy failures.

---

## 26. F0.12 closure criteria

F0.12 is **CLOSED baseline v0.1** because:

- unit/integration/UI/application/field layers are defined;
- test seams and replay infrastructure are specified;
- detector, GPS, database, worker and service responsibilities have test contracts;
- permission/privacy/recovery scenarios are mapped;
- merge/split invariants are explicit;
- compatibility, performance, accessibility and flakiness policies are defined;
- commit/merge/integration/release/detector-freeze gates exist;
- future implementation tasks have a test-oriented Definition of Done;
- remaining thresholds depend on field measurements rather than undocumented assumptions.

---

## 27. Next workstream

**F0.13 — Observability & Diagnostics**

F0.13 must define the evidence that exists when something fails in the real world:

- TripEvent contract;
- detector-state/event logging;
- location-quality diagnostics;
- processing/recovery event codes;
- local diagnostic retention;
- debug screen;
- diagnostic export;
- redaction/privacy rules;
- detector/location/processing version visibility;
- incident bundle usable by Claude/Codex during debugging.
