# F0.3 — Trip Detection Specification & Research Questions
## Moto Trip Tracker V2

**Status:** Draft baseline  
**Version:** 0.1  
**Project:** Moto Trip Tracker V2  
**Phase:** 0 — Discovery & Design  
**Depends on:** F0.1 Product Definition, F0.2 Requirements & Scope

---

## 1. Purpose

F0.3 defines the intended behavior of automatic Trip detection before choosing Android APIs, sensor thresholds or implementation algorithms.

It answers:

- what the system should consider a Trip;
- how automatic start and stop should behave conceptually;
- how traffic lights, congestion and short stops differ from a real Trip end;
- how manual start, pause, resume and finish interact with automation;
- how the product should behave when signals disagree or disappear;
- which scenarios must be reproduced during technical and field research.

F0.3 intentionally does **not** choose exact values such as `20 km/h`, `15 m`, `60 seconds` or GPS sampling intervals. Those values must be evidence-driven and validated later.

---

## 2. Core detection principle

Trip detection MUST be based on accumulated evidence over time rather than a single sensor sample or a single hard threshold.

Conceptually:

```text
LOW-POWER / PASSIVE SIGNALS
        +
MOTION / ACTIVITY EVIDENCE
        +
LOCATION / SPEED / DISPLACEMENT EVIDENCE
        +
TIME CONSISTENCY
        ↓
DETECTION CONFIDENCE
        ↓
STATE TRANSITION
```

The exact signals and weights remain research questions.

---

## 3. Detection design principles

### DP-001 — Multi-signal evidence
A single GPS point, speed value or activity label MUST NOT be enough by itself to start or stop a Trip.

### DP-002 — Temporal confirmation
Start and stop decisions MUST require evidence that persists or accumulates over an appropriate time window.

### DP-003 — Hysteresis
The rules for entering TRACKING and leaving TRACKING MUST NOT be identical. Start and stop require separate confidence/grace logic to prevent oscillation.

### DP-004 — Traffic tolerance
Temporary low speed or zero speed MUST be treated as normal during a Trip unless additional evidence indicates the ride has actually ended.

### DP-005 — Manual intent wins
Explicit user actions such as Pause, Resume and Finish take precedence over automatic interpretation.

### DP-006 — No fabricated route
When location data is missing or invalid, the application MUST record a gap/quality issue rather than inventing detailed route data.

### DP-007 — Explainability
Development builds MUST preserve enough state/evidence to explain why a start/stop decision occurred.

### DP-008 — Battery-aware escalation
High-detail location tracking SHOULD be activated only when a Trip is being validated or recorded, not permanently while idle, unless research proves this necessary.

---

## 4. Canonical Trip states

The working state model is:

```text
IDLE
  │
  ├── Manual start ───────────────────────────────┐
  │                                              │
  └── Possible motorized movement                │
             ↓                                   │
      CANDIDATE_START                            │
        │         │                              │
        │ valid   │ evidence fades               │
        ↓         └──────────────→ IDLE           │
      TRACKING ←─────────────────────────────────┘
        │
        ├── temporary stop → TEMPORARY_HOLD → TRACKING
        │
        ├── user pause → MANUAL_PAUSED → user resume → TRACKING
        │
        ├── possible end → CANDIDATE_STOP ── movement returns → TRACKING
        │                                  └─ end confirmed → FINALIZING
        │
        └── user finish ─────────────────────────→ FINALIZING
                                                   ↓
                                               PROCESSING
                                                   ↓
                                                  SAVED
```

`TEMPORARY_HOLD` may ultimately be implemented as an internal condition/substate rather than a persisted top-level state. The product behavior is what matters at F0.3.

---

## 5. State semantics

### IDLE
No active Trip exists. The system may monitor low-power signals if Auto Tracking is enabled.

### CANDIDATE_START
The system has evidence of possible motorized movement but has not yet created/confirmed a real Trip.

Expected behavior:
- gather enough additional evidence to confirm or cancel;
- avoid creating visible history for candidates that disappear;
- preserve diagnostic events in development builds;
- transition quickly enough that the beginning of a real Trip is not lost unnecessarily.

### TRACKING
A Trip is active and route recording is occurring at the active tracking policy.

### TEMPORARY_HOLD
The Trip remains active while movement is temporarily insufficient for normal tracking interpretation.

Examples:
- traffic light;
- traffic congestion;
- short stop sign;
- short roadside interruption.

This MUST NOT behave as a completed Trip.

### MANUAL_PAUSED
The user explicitly paused the Trip.

Rules:
- the same Trip remains open;
- detailed route points during the pause are not part of the active route by default;
- low-power movement detection MAY continue only to warn that the rider appears to have resumed movement;
- automatic logic MUST NOT silently resume the Trip;
- the pause interval is preserved separately from ordinary stopped time.

### CANDIDATE_STOP
The system has evidence that the ride may have ended but has not yet finalized it.

Rules:
- return to TRACKING if movement resumes;
- do not split the Trip because of a single idle event;
- allow a grace/confirmation strategy determined later.

### FINALIZING
Recording is closed and persistent source data is committed for post-processing.

### PROCESSING
The route and metrics are cleaned/recalculated. This state must not risk losing the already captured raw Trip.

### SAVED
The completed Trip is available in history.

---

## 6. Start-detection behavior

Automatic start must distinguish a real motorized departure from ordinary phone movement.

Potential evidence families to research:
- activity/motion classification;
- displacement over time;
- speed consistency;
- consecutive valid locations;
- heading continuity;
- acceleration/motion patterns;
- device/environment context where available and justified;
- optional future user context such as a known motorcycle Bluetooth device.

No individual signal is accepted as authoritative at this stage.

### Start behavior requirements

1. Walking to the motorcycle MUST NOT normally create a Trip.
2. Picking up or moving the phone around a room MUST NOT create a Trip.
3. Slow departure from a parking area SHOULD still be detectable without requiring an unrealistically high immediate speed.
4. A brief GPS jump MUST NOT create a Trip.
5. A real departure SHOULD transition from candidate to tracking without losing a large initial section of route.
6. If a candidate collapses, the system returns to IDLE without producing a normal Trip history record.
7. Manual Start bypasses candidate validation and creates an active Trip immediately.

---

## 7. Stop-detection behavior

Automatic stop is intentionally more conservative than temporary-stop recognition.

The detector must consider that motorcycle travel includes:
- traffic lights;
- stop signs;
- dense congestion;
- slow parking exits;
- fuel stops;
- gates/security checks;
- short photo/viewpoint stops;
- tunnels or temporary GPS loss.

### Stop behavior requirements

1. Zero speed alone MUST NOT finalize a Trip.
2. A short stop MUST remain part of the active Trip.
3. A long stop MAY enter candidate-stop only when additional evidence supports a real Trip end.
4. Resumed movement during candidate-stop MUST return to TRACKING without creating a split.
5. Walking away from the parked motorcycle SHOULD contribute to confirming the motorized Trip has ended rather than extending the route as a walking Trip.
6. Manual Finish closes the Trip immediately from the user's perspective.
7. After a manual finish, the detector SHOULD avoid instantly auto-starting a new Trip from the same ongoing movement. A suppression/cooldown concept must be researched.

---

## 8. Manual pause and resume contract

Manual Pause exists for deliberate interruptions such as eating, resting or visiting a location while preserving one logical ride.

### Required behavior

- Pause is available from the active-trip UI and notification.
- The Trip remains logically active.
- The pause start timestamp is persisted.
- Detailed movement during the pause is excluded from normal Trip distance/route by default.
- Resume closes the pause interval and continues the same Trip.
- Finish is available while paused.
- Automatic stop detection should not silently close a manually paused Trip unless the user explicitly chooses a policy later.

### Forgotten-pause scenario

If the phone detects sustained motorized movement while the Trip is manually paused:

- the app MAY issue a high-priority but non-distracting reminder;
- it MUST NOT silently resume recording;
- F0 research must decide whether a second reminder/escalation is useful;
- missed route geometry during manual pause should be treated as genuinely missing rather than reconstructed as if it were captured.

---

## 9. Manual finish and auto-start suppression

Manual Finish expresses explicit user intent that the current Trip is over.

Problem case:
A rider/passenger may press Finish while the device is still moving. Without protection, the detector could immediately create a new candidate Trip.

F0.3 therefore defines a conceptual **post-manual-finish suppression** mechanism.

The suppression should end after evidence shows that the previous motorized movement context has ended, or after another explicit Manual Start.

Exact timeout/logic remains a research question.

### Forgotten-finish scenario (added post-launch, DET-007)

Not originally scoped by this document - surfaced by real on-device dogfooding during `UI-001`'s own field use (2026-09-18): a manually-started capture has no automatic stop of any kind (unlike an auto-started one, which `CandidateStopEngine` already finishes on a real stop), so a rider who simply forgets to press Finish keeps recording indefinitely. The observed real trip's distance stayed correct (raw point-to-point summation doesn't inflate from GPS jitter while parked), but its duration and average speed were both meaningless for the extra stationary time.

If the phone detects a manually-started, non-paused capture has been stationary far longer than a normal stop:

- the app MAY issue a high-priority but non-distracting reminder, mirroring §8's forgotten-pause reminder;
- it MUST NOT auto-finish the Trip - manual ownership stays authoritative (DP-005), the same as the forgotten-pause case;
- the reminder may recur for a later, independent stationary period in the same still-active capture (unlike the forgotten-pause reminder's one-shot-per-pause scope), since a long ride can plausibly include more than one genuinely-forgotten-length stop.

Exact stationary duration/displacement thresholds remain a research question, same posture as this document's other placeholder thresholds (ADR-018).

---

## 10. Auto Tracking setting

The user MUST be able to disable automatic detection while retaining manual Trip controls.

When Auto Tracking is disabled:
- IDLE performs no automatic Trip start behavior beyond what the platform may require for already active tasks;
- Manual Start remains available;
- an already active Trip is not silently terminated just because the setting changes; the exact UX must be defined later.

This requirement is added to F0.2 as `FR-DET-011`.

---

## 11. Ambiguity: motorcycle vs other vehicle

A major product risk is that a phone may detect "motorized travel" more reliably than "motorcycle specifically".

The product MUST NOT assume that a platform activity label can always distinguish:
- motorcycle;
- car;
- bus;
- other motorized transport.

Research must determine what is realistically possible.

Possible product strategies if perfect classification is not feasible:
1. record motorized Trips and allow the user to classify/delete non-motorcycle Trips;
2. use optional contextual signals (e.g. paired intercom/device) only as additional evidence;
3. provide a quick "Not a motorcycle trip" correction;
4. allow Auto Tracking schedules/profiles if later justified.

No strategy is accepted yet.

---

## 12. Short trips

Short motorcycle movements can be legitimate.

Examples:
- moving between nearby buildings;
- fuel station run;
- repositioning the motorcycle;
- very short commute segment.

The system SHOULD NOT discard a Trip solely because it is shorter than an arbitrary distance or duration.

Research must distinguish:
- noise/candidate events that should never become Trips;
- legitimate short Trips;
- movements so trivial that the user would consider them clutter.

Any automatic cleanup rule must be reversible or configurable if it risks deleting legitimate data.

---

## 13. Location loss and degraded GPS

### Temporary loss
If GPS/location quality degrades while TRACKING:
- the Trip remains open;
- the gap is represented as missing/low-confidence data;
- the system attempts to recover normally;
- the event is logged for diagnostics.

### Implausible samples
The raw observation may be retained, but processed metrics MUST be able to reject or quarantine suspicious points.

### Long gaps
A long location gap alone MUST NOT automatically prove that a Trip ended. Other evidence and recovery behavior are required.

---

## 14. Process death, app closure and device restart

Business state must not live only in the UI process.

### Active Trip recovery
After ordinary UI closure or process recreation:
- TRACKING / MANUAL_PAUSED state must be recoverable to the extent Android allows;
- persisted timestamps and raw points already committed must survive;
- the user must not receive a duplicate Trip because the UI reopened.

### Device restart
Whether automatic continuation after full device reboot is feasible/reliable is research-gated. The product should preserve the incomplete Trip and make recovery explicit even if continuous recording across reboot cannot be guaranteed.

---

## 15. One active Trip invariant

The initial product supports at most one logical active Trip at a time.

A new automatic or manual start while another Trip is active MUST NOT silently create a second concurrent Trip.

Potential responses:
- return to active Trip;
- resume if manually paused;
- ask whether to finish the existing Trip before starting another.

Exact UX belongs to F0.9.

---

## 16. Trip-source metadata

Each Trip SHOULD retain how it began and ended.

Candidate conceptual fields:
- `startSource`: AUTO / MANUAL / RECOVERED;
- `endSource`: AUTO / MANUAL / RECOVERY_DECISION;
- detection confidence/diagnostic metadata where appropriate;
- detector/version identifier for field-test analysis.

This enables comparison of detector versions without rewriting history.

---

## 17. Detection events

Minimum diagnostic event vocabulary to consider:

```text
CANDIDATE_START_CREATED
CANDIDATE_START_CANCELLED
AUTO_TRIP_STARTED
MANUAL_TRIP_STARTED
TEMPORARY_STOP_ENTERED
TEMPORARY_STOP_EXITED
MANUAL_PAUSE_STARTED
MANUAL_PAUSE_MOVEMENT_WARNING
TRIP_RESUMED
GPS_DEGRADED
GPS_RECOVERED
CANDIDATE_STOP_CREATED
CANDIDATE_STOP_CANCELLED
AUTO_TRIP_FINISHED
MANUAL_TRIP_FINISHED
PROCESS_RECOVERED
TRIP_RECOVERY_REQUIRED
```

The exact event schema is deferred to F0.7/F0.13.

---

## 18. Required scenario matrix

The following scenarios must exist in field/synthetic validation before automatic detection is considered stable.

| ID | Scenario | Expected high-level behavior |
|---|---|---|
| SCN-001 | Walk around home with phone | No Trip |
| SCN-002 | Walk to parked motorcycle | No Trip before motorized departure |
| SCN-003 | Slow parking-lot departure | Trip eventually starts without requiring high initial speed |
| SCN-004 | Normal urban departure | Auto Trip starts |
| SCN-005 | Traffic light, short stop | Same Trip |
| SCN-006 | Heavy congestion with repeated 0–10 km/h motion | Same Trip |
| SCN-007 | Long red light / roadworks | Same Trip unless broader evidence indicates end |
| SCN-008 | Quick fuel stop | Prefer same Trip; exact stop handling to validate |
| SCN-009 | Viewpoint/photo stop | Prefer same Trip for reasonable interruption |
| SCN-010 | Restaurant with Manual Pause | Same Trip after Resume |
| SCN-011 | Forget to Resume after restaurant | Remind; do not silently resume |
| SCN-012 | Manual Finish while stationary | Trip ends immediately |
| SCN-013 | Manual Finish while still moving | End + prevent instant auto-restart |
| SCN-014 | Park motorcycle then walk away | Motorized Trip ends; walking does not extend route |
| SCN-015 | GPS jump while idle | No false Trip |
| SCN-016 | Temporary GPS loss during ride | Same Trip with gap/quality event |
| SCN-017 | Tunnel / poor sky view | Same Trip if other evidence supports continuation |
| SCN-018 | App UI closed during ride | Trip continues subject to platform capabilities |
| SCN-019 | Process killed/recreated | Recover active Trip without duplicate |
| SCN-020 | No Internet / airplane mode with GPS available | Core recording continues |
| SCN-021 | Very short real motorcycle ride | Do not automatically discard solely for being short |
| SCN-022 | Travel as passenger in car | Research classification/false-positive behavior |
| SCN-023 | Bus/taxi trip | Research classification/false-positive behavior |
| SCN-024 | Bicycle ride | Should not be treated as motorcycle if signals can distinguish reliably |
| SCN-025 | Phone moved rapidly by hand but no travel | No Trip |
| SCN-026 | Multiple stop/start blocks in city | One logical Trip when continuous travel context exists |
| SCN-027 | Active Trip then Auto Tracking disabled | Active Trip remains coherent; no silent data loss |
| SCN-028 | Manual Start with Auto Tracking disabled | Trip records normally |
| SCN-029 | Manually-started ride, rider forgets to press Finish and parks for a long time | Reminder eventually issued; distance/duration stay honest, no auto-finish |

---

## 19. Research hypotheses

These are hypotheses to test, not accepted architecture decisions.

### HYP-001
A low-power activity/motion transition signal can act as a passive trigger for candidate motorized travel more efficiently than permanent high-frequency GPS.

### HYP-002
Combining activity evidence with speed, displacement and consecutive valid locations will reduce false starts compared with any single threshold.

### HYP-003
Separate start/stop hysteresis and a stop grace period are required to prevent traffic lights and congestion from splitting Trips.

### HYP-004
Motorcycle-specific classification will be less reliable than general motorized/vehicle classification and will require field validation.

### HYP-005
High-detail GPS should be activated during validation/tracking rather than permanent idle monitoring for acceptable battery consumption.

### HYP-006
Raw location data plus a deterministic post-processing pipeline will yield more reliable distance/speed metrics than trusting every platform location value directly.

### HYP-007
Elevation requires dedicated smoothing/quality research before cumulative ascent/descent can be considered trustworthy.

---

## 20. Mandatory research questions

### RQ-DET — Detection signals
1. Which Android activity/motion signals are available on target Android versions?
2. What latency and reliability do they exhibit on a motorcycle?
3. How are motorcycles classified compared with cars, bicycles and walking?
4. Can transition APIs wake the application reliably enough to begin validation?

### RQ-START — Start confirmation
5. Which combination of movement, speed, displacement, time and accuracy best confirms a real Trip?
6. How much route beginning is lost under different confirmation windows?
7. How should slow parking/garage departures behave?

### RQ-STOP — End confirmation
8. What evidence distinguishes a traffic stop from a real destination arrival?
9. What grace/hysteresis strategy avoids fragmentation without keeping Trips open excessively?
10. How should brief fuel/viewpoint stops behave by default?

### RQ-PAUSE — Manual pause
11. What low-power signal can detect forgotten Resume without recording detailed paused-route geometry?
12. How frequently should reminders occur without becoming distracting?

### RQ-BAT — Battery
13. What is passive detection cost per day?
14. What is active tracking cost per hour under candidate sampling policies?
15. Which sampling changes meaningfully reduce battery use without materially damaging route quality?

### RQ-LOC — Location quality
16. Which location fields are reliable enough to persist/use?
17. How should accuracy influence acceptance/rejection of points?
18. How should speed be computed/filtered?
19. How should long gaps and tunnels be represented?

### RQ-ALT — Elevation
20. Is raw GNSS altitude reliable enough for min/max values?
21. What smoothing/source strategy is needed for cumulative ascent/descent?

### RQ-PLAT — Platform behavior
22. What permissions are required for automatic detection and active tracking by Android version?
23. What foreground-service/background-start constraints apply?
24. What behavior is possible after process death and full device reboot?
25. Which OEM battery optimizations materially affect reliability?

---

## 21. Evidence categories for later decisions

F0.3/F0.4/F0.5 results should classify detector decisions as:

- **Validated:** demonstrated by official platform documentation + repeatable field tests.
- **Supported hypothesis:** technically plausible and observed, but needs more rides/devices.
- **Heuristic:** practical rule selected despite incomplete evidence; must be versioned and tunable.
- **Rejected:** produced unacceptable false positives, false stops, battery cost or route loss.
- **Unknown:** insufficient evidence.

No threshold should become a hardcoded product truth without being assigned one of these categories.

---

## 22. Metrics for detector evaluation

Field research must produce measurable outcomes:

- start detection latency;
- distance/time lost before auto-start;
- stop detection latency;
- false-start rate;
- missed-Trip rate;
- false-stop / fragmented-Trip rate;
- accidental continuation after destination;
- manual-pause reminder effectiveness;
- battery use in passive mode;
- battery use per active tracking hour;
- GPS valid-point ratio;
- number of suspicious/rejected points.

Target values are not set yet.

---

## 23. F0.3 acceptance criteria

F0.3 can move from Draft to Accepted when:

- the canonical states and transitions are approved;
- manual pause/resume semantics are approved;
- start/stop conceptual behavior covers the scenario matrix;
- motorcycle-vs-other-vehicle ambiguity is explicitly acknowledged;
- no arbitrary speed/distance/time threshold is presented as fact;
- all implementation-sensitive decisions are converted into research questions;
- F0.4/F0.5 can use F0.3 as their behavioral contract.

---

## 24. Outputs to next workstreams

### F0.4 — Android Platform Research
Must answer permissions, activity recognition, background execution, foreground service, reboot/process behavior and version constraints.

### F0.5 — GPS & Location Research
Must answer sampling, accuracy, speed, filtering, altitude, distance calculation and power/quality tradeoffs.

### F0.6 — Field Experiment Design
Must transform SCN-001..SCN-028 into repeatable real motorcycle and synthetic tests with structured logging.

---

## 25. Current F0.3 decision

**Draft v0.1:** The detector is specified as a stateful, multi-signal, hysteresis-based system with explicit manual override. Exact APIs, thresholds and algorithms remain intentionally unresolved until Android/GPS research and field evidence are complete.
