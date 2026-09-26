# Moto Trip Tracker V2
## Phase 0 — Master Document

**Project type:** Greenfield Android application  
**Status:** Phase 0 documentation closed (GO, F0.17); Phase 1 implementation underway — **Wave W0 and Wave W1 both complete** (`TRK-001`, `TRK-002`, `TRK-003`, `TRK-004`, `PRC-001`, `PRC-002`, `UI-001`, `HIS-001`, `PRC-003`, `NOT-001` (on-device verification closed 2026-09-21), `REC-001`, `MAP-001` done); Wave W2 started early (`DET-001`, `DET-002`, `DET-003`, `AUTO-001`, `DET-004`, `DET-005`, `DET-006`, `DET-007` done; `EXP-002`/`EXP-003` harness+profile-switching done, Campaign S1 pilot data collection closed 2026-09-23 (one real ride per S1-A/S1-B/S1-C, `EXP-008`'s evidence-sufficiency question still open); `REF-001`/`REF-002` V1 parity/migration design done); Wave W3 started early (`TRS-001`, `FAV-001`, `MET-001`, `MAP-002`, `HIS-002`, `EDT-001`, `EDT-002`, `EDT-003`, `EDT-004` done); Wave W4 started early (`REC-002`, `REC-003` done; `PERM-001` narrow Start-crash guard (narrow Start-crash guard done 2026-09-22, full onboarding scope still open)  
**Version:** 0.55
**Last updated:** 2026-09-25

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
| F0.16 | V1 Reference (Feature Parity & Data Migration) | **Reinstated 2026-09-19, scoped v0.1**. See `docs/00-master/f0-16-v1-reference.md`. Parity checklist (`REF-001`) and migration design (`REF-002`) both done 2026-09-20 — see `docs/00-master/f0-16-v1-parity-checklist.md` and `docs/00-master/f0-16-v1-migration-design.md` |
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

**Update, 2026-09-19 (project-owner decision):** F0.16 is reinstated, narrowly. See `docs/00-master/f0-16-v1-reference.md`. Scope is limited to (a) a V1-vs-V2 feature parity checklist and (b) V1-to-V2 data migration planning — both executed as backlog tasks `REF-001`/`REF-002`. This does **not** reopen V1's architecture, code, or "lessons learned" for inspection (that angle was explicitly declined), and does not change `DEC-001`/`ADR-001`'s core greenfield decision: V2's own architecture is not being revisited against V1.

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

`DET-003` (Candidate Stop engine) followed the same day: `domain/detection/CandidateStopEngine` mirrors `CandidateStartEngine` with the trigger reversed (`IN_VEHICLE` EXIT opens a candidate) and a deliberate asymmetry — no displacement check, since F0.3 §7 treats either staying put or walking away as valid evidence the ride ended, so a `WALKING`/`ON_FOOT` ENTER confirms immediately while sustained absence of a re-`ENTER` for a 3-minute grace period (12× longer than start's 15s, per §7's own "intentionally more conservative" framing) confirms otherwise. It leans on Google's own Transition-API filtering of transient `STILL` states rather than re-implementing traffic-light tolerance from raw speed, which is this task's actual acceptance bar. Done, unit-verified (124/124), again with no live caller and nothing to verify on-device yet.

`AUTO-001` (Capability-aware automatic orchestration) followed, at the user's direction, and is the task that finally gives `DET-002`/`DET-003` a live caller and `CAP-001`'s `CapabilityResolver` its first production consumer. A new `AndroidCapabilityInputsProvider` reads real permission/service state, backed by a new `AutoTrackingPreferences` (DataStore Preferences 1.2.1) for the one persisted "Auto Tracking enabled" boolean, defaulting `false` so a fresh install correctly resolves `MANUAL` rather than an auto mode by accident. `ActivityTransitionReceiver` now also publishes onto a new `ActivityTransitionBus` and, on an `IN_VEHICLE` ENTER with no active capture and a capability mode that allows it, starts `TrackingForegroundService` in a new `ACTION_AUTO_DETECT` mode. `TrackingSessionCoordinator.runAutoDetection` is one continuous coroutine — merging the bus, the same location stream, and an internal ticker — that feeds `CandidateStartEngine` until confirmed, then switches to persisting Raw Track points and feeding `CandidateStopEngine` until *it* confirms, auto-finishing the trip. The candidate-validation phase shows a distinct, honest notification (F0.9 §5.3) rather than "recording your trip". Several v1 simplifications are documented rather than silently accepted: candidate-phase location samples are evaluated but never retroactively persisted once confirmed (a small, accepted loss against F0.3 §6 requirement 5); a process death mid-auto-tracking resumes plain recording but not stop-monitoring; auto-stop only ever applies to auto-started captures, never a manual one (DP-005). Done, unit-verified (141/141, up from 124) including deterministic virtual-time-scheduled coordinator tests exercising a full confirm-then-auto-finish round trip against real database rows. Once the phone reconnected, `ACTION_AUTO_DETECT` was confirmed on real hardware (Honor DNY-NX9, Android 16) by invoking the real, non-exported service directly via adb: it reached `isForeground=true` with no crash, and a device screenshot confirmed the real posted notification reads "Checking for a possible trip…", not "Recording your trip" — F0.9 §5.3's notification-honesty claim confirmed rendering on actual hardware. A before/after diff of the real production database (pulled binary-safe via `adb exec-out`, since `adb shell ... >` was found to corrupt the pull with CRLF-mangled bytes) confirmed no spurious `ACTIVE`/`AUTO` capture or diagnostic event was created from real GPS noise and the internal ticker alone, with no genuine trigger. What remains honestly unverified: a full run driven by a real Activity-Recognition `IN_VEHICLE` transition — the same wire-format-isn't-fabricable-via-adb limitation `DET-001` already documented, compounded by this device's already-known unreliable real transition delivery.

`DET-004` (Temporary-stop hysteresis) followed, continuing the roadmap after the user opted to defer real field testing until `PERM-001`/`PERM-002` (Wave W4) build an actual Auto Tracking toggle UI — none exists yet, so `AUTO-001`'s own trigger path can't be exercised end to end on a real ride today. Unlike every task before it in this wave, `DET-004` added **no new production code**: tracing its acceptance criterion (F0.3 §18 SCN-006/SCN-026, congestion/traffic-light churn must stay one Trip) against what `DET-002`/`DET-003`/`AUTO-001` already do showed the property already holds by construction — Google's own Transition-API filtering means ordinary congestion never even emits an `IN_VEHICLE` EXIT, and for the case where it genuinely does flap, `CandidateStopEngine` resets fully to `Tracking` on every `Abandoned` with no memory across cycles, so repeated churn is provably just the same already-tested single cycle repeated. The task's actual deliverable is two new regression-proof tests making that explicit rather than merely inferred: a 10-cycle churn test on the engine itself, and a full-stack `runAutoDetection` test proving one continuous AUTO capture survives repeated churn (with location samples persisting uninterrupted throughout) before a real stop correctly finishes it. Done, verified (143/143, up from 141) — a deliberate case of verification rather than implementation, documented as such rather than inventing an unneeded hysteresis mechanism to have "done" something.

The user then asked for `DET-005` directly, which surfaced a real, hard prerequisite gap rather than just a wave-sequencing preference: `DET-005` ("manual-pause warning logic") needs a real Pause to warn about, and `TRK-003` (manual pause/resume, a W1 task) had never been built — `ManualPauseIntervalEntity` was schema-only since FND-003. Asked to choose between building `TRK-003` first or switching to the unblocked `DET-006`, the user chose to build `TRK-003`. "Paused" is not a new `CaptureStatus` - it's an open `ManualPauseIntervalEntity` row, exactly as F0.7 §6.4 already specified, so every existing ACTIVE-status check kept working unchanged. Both existing location-recording paths (`recordLocationUpdates` from TRK-002, `runAutoDetection`'s tracking phase from AUTO-001) became pause-aware - checking for an open pause per sample and skipping persistence, and, for the auto-detection path, also skipping stop-engine evaluation entirely (F0.3 §8: "automatic stop detection should not silently close a manually paused Trip") - rather than being cancelled and relaunched, so a live stream stays available for `DET-005`'s own forgotten-pause monitor to watch later. `finishCapture` now actually performs F0.10 §15.1's "close any open pause" step it had documented as a no-op since TRK-004, and PRC-002's `manualPauseDurationMs` placeholder (a hardcoded `0`) is now a real computation over closed pause intervals. A genuinely flaky test was caught and fixed mid-task: an early version raced a separately-launched, `delay()`-timed coroutine's Pause/Resume calls against Room's real (non-virtual) executor threads; sequencing those calls inside the flow builders themselves instead made it deterministic, confirmed across 5 repeated runs. Done, verified (158/158, up from 143) and confirmed for real on-device (Honor DNY-NX9): a genuine Start → Pause → Resume → Pause → Finish-while-paused sequence produced two real, correctly-closed pause rows and a real `manualPauseDurationMs` matching their summed duration exactly. Raw-point skip-while-paused itself wasn't separately confirmed on-device (the test device never acquired an indoor GPS fix in the available window) - that exact path is proven instead by a real Room-backed unit test.

`DET-005` (Manual-pause warning logic) followed immediately, now that `TRK-003` had built something to warn about. New `domain/detection/ForgottenPauseEngine` is deliberately location-only, unlike `CandidateStartEngine`'s activity+location combo: the path a manually-started, manually-paused capture actually uses never sees activity transitions, only location samples, so gating on an `IN_VEHICLE` signal would leave that whole case (F0.3 §8's own primary example — "eating, resting or visiting a location") undetectable; a larger displacement/duration bar than `CandidateStartProfile`'s own start thresholds stands in for that missing evidence instead. Wired into both existing location-recording paths through one small shared `ForgottenPauseWatch` helper, with a new `onForgottenPauseWarning` callback (mirroring `runAutoDetection`'s existing `onCaptureStarted` pattern) letting `TrackingForegroundService` post a real, dismissible reminder via a new `TrackingNotificationController.postForgottenPauseReminder()` — nothing in this task ever touches capture or pause state itself, which is exactly what "manual ownership remains authoritative" means made concrete. Writing the `runAutoDetection` warning test surfaced the same subtle class of bug TRK-003 had already found, twice in a row: a merged flow's upstream sources buffer independently, so a producer coroutine can race arbitrarily far ahead of what the *consumer* has actually processed - first `resumeCapture()` raced ahead of `pauseCapture()`, and after gating that, raced ahead of the warning itself being consumed. Both were fixed with explicit consumer-side completion signals, not guessed delays, and both were caught by an actual failing/hanging test run, not suspicion. Done, verified (169/169, up from 158) including a Robolectric-shadow proof of the real posted notification's shape. Confirmed on-device (Honor DNY-NX9) that a full Start→Pause→Resume→Finish cycle with this code active still runs cleanly - triggering the actual forgotten-pause scenario live needs sustained real GPS movement this device couldn't produce indoors, the same honest limitation already documented for `AUTO-001`.

`DET-006` (Duplicate-start / post-finish suppression) followed, closing F0.3 §9's "post-manual-finish suppression" gap: "a rider may press Finish while the device is still moving; without protection, the detector could immediately create a new candidate Trip." New `domain/detection/PostFinishSuppression` (pure decision logic, same posture as `CapabilityResolver`) plus a 2-minute placeholder profile — F0.3 §9 itself calls the exact timeout "a research question", so this deliberately implements only the time-based half of what the spec leaves open, not a guessed version of the harder half ("lift early once evidence shows movement ended"). Wired into `ActivityTransitionReceiver.maybeStartAutoDetection` as one more gate alongside its existing checks, fed by a new `TripCaptureDao.findMostRecentlyEnded`. "Duplicate-start" and "post-finish suppression" turned out to be the same concern from two angles rather than two mechanisms — ADR-015/ADR-020 already cover ordinary duplicate-Start protection; this task's actual job was specifically the rebound-after-Finish case neither of those touches. A Manual Start bypasses the window by construction (it never goes through this receiver), so DP-005 already guarantees F0.3 §9's "ends after another explicit Manual Start" clause without any special-casing. Done, verified (176/176, up from 169). No on-device verification — this gate's own trigger (a real Activity Recognition transition) has the same wire-format limitation already documented for `DET-001`/`AUTO-001`, and the phone wasn't connected when this task ran.

After `DET-006`, the user asked which of the remaining unblocked tasks was most logical next and then chose between the assistant or Codex implementing `UI-001`, opting for the assistant given Codex's repeated credit exhaustion that session. `UI-001` (App shell, Home and Active Trip) followed, returning to Wave W1 to build the first actual UI on this codebase - everything before it was headless. Implements ADR-011 literally: Navigation 3 with a plain, non-saveable `mutableStateListOf<Destination>` back stack, since F0.8 §14 already establishes that the back stack is never the source of truth for an active Trip (Room rehydration, `TrackingSessionCoordinator`'s existing job, is) — nothing to gain from a saveable stack and a real invariant to violate if it were. `HomeViewModel`/`ActiveTripViewModel` observe Room DAOs reactively (`flatMapLatest`/`combine`, no repository layer, matching the rest of the codebase); Start/Pause/Resume/Finish all go through the same `TrackingForegroundService` commands the UI-less tasks already built, never bypassing the Service (ADR-004). Hit the same compileSdk-37 dependency wall as the Compose BOM downgrade already on record, one layer up this time (`hilt-navigation-compose` 1.4.0's transitive `lifecycle-*-compose`/`hilt-lifecycle-viewmodel-compose` chain), root-caused by inspecting real AAR metadata and fixed the same way: pin to the newest release still under this project's compileSdk-36 ceiling (1.3.0), not a guessed version. Found and fixed a real reactivity bug live on-device, not by inspection: `recentTripsFlow`'s first version couldn't ever notice `TripProcessingWorker` writing statistics after the trip already existed, because Room only invalidates a query's Flow when a table it reads from changes — a freshly-finished trip showed "-/-" forever. Fixed and captured as a regression test confirmed to actually fail against the original code first. Done, verified (177/177, up from 176, including that new regression test) and confirmed live on-device: a full Start→Pause→Resume→Finish→back-to-Home cycle, with Home's stats updating within ~1s of Finish with no app restart needed. History/Favorites/Settings are real, navigable destinations but stay minimal "coming soon" placeholders — `HIS-001`/`FAV-001`/the `SET-` family own the real screens; `MAP-001` isn't built, so Active Trip's route area is an honest placeholder, never a fake map.

Right after `UI-001` shipped, the user ran two real outdoor test rides on the actual device and reported forgetting to press Finish on one of them - the first genuine field feedback this project has gotten since Phase 1 implementation began. Pulling the real production database (via `sqlite3.exe`, found bundled with the Android SDK's own `platform-tools` rather than a separately-installed tool) confirmed the app itself behaved correctly and, more importantly, produced the **first real end-to-end GPS proof this codebase has**: two genuine motorcycle rides (2.07 km/~7 min and 5.38 km/~37.6 min) with plausible real speeds (~98 km/h max) and honest ~7m accuracy, both correctly `COMPLETED`. But the long ride's average speed came out near 8.6 km/h - exactly what "rode normally, then sat parked a long time before remembering to finish" looks like, surfacing a real gap `F0.3` never scoped: a manually-started capture has no automatic stop at all, unlike an auto-started one. `DET-007` closed it: a new `ForgottenFinishEngine`, the deliberate inverse of `DET-005`'s `ForgottenPauseEngine` (re-anchors on real movement, warns once stationary past a placeholder 10-minute bar), wired only into the manual-capture path and surfaced as a dismissible reminder sharing `DET-005`'s notification channel - never an auto-finish, since manual ownership stays authoritative (DP-005) same as the forgotten-pause case. `trip-detection-spec.md` gained a new "Forgotten-finish scenario"/SCN-029 documenting this as a post-launch addition, not a pre-existing requirement quietly reinterpreted. Done, verified (188/188, up from 177) via the engine's own tests plus real Room-backed coordinator tests, including a dedicated regression test for a subtle timing bug caught before it ever reached a device (a pause must discard the pre-pause stationary anchor, or the first sample after Resume could misfire immediately). Not verified live on-device - the 10-minute bar needs a genuine real-time wait, the same class of honest gap already accepted for `DET-005`'s own on-device claims.

The user then chose `HIS-001` from the remaining W1 options. It replaced `UI-001`'s placeholder History tab with the real, full chronological list (F0.9 §8) plus a new Trip Detail screen (F0.9 §9) - the first parameterized destination in the Nav3 graph, showing every metric `PRC-002` already computes (and an honest "—" for the ones `PRC-003` doesn't yet). Both new screens' reactive statistics joins were written correctly from the start by directly applying `DET-007`'s own lesson (combine a `Flow` per trip's statistics, never a one-shot suspend read nested in a table-scoped `mapLatest`), each backed by its own regression test rather than trusted by inspection. Favorite is shown as a read-only indicator (the `Trip.isFavorite` column has existed since `FND-003`) but not made toggleable - that's `FR-FAV-001`'s own requirement, kept out of this task's scope. Rename works and propagates live to Home's own preview row without a restart. Done, verified (200/200, up from 188) and confirmed on-device against the two real GPS rides from `DET-007`'s field session, including a real rename that persisted correctly.

**An unrelated incident during that on-device verification, corrected here for accuracy**: an automated tap near the status bar hit an incoming WhatsApp notification instead of the app's edit icon, and a real phone call appeared active on the device around the same time - the user later clarified they placed that call themselves, unrelated to the automated tap. The genuine lesson kept from this: a heads-up notification can intercept a tap meant for the app's own top bar, so a screenshot immediately before any such tap is now this session's practice. Recorded in `HIS-001`'s own report, corrected rather than left with an inaccurate causal claim.

`PRC-003` followed, closing FR-MET-010's "research-gated" elevation gain/loss requirement with a documented placeholder rather than leaving it null indefinitely: `gps-location-research.md` explicitly rules out summing raw altitude deltas and names hysteresis/vertical-accuracy filtering as the methods to validate, so the new `ElevationCalculator` implements exactly those two, gated independently per F0.7's own rule (elevation range fills with any valid sample; ascent/descent needs a minimum reliable count). A real requirements-vs-schema gap surfaced and was recorded rather than resolved either way: FR-MET-009 also asks for start/end elevation, which `TripStatisticsEntity`'s frozen schema has no columns for. Done, verified (211/211, up from 200) and confirmed on-device with no crash on a real Start→Finish cycle - genuine non-zero ascent/descent still needs a real ride with elevation change, an honest gap not yet closed. While verifying on-device, a real `HIS-001` gap was found and fixed: Home's own "Recent" rows weren't clickable, even though `ux-navigation.md` explicitly documents "Trip reciente -> HIS-02" - fixed with the same pattern History's rows already used.

`NOT-001` followed, giving the persistent tracking notification the Pause/Resume/Finish actions F0.9 §7's wireframes specify, targeting the exact same idempotent `TrackingForegroundService` intents the in-app UI already uses (ADR-015's idempotency inherited for free, not re-implemented). A new `TrackingSessionCoordinator.currentTrackingSnapshot` gives the Service live distance/duration/pause-state on demand, refreshed immediately on Start/Pause/Resume/rehydrate plus a 30s ticker otherwise - a documented placeholder cadence chosen over rescanning the whole raw-point history on every ~2s location sample. Tapping the notification body now opens Active Trip directly on a cold launch (via a `MainActivity` extra), with the "already running elsewhere" case deliberately left as default Android behavior rather than added `onNewIntent` complexity for a UX nicety F0.9 itself doesn't test as an acceptance criterion. Done, verified (219/219, up from 211) via Robolectric-shadow proof of both notification variants' real shape (text, actions, content intent). **On-device verification closed 2026-09-21**: real Pause/Resume/Finish taps from the actual notification shade on the Honor DNY-NX9 produced the correct live text changes and the correct database state each time (a real `manual_pause_interval` row opened then closed, a real `COMPLETED` capture/Trip on Finish). An incidental `am force-stop` test also confirmed `REC-001`'s rehydration survives a harsher kill than it was originally scoped for - Home kept reflecting the orphaned `ACTIVE` capture reactively with the Service dead, and tapping Pause from Home correctly restarted the Service and rehydrated the same capture rather than duplicating it. The one sub-behavior not re-confirmed this round: tapping the notification body to open Active Trip on a cold launch, since force-stop removes the notification itself before that can be tested - already documented above as a UX nicety outside this task's own acceptance line.

`REC-001` closed out the same-boot half of F0.10's recovery checklist. `TRK-001`'s sticky-restart rehydration already resumed an `ACTIVE` capture correctly; what was missing was F0.10 §7.1's own item 2 - checking that the current `elapsedRealtime` is actually compatible with what was persisted - and item 6, logging a `PROCESS_RECOVERED` event. The compatibility check turned out to matter more than it sounds: F0.10 §10.1/§10.2 are explicit that a real reboot must never be treated as an ordinary same-boot resume - the capture has to be closed as `ABORTED` using its own last persisted evidence, never an invented "now", and never resumed. New `TrackingSessionCoordinator.recoverActiveCaptureIfAny()` makes that exact decision (`Resumed` vs `AbortedAfterReboot` vs `NoActiveCapture`) using a direct, checkable signal: the current monotonic clock reading going backward relative to the capture's own recorded start. Deliberately not built: a visible partial Trip for an aborted capture (F0.10 §22 itself says this is optional, "puede", not required) and a full permission/capability gate on the recovery path (not in this task's own Acceptance line). Done, verified (225/225, up from 219) and **confirmed live with a genuine process kill** (not just Robolectric): started a real trip, found its PID, killed it with `kill -9`, let Android's own sticky restart bring the service back under a new PID, and confirmed directly from the pulled production database that a real `PROCESS_RECOVERED` event was logged and the capture correctly stayed `ACTIVE` and kept recording (39 raw points across the restart) rather than being duplicated or lost. The reboot/abort path itself wasn't tested against a real device reboot (too disruptive to trigger deliberately against the user's own phone) - proven instead by the unit-test suite. Only `MAP-001` remains to close Wave W1 entirely.

`MAP-001` closed out Wave W1 entirely. `ADR-019` had deliberately deferred the map provider until licensing/cost/Compose-support/offline-behavior could be evaluated for real - this task did exactly that (live Maven Central metadata, MapLibre's own GitHub releases, OpenFreeMap's own site, not assumptions) and recorded the decision as **`ADR-021`**: MapLibre Native + OpenFreeMap's public vector tiles, both free of any API key or billing account, the same posture this project already took once before when it rejected Google's Elevation API (F0.5) for exactly that reason. `feature/map/TripRouteMap.kt` is the only file allowed to import the map SDK - `domain`/`tracking`/`core` only ever see a plain `List<GeoPoint>`, `ADR-019`'s isolation boundary made concrete. New `domain/RouteSimplifier` (Ramer-Douglas-Peucker) handles `FR-MAP-003`'s "usable with thousands of points," with a real 10,000-point benchmark test as part of the ordinary suite, not a separate tool. Done, verified (233 tests, all still passing) and **confirmed live on a real device** using "Coastal loop"'s already-recorded route from `PRC-003`'s own field session: real OpenFreeMap tiles, the actual GPS polyline, distinguishable start/end markers, a working fit-route button. That same on-device pass caught a real bug no unit test could have (`TripRouteMap` needs a real GL-capable environment MapLibre Native can render into, which Robolectric doesn't provide): navigating between two different Trips' detail screens kept showing the first Trip's route because the map's route configuration only ran once in `AndroidView`'s `factory`, never reacting to `points` actually changing - fixed by moving it into a `LaunchedEffect(points, map)`, and reconfirmed against the same navigation sequence that first revealed it.

`FAV-001` followed, the first Wave W3 task started (ahead of the rest of W3, same early-start posture Wave W2 already took relative to W1). `TripDao.setFavorite` is a targeted `UPDATE`, the same shape as `HIS-001`'s own `rename`; a new `observeFavoritesDescending` mirrors `observeAllDescending` filtered to `isFavorite = 1`. A shared `TripSummaryRow`/`TripSummaryUi` (`feature/common/TripSummaryRow.kt`) replaced `HistoryScreen`'s own private row so History and the new Favorites screen render identically instead of duplicating the card - the star is now a real `IconButton`, not the read-only indicator `HIS-001` deliberately left for this task. `UI-001`'s `FavoritesPlaceholderScreen` is replaced outright by a real `FavoritesScreen`/`FavoritesViewModel`, mirroring `HistoryViewModel`'s DAOs-direct/per-trip-statistics-Flow shape for the same Room-invalidation reason. No ADR requires a coordinator layer for this kind of edit - ADR-004/ADR-015 are both scoped to live-tracking commands and structural edits, and `domain-data-model.md` already classifies favorites as not affecting metrics/processing, the same posture as notes. Done, verified (272/272, up from 265) and confirmed live on-device (Honor DNY-NX9): toggling from Trip Detail's new topbar star, from a History row, and from a Favorites row all correctly persisted and reflected back reactively across all three screens, including the Favorites list correctly emptying itself the instant a trip was unfavorited from within it.

`TRS-001` followed. Implements `FR-HIS-005`/`FR-EDT-006` against the purge policy `privacy-permissions.md`/`PRIV-013` had already accepted (30-day retention) but left its reference-counting mechanics for this task to design. Soft-delete (`Trip.status = TRASHED` + `deletedAt`) is a plain targeted `UPDATE`, same shape as rename/favorite - nothing is destroyed, so no `TripEditOperationEntity` logging is needed yet (`EDT-001`'s own job for merge/split). A new `worker/TripPurger` does the real physical delete, shared by a new `worker/TrashPurgeWorker` (the codebase's first periodic WorkManager job, once/day, scheduled from `MotoTripApplication.onCreate()`) and the new Trash screen's immediate "Delete forever" - both enforce domain-data-model.md's reference-counting rule for real: a Trip's own rows cascade away, but its TripCapture (and raw GPS evidence, ADR-006) only goes with it once no other TripPart references it, `TripPartEntity`'s own `RESTRICT` FK backing that up rather than trusting it blindly. A real integration bug was caught before reaching a device: eagerly injecting the new scheduler into `MotoTripApplication` made Hilt construct it (needing WorkManager) before this file's own `WorkManager.initialize()` call, breaking 155 unrelated tests across the whole suite - fixed with `dagger.Lazy`. Done, verified (288/288, up from 272) and confirmed live on-device including a real "Delete forever" checked directly against the pulled production database: `trip`/`trip_capture`/`trip_part` row counts dropped in lockstep with zero orphaned captures, proving the reference-counting cascade holds on real data.

`MET-001` closed out last, inventing a defensible presentation (confirmed with the owner first) for two concepts - "quality/degraded labels" and "recalculation visibility" - that no `FR-MET` requirement or wireframe actually specifies: a data-quality note shown only when there's something real to report (never decorative on a clean trip) and a plain "Calculated {date}" footer, both built from fields already computed. Also closed a real, previously-flagged gap: `FR-MET-009`'s start/end elevation had no schema columns (`PRC-003`'s own noted deferral) - this task added them via this schema's first real migration (`MIGRATION_1_2`, v1→v2, additive-only, never destructive), verified for real with this project's first instrumented test (`MigrationTestHelper` against the actual exported v1 schema on a real device), which is exactly the moment `FND-003`'s `androidTest` harness was built for. Done, verified (301/301, up from 288) including the new instrumented migration test passing on real hardware.

**A real incident during that same on-device verification, corrected here for accuracy.** Running the new instrumented-test task for the first time caused the target app to be fully uninstalled and reinstalled on the connected device as a side effect of preparing instrumentation - wiping the real production database (~20 field-test trips, unrecoverable) and every granted runtime permission. The next day, every real trip-start attempt crashed with a `SecurityException` (missing location permission) - not a code regression, but the direct, foreseeable consequence of that wipe meeting an already-known gap: nothing yet requests or gracefully degrades around a missing permission (`PERM-001` doesn't exist). Fixed by re-granting the permissions via adb and re-verified with a real Start→Finish cycle; recorded as a standing rule that `connectedAndroidTest`-family tasks must never run again against a device holding real data, and as the most concrete evidence yet for why `PERM-001` is needed.

A narrow piece of `PERM-001` was pulled forward the same day, directly in response to that incident: neither Home's Start Trip button nor the field-test harness ever checked location permission before starting the tracking service, so a missing permission crashed the app outright rather than degrading gracefully - the exact crash the user hit. `CapabilityResolver`/`CapabilityMode`/`AndroidCapabilityInputsProvider` were already built and tested and needed no changes; what was missing was any real permission-request flow anywhere in the app (confirmed: zero `ActivityResultLauncher` usage anywhere before this). Added a small, reusable `rememberStartWithLocationPermission` guard, wired into both crash sites, with an explanatory dialog on denial rather than a silent no-op. This is deliberately only the crash guard, not `PERM-001`'s full onboarding flow (ONB-01/ONB-02), which - along with `PERM-002`/`PERM-003`'s own scope - still has no written Objective anywhere; that scoping work remains open. Confirmed live on a disposable emulator, not the device holding real data, per the standing rule this same incident established: revoking location permission and tapping Start now shows the real Android permission dialog instead of crashing, and granting it correctly starts and finishes a real trip.

**Campaign S1's pilot data collection closed the next day (2026-09-23)**, with the permission fix holding up under real use: three real rides, one per candidate GPS profile (`S1-A` 0.6 km/1m44s, `S1-B` 29.9 km/57min, `S1-C` 19.1 km/23min, real varied weather/routes), confirmed directly against the pulled production database and the on-device exported `session.json`/`raw-track.csv` files - not just the harness's own on-screen claim. Also found, and recorded rather than discarded, an orphaned `S1-A` attempt from during the permission-wipe incident itself: its session metadata correctly shows all permissions `false` at the time, and its `raw-track.csv` is genuinely empty rather than fabricated. This closes `EXP-002`/`EXP-003`'s own pilot objective, but deliberately not `EXP-008` (Hard gate G4): whether one ride per profile is enough evidence for the actual production-profile freeze, or whether `EXP-007`'s held-out validation needs more samples first, is recorded as still open rather than assumed.

`MAP-002` followed. Re-checking `MAP-001`'s own delivery first showed most of the task's title was already done (start/end markers, large-track handling via `RouteSimplifier`) - the real remaining scope was REF-001's own selected-point marker: tap the route, get a distinct high-contrast marker plus a real Compose accessibility node (MapLibre's GL-rendered markers have none of their own), dismissible by an "×" or by tapping elsewhere. A real bug was found and fixed live, not just tuned: the first version compared taps to route points using a flat real-world-meters tolerance, which fell apart at a whole-route zoom level (confirmed by logging a real tap on today's 30km pilot ride - it measured ~700m from the nearest point despite looking dead-on the line, since one screen pixel there covers tens of real-world meters). Fixed by comparing in screen-pixel space instead, the conventional zoom-independent way map SDKs do this. Deliberately left unbuilt: stop markers (`FR-MAP-004`, P1) - `TripStopEntity` has existed since `FND-003` with nothing anywhere populating it, and no doc specifies a detection heuristic; building one now would mean inventing a new, unvalidated `DET`-family-scale algorithm outside this task's own "render" objective, the same restraint `PRC-002`/`PRC-003` already applied to their own unvalidated metrics. Verified live on the Honor DNY-NX9 against three of today's real pilot rides, including confirming selection correctly resets rather than leaking between two different Trips' detail screens.

`HIS-002` closed the same day. Search (`FR-HIS-006`), a date filter (`FR-HIS-007`), and distance/duration sort (half of `FR-HIS-008`) are all plain Kotlin operations over `HistoryViewModel`'s already-loaded list, not new DB queries - and search specifically has to be client-side, since an un-renamed Trip's real display text (`fallbackTripName`) only exists in Kotlin, never in the `name` column a SQL search could reach. REF-001's own route-thumbnail scope addition needed a genuinely new, bounded, reactive multi-trip query (`ProcessedTrackPointDao.observeSampledByTrips`) sampled by a flat modulo rather than a SQL window function, because this app's minSdk 26-28 range predates the bundled SQLite version (3.25, API 29+) that window functions need and this project has no custom-bundled driver - a documented trade-off. `ADR-019`'s map-isolation boundary ruled out reusing `TripRouteMap` for the thumbnail, so a new `Canvas`-based `RouteThumbnail` composable (this codebase's first) was built instead. Done, verified (305/305, up from 301) and confirmed live on the Honor DNY-NX9 against today's four real trips: thumbnails visibly match each trip's real shape, the date/favorites filter chips and the six-way sort dropdown all work correctly against real data, and search/empty-state messaging behave as designed. **A real layout bug was found and fixed during that same verification**: the filter-chip row was a plain non-scrolling `Row`, and on the real device's screen width the fourth chip overflowed and rendered its label wrapped vertically letter-by-letter at the screen edge instead of just being cut off - fixed with `.horizontalScroll`, the conventional fix, and re-verified live. Deliberately not built: motorcycle/route/tag filtering - no data source exists yet for any of those dimensions, so filter UI for them would be dead UI, not a real feature.

`EDT-001` (Merge Trips) followed, this codebase's first real use of the `TripEditOperation`/`TripLineageLink` tables that had existed unused since `FND-003`. V1 itself never built this - its own roadmap documents merge only as a list of unresolved design questions, three of which needed a real owner decision here: selection is narrowed to chronologically-adjacent pairs only (from Trip Detail's own overflow menu, not a new multi-select mode), no artificial time-gap limit gates what counts as "mergeable" (the user's own explicit selection and preview already gate it), and this task is data-only toward undo - source Trips become `SUPERSEDED`, never deleted, but no undo UI is built yet (`FR-EDT-006` is P1, and `TRS-001`'s Trash/restore already covers the common case). A real, previously-dormant bug in `ProcessingEngine` was found and fixed along the way: its gap/order detection compared `elapsedRealtimeNanos` continuously across `TripPart` boundaries, which is only valid within one boot session - harmless as long as every Trip had exactly one TripPart (true until this task), but a reboot between two merged rides would have rejected the entire second capture as "out of order." Fixed by treating a capture-boundary crossing specially (skip the elapsedRealtime order check, always record a wall-clock-timed gap there instead). Done, verified (315/315, up from 305) and confirmed live on the Honor DNY-NX9 using two disposable throwaway trips (never the real pilot data): Trip Detail's real "Merge with previous" flow correctly combined them into one Trip whose map, duration and max speed were all genuinely recomputed by the existing processing worker, not copied, while the two originals disappeared from every list and the device's real production trips stayed untouched throughout.

`EDT-002` (Split Trip) followed, completing the split half of `FR-EDT-003`/`004`. Trip Detail's overflow menu opens a dedicated Split screen (`F0.9` SPL-01, `UX-10`): route map with a cut marker, a slider, and live Part 1/Part 2 distance+duration previews before anything is committed. A split creates two new Trips over the *same* capture with disjoint sequence ranges (the cut sits between two points; the crossing segment belongs to neither half - a shared boundary point was rejected because it would double-count a capture range the moment the halves were merged back), marks the source `SUPERSEDED` with lineage recorded, touches no raw point and no Room schema, and enqueues processing for both halves after the transaction commits. Three latent bugs that only become reachable once two Trips can share a capture were fixed: manual pauses were counted in full by both halves (now clipped to each part's elapsed range), processing one half wiped the other's point assessments (now deleted per part range), and Merge mis-ordered two halves of one split (now tie-breaks by position within the shared capture). Done, verified (344/344, up from 315) and confirmed live on the Honor DNY-NX9 - the on-device run was executed by the Codex CLI against a disposable recording (Split, previews, Cancel, confirm, both halves, merge back, Trash, real trips untouched) plus my own read-only check of the cut marker on a real 30 km ride. Honest gaps are recorded in the backlog: the disposable recording was stationary (0,0 km) so distance behaviour rests on unit tests and the real-trip check, the marker is easy to lose at tiny-route zoom levels, one unreproduced menu tap that didn't respond, and the ADR subagent review stalled so the ADR check was manual.

`EDT-003` (Boundary correction) followed, the last piece of `FR-EDT-005`'s "SHOULD" scope: Trip Detail's menu opens a Trim screen (Split's proven layout reused - no wireframe exists for this) with a two-handle slider and a live preview of the trimmed distance/duration. A trim yields ONE new revised Trip (`BOUNDARY_EDIT`, 1 input -> 1 output) over a narrower range of the same capture, supersedes the source, touches no raw point and no schema, and carries name/notes/favorite over unchanged. A unit test caught a real flaw in the first version - an untouched side was also snapped to the first/last GPS point, silently dropping the second between the last fix and Finish - so an untouched side now keeps the source's own edge. This task also used the Codex CLI for real: a read-only diff review found two genuine issues (an unguarded database exception in `save()` that could crash the app, and an unknown duration shown as 0 against ADR-016), both fixed in Trim and Split; and its on-device run found a stale-state bug (a cancelled Trim/Split reopened with the previous handles because the ViewModels outlive a visit) that I fixed and re-verified read-only on a real ride. Done, verified (363/363, up from 344). Two earlier device attempts verified nothing (phone disconnected, then locked) and are recorded as such.

`EDT-004` (Recalculation/invalidation) closed the editing family. Most of the *recompute* already existed by construction - every merge/split/trim creates new Trips whose derived data the existing worker computes from scratch - so the real gap was the *recovery* half `reliability-recovery.md` §20 requires: each edit commits first and enqueues processing after, in memory, so a process death in that gap would leave the new Trips without statistics forever. A `DerivedDataReconciler` now runs at each app start and re-enqueues processing for visible Trips with no statistics for the *current* `processingVersion` (idempotent via unique work; superseded/trashed Trips excluded); because it is keyed on the current version it also covers a failed worker and a future version bump (`ADR-014`). Trip Detail shows "Calculating trip data…" meanwhile, with the numbers left unknown rather than 0. Done, verified by 370/370 unit tests; on the device only a cold-start smoke test was possible - the healing path itself needs a Trip that lost its enqueue and rests on unit tests, which the backlog says plainly. Codex wasn't used for this task (credits).

`REC-002` (sticky service restart) opened the reliability work. `REC-001` had already made a restarted service rehydrate from Room but explicitly skipped F0.10 §7.3's permission check - and the gap was a real crash, not just a missing label: entering the foreground with the location type after the permission is revoked throws `SecurityException`. A restart with no command now checks the permission first; without it the service declares the recording degraded (a diagnostic event, once per capture), posts an honest "Trip recording stopped" alert instead of a fake tracking notification, leaves the capture `ACTIVE` with its evidence intact for reconciliation, and stops without asking to be restarted. `startForeground` is also guarded so a Finish always still works. Done, verified by 373/373 unit tests and by a real run on the phone (disposable trip, location permission revoked mid-trip: no crash, the alert appeared, the trip stayed in progress, then it finished normally). The backlog states the unverified assumption about sticky restarts and the Android-timeout rule.

`REC-003` (reboot boundary reconciliation) fixed a gap `REC-001` left behind: a reboot does not restart the sticky service, so the code that aborts a capture after a reboot almost never ran - the capture stayed ACTIVE forever, Home showed a dead "Trip in progress" card and ADR-020 blocked every new Start. `BOOT_COMPLETED` (the certain reboot signal, deliberately not the fooled-by-early-starts elapsedRealtime heuristic) and every process start now seal an orphaned capture at its last persisted evidence, and - per F0.10 §22 and §10.2 rule 5 - give an interrupted ride with a route a visible partial Trip, flagged on Trip Detail, instead of letting it vanish. Done, 381 unit tests; on the phone a real process kill confirmed the new start-up reconciliation leaves a live same-boot trip alone. A real reboot was not tested (it needs the owner to unlock the phone afterwards) and the backlog says so.

See `docs/05-roadmap/phase1-backlog.md` for task-level reports, including dependency-version deviations from F0.8's exact pins discovered by attempting real builds (documented there and in `gradle/libs.versions.toml`).

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
