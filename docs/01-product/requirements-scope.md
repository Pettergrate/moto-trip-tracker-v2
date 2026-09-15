# F0.2 — Requirements & Scope
## Moto Trip Tracker V2

**Status:** Draft baseline  
**Version:** 0.1  
**Project:** Moto Trip Tracker V2  
**Phase:** 0 — Discovery & Design  
**Depends on:** F0.1 Product Definition

---

## 1. Purpose

This document converts the product definition into explicit, numbered requirements.

It is not an implementation specification.

Its purpose is to establish:

- what the product must do;
- what the product should support later;
- which behaviors are considered essential;
- which constraints apply across the application;
- what is explicitly outside the initial scope;
- which requirements still require technical research before implementation.

All later architecture, research, roadmap and implementation tasks should be traceable back to one or more requirements in this document.

---

## 2. Requirement language

The following terms are used deliberately:

- **MUST** — required for the defined scope.
- **SHOULD** — strongly desired, but may move if technical evidence justifies it.
- **MAY** — optional or future capability.
- **MUST NOT** — explicitly prohibited behavior.

---

## 3. Priority levels

### P0 — Core / Blocking

Required for the first usable Moto Trip Tracker V2 product.

A P0 requirement may be deferred from the very first development milestone, but Phase 1 planning must include it.

### P1 — Important / Post-Core

Important product capability that should follow once the trip-recording foundation is stable.

### P2 — Future / Expansion

Potential expansion. Architecture should avoid unnecessarily blocking it, but no implementation commitment exists.

---

# 4. Functional Requirements

## 4.1 Trip Detection

### FR-DET-001 — Automatic trip candidate detection
**Priority:** P0  
The application MUST be capable of detecting conditions that indicate a possible motorized trip without requiring the user to manually start every trip.

**Acceptance intent:**
- Detection can occur while the main UI is not open, subject to Android platform restrictions.
- Detection behavior must be explainable through diagnostics.
- Exact signals and thresholds are deferred to F0.3–F0.5.

---

### FR-DET-002 — Automatic trip start
**Priority:** P0  
The application MUST be capable of automatically starting a Trip after sufficient evidence indicates that a valid trip has begun.

**Acceptance intent:**
- A single noisy GPS sample MUST NOT be sufficient by itself.
- The exact validation logic is not defined in F0.2.

---

### FR-DET-003 — Automatic trip stop
**Priority:** P0  
The application MUST be capable of automatically finalizing a Trip when sufficient evidence indicates that the ride has ended.

**Acceptance intent:**
- Traffic lights, congestion and short stops MUST NOT normally finalize the Trip.
- Automatic stop must use a grace/validation concept rather than a single zero-speed event.

---

### FR-DET-004 — Temporary stop tolerance
**Priority:** P0  
The application MUST tolerate temporary stops during an active Trip.

Examples include:
- traffic lights;
- traffic congestion;
- stop signs;
- short roadside interruptions.

---

### FR-DET-005 — Manual start
**Priority:** P0  
The user MUST be able to start a Trip manually.

---

### FR-DET-006 — Manual finish
**Priority:** P0  
The user MUST be able to finish an active Trip manually without waiting for automatic stop detection.

---

### FR-DET-007 — Manual pause
**Priority:** P0  
The user MUST be able to manually pause an active Trip.

A manually paused Trip remains logically open.

---

### FR-DET-008 — Manual resume
**Priority:** P0  
The user MUST be able to resume a manually paused Trip.

The resumed recording MUST remain part of the same logical Trip.

---

### FR-DET-009 — Manual pause ownership
**Priority:** P0  
A manual pause MUST NOT be automatically canceled without user intent.

The application MAY warn the user when sustained movement is detected while a Trip remains manually paused.

---

### FR-DET-010 — Trip state visibility
**Priority:** P0  
The user MUST be able to determine whether the application is currently:
- idle;
- validating a possible trip where appropriate;
- recording;
- manually paused;
- finalizing/processing.

The UI does not necessarily need to expose every internal state.

---

## 4.2 Location Recording

### FR-REC-001 — GPS track recording
**Priority:** P0  
During an active Trip, the application MUST record a geographic track sufficient to reconstruct the route.

---

### FR-REC-002 — Timestamped track points
**Priority:** P0  
Recorded location samples MUST preserve timing information.

---

### FR-REC-003 — Location quality metadata
**Priority:** P0  
Where available, track points SHOULD preserve relevant quality/context metadata such as:
- location accuracy;
- altitude;
- speed;
- bearing.

Final fields will be confirmed during platform research.

---

### FR-REC-004 — Offline trip recording
**Priority:** P0  
Loss of Internet connectivity MUST NOT stop the core Trip recording process.

---

### FR-REC-005 — Raw track preservation
**Priority:** P0  
The system SHOULD preserve original/raw location observations separately from processed route data whenever technically and storage-wise reasonable.

---

### FR-REC-006 — Processed track
**Priority:** P0  
The application MUST support deriving a processed Trip track used for visualization and statistics.

---

### FR-REC-007 — Invalid point handling
**Priority:** P0  
The processing pipeline MUST be able to identify or reject obviously invalid/suspicious GPS observations rather than blindly using every sample.

---

### FR-REC-008 — Recording continuity
**Priority:** P0  
Closing the visible application interface MUST NOT by itself end an active Trip.

Actual background guarantees depend on Android platform research.

---

## 4.3 Trip Metrics

### FR-MET-001 — Trip distance
**Priority:** P0  
The application MUST calculate total Trip distance from valid processed data.

---

### FR-MET-002 — Trip duration
**Priority:** P0  
The application MUST calculate total elapsed Trip duration.

---

### FR-MET-003 — Moving time
**Priority:** P0  
The application MUST calculate moving time when technically reliable.

---

### FR-MET-004 — Stopped time
**Priority:** P0  
The application MUST calculate stopped time when technically reliable.

---

### FR-MET-005 — Manual pause time
**Priority:** P0  
The application MUST track manual pause duration separately from ordinary stopped time.

---

### FR-MET-006 — Maximum speed
**Priority:** P0  
The application MUST calculate a filtered maximum speed.

Clearly invalid GPS spikes MUST NOT be presented as legitimate maximum speed.

---

### FR-MET-007 — Average speed
**Priority:** P0  
The application MUST calculate average Trip speed.

---

### FR-MET-008 — Average moving speed
**Priority:** P0  
The application SHOULD calculate average speed while moving.

---

### FR-MET-009 — Elevation range
**Priority:** P0  
When sufficiently reliable data exists, the application SHOULD calculate:
- minimum elevation;
- maximum elevation;
- starting elevation;
- ending elevation.

---

### FR-MET-010 — Elevation gain/loss
**Priority:** P0 research-gated  
The application SHOULD calculate cumulative ascent and descent only if F0 research establishes a sufficiently reliable method.

This requirement MUST NOT force implementation of knowingly misleading elevation metrics.

---

### FR-MET-011 — Stop summary
**Priority:** P1  
The application SHOULD identify meaningful Trip stops and their approximate duration.

---

### FR-MET-012 — Metric recalculation
**Priority:** P0  
Trip metrics MUST be recalculable after operations that modify the logical route, including merge, split or boundary correction.

---

## 4.4 Active Trip Controls & Notifications

### FR-NOT-001 — Active trip notification
**Priority:** P0  
An active Trip MUST expose an appropriate persistent notification when required by the Android implementation.

---

### FR-NOT-002 — Pause action
**Priority:** P0  
The active Trip notification SHOULD provide a direct Pause action when the Trip is recording.

---

### FR-NOT-003 — Resume action
**Priority:** P0  
The active Trip notification SHOULD provide a direct Resume action when the Trip is manually paused.

---

### FR-NOT-004 — Finish action
**Priority:** P0  
The active Trip notification SHOULD provide a direct Finish action.

---

### FR-NOT-005 — Trip completion summary
**Priority:** P0  
When a Trip is completed, the application SHOULD provide a concise completion notification or equivalent user-visible confirmation.

---

### FR-NOT-006 — Long pause reminder
**Priority:** P1  
The application MAY warn when a Trip has remained manually paused for an unusually long period.

---

### FR-NOT-007 — Recording problem warning
**Priority:** P1  
The application SHOULD be capable of warning the user about significant recording limitations such as missing permissions or prolonged location failure.

---

### FR-NOT-008 — Notification preferences
**Priority:** P1  
Non-essential notifications SHOULD be individually configurable.

Essential active-recording notifications remain subject to Android requirements.

---

## 4.5 Trip History & Detail

### FR-HIS-001 — Persistent trip history
**Priority:** P0  
Completed Trips MUST remain available in a persistent history.

---

### FR-HIS-002 — Trip detail
**Priority:** P0  
The user MUST be able to open a completed Trip and inspect its route and core statistics.

---

### FR-HIS-003 — Chronological browsing
**Priority:** P0  
The history MUST support chronological browsing.

---

### FR-HIS-004 — Rename trip
**Priority:** P0  
The user MUST be able to assign or edit a human-readable Trip name.

---

### FR-HIS-005 — Delete trip
**Priority:** P0  
The user MUST be able to delete a Trip.

The storage strategy SHOULD allow a recovery/undo window rather than immediate irreversible deletion where practical.

---

### FR-HIS-006 — Search
**Priority:** P1  
The history SHOULD support search.

---

### FR-HIS-007 — Filtering
**Priority:** P1  
The history SHOULD support filters such as:
- date;
- favorite;
- distance;
- duration;
- motorcycle;
- route;
- tags.

---

### FR-HIS-008 — Sorting
**Priority:** P1  
The history SHOULD support additional sorting beyond default chronology where useful.

---

## 4.6 Favorites

### FR-FAV-001 — Favorite trip
**Priority:** P0  
The user MUST be able to mark/unmark a Trip as favorite.

---

### FR-FAV-002 — Favorites area
**Priority:** P0  
The application MUST provide a dedicated way to browse favorite Trips.

---

### FR-FAV-003 — Favorite route
**Priority:** P1  
When reusable Routes exist, the user SHOULD be able to mark a Route as favorite independently of any individual Trip.

---

## 4.7 Trip Editing

### FR-EDT-001 — Merge trips
**Priority:** P0  
The user MUST be able to merge two or more compatible Trips into one logical Trip.

---

### FR-EDT-002 — Merge recalculation
**Priority:** P0  
After a merge, all derived metrics MUST be recalculated using the resulting logical Trip.

---

### FR-EDT-003 — Split trip
**Priority:** P0  
The user MUST be able to divide a Trip into separate Trips at a selected logical point/time.

---

### FR-EDT-004 — Split recalculation
**Priority:** P0  
After a split, both resulting Trips MUST have independently recalculated metrics.

---

### FR-EDT-005 — Boundary correction
**Priority:** P1  
The user SHOULD be able to correct an inaccurate Trip start or end boundary.

---

### FR-EDT-006 — Reversible destructive operations
**Priority:** P1  
Merge, split and deletion SHOULD preserve enough provenance or temporary history to support undo/recovery where practical.

---

## 4.8 Maps

### FR-MAP-001 — Trip route map
**Priority:** P0  
A completed Trip MUST be viewable on a map.

---

### FR-MAP-002 — Start/end markers
**Priority:** P0  
The Trip map SHOULD identify the start and end of the route.

---

### FR-MAP-003 — Map usability with large tracks
**Priority:** P0  
The map implementation MUST remain usable for long Trips and large numbers of TrackPoints.

---

### FR-MAP-004 — Stop markers
**Priority:** P1  
Meaningful stops SHOULD be optionally represented on the Trip map.

---

### FR-MAP-005 — Metric overlays
**Priority:** P2  
Future map layers MAY visualize information such as:
- speed;
- elevation;
- recording quality.

---

### FR-MAP-006 — Offline recording independence
**Priority:** P0  
Failure to load online map content MUST NOT invalidate or stop Trip recording.

---

## 4.9 Routes

### FR-RTE-001 — Reusable Route entity
**Priority:** P1  
The system SHOULD support a Route as a concept distinct from a Trip.

---

### FR-RTE-002 — Assign trip to route
**Priority:** P1  
A Trip SHOULD be assignable to a reusable Route.

---

### FR-RTE-003 — Route trip history
**Priority:** P1  
A Route SHOULD expose the Trips associated with it.

---

### FR-RTE-004 — Similar-route detection
**Priority:** P2  
The application MAY detect that a new Trip resembles an existing Route.

---

### FR-RTE-005 — Route comparison
**Priority:** P2  
The application MAY compare multiple Trips belonging to the same Route.

---

## 4.10 Analytics

### FR-ANA-001 — Trip-level analytics
**Priority:** P0  
Core metrics MUST be visible for each Trip.

---

### FR-ANA-002 — Aggregate distance
**Priority:** P1  
The application SHOULD support aggregate distance by relevant periods such as week, month and year.

---

### FR-ANA-003 — Aggregate riding time
**Priority:** P1  
The application SHOULD support aggregate riding time.

---

### FR-ANA-004 — Historical totals
**Priority:** P1  
The application SHOULD support lifetime statistics.

---

### FR-ANA-005 — Records
**Priority:** P1  
The application MAY surface personal records such as:
- longest Trip;
- highest elevation;
- most repeated Route.

Speed-based statistics MUST remain informational and MUST NOT be designed as competitive leaderboards.

---

## 4.11 Markers, Notes, Tags & Media

### FR-CTX-001 — Trip notes
**Priority:** P1  
The user SHOULD be able to attach notes to a Trip.

---

### FR-CTX-002 — Tags
**Priority:** P1  
The user SHOULD be able to categorize Trips using tags.

---

### FR-CTX-003 — Manual markers
**Priority:** P1  
The user SHOULD be able to associate notable points with a Trip.

Examples:
- viewpoint;
- restaurant;
- fuel stop;
- rough road;
- off-road segment.

---

### FR-CTX-004 — Photos
**Priority:** P2  
The application MAY associate photos with a Trip and optionally with a location on that Trip.

---

## 4.12 Motorcycle Profiles

### FR-MOTO-001 — Motorcycle entity
**Priority:** P1  
The data model SHOULD be capable of associating a Trip with a Motorcycle.

---

### FR-MOTO-002 — Multiple motorcycles
**Priority:** P1  
The application SHOULD eventually support more than one Motorcycle.

---

### FR-MOTO-003 — Motorcycle distance
**Priority:** P1  
The application SHOULD be able to aggregate tracked distance per Motorcycle.

---

### FR-MOTO-004 — Maintenance
**Priority:** P2  
Future versions MAY track maintenance items by date and/or distance.

---

### FR-MOTO-005 — Fuel
**Priority:** P2  
Future versions MAY track fuel purchases, consumption and cost.

---

## 4.13 Export, Import & Sharing

### FR-EXP-001 — GPX export
**Priority:** P1  
The application SHOULD support export of compatible Trip route data in GPX or another suitable open interchange format.

---

### FR-EXP-002 — Data backup/export
**Priority:** P1  
The application SHOULD support a backup/export strategy for user-owned Trip data.

---

### FR-EXP-003 — Restore/import
**Priority:** P1  
The system SHOULD support restoration of its own backup format.

---

### FR-EXP-004 — GPX import
**Priority:** P2  
The application MAY import external GPX routes/tracks.

---

### FR-EXP-005 — Shareable trip summary
**Priority:** P1  
The application SHOULD eventually generate a visual Trip summary suitable for sharing.

---

### FR-EXP-006 — Privacy zones
**Priority:** P1  
When sharing routes, the application SHOULD support hiding sensitive start/end areas such as home or work.

---

## 4.14 Diagnostics & Observability

### FR-DIA-001 — Trip lifecycle events
**Priority:** P0  
The application MUST preserve enough Trip lifecycle information to diagnose start/stop/pause/recovery behavior.

---

### FR-DIA-002 — Detector diagnostics
**Priority:** P0  
Development builds MUST provide visibility into the signals and state used by the automatic Trip detector.

---

### FR-DIA-003 — Location diagnostics
**Priority:** P0  
Development builds SHOULD expose relevant location information such as:
- latest accuracy;
- current/derived speed;
- time since last valid fix;
- current Trip state.

---

### FR-DIA-004 — Failure reason visibility
**Priority:** P0  
When a Trip fails to start, continue or recover as expected, the development tooling SHOULD provide enough evidence to investigate why.

---

# 5. Non-Functional Requirements

## 5.1 Reliability

### NFR-REL-001 — Persistent active trip state
**Priority:** P0  
Critical active Trip state MUST be persisted outside transient UI memory.

---

### NFR-REL-002 — Crash/process interruption resilience
**Priority:** P0  
The architecture MUST minimize Trip data loss when the process or UI is interrupted.

Exact recovery guarantees will be defined in F0.10.

---

### NFR-REL-003 — Atomic/consistent persistence
**Priority:** P0  
Trip persistence MUST avoid leaving data in silently inconsistent states after common interruptions.

---

### NFR-REL-004 — Reprocessing capability
**Priority:** P0  
Derived Trip metrics SHOULD be reproducible from persisted source data where feasible.

---

## 5.2 Battery

### NFR-BAT-001 — Passive efficiency
**Priority:** P0  
Idle/automatic-detection mode MUST be designed to use materially less power than active high-detail Trip recording.

---

### NFR-BAT-002 — Active tracking efficiency
**Priority:** P0  
Active recording MUST balance route quality with battery use.

Exact targets are research-gated.

---

### NFR-BAT-003 — No permanent high-frequency GPS while idle
**Priority:** P0  
The design MUST NOT assume continuous high-frequency precise GPS sampling while the user is idle solely to detect trip starts, unless research proves no viable alternative.

---

## 5.3 Performance & Scale

### NFR-PERF-001 — Large history
**Priority:** P0  
The architecture MUST support years of Trip history without requiring all TrackPoints to be loaded into memory simultaneously.

---

### NFR-PERF-002 — Long trips
**Priority:** P0  
Long-distance Trips with large numbers of TrackPoints MUST remain reviewable.

---

### NFR-PERF-003 — Background processing
**Priority:** P0  
Expensive post-processing SHOULD not block the main UI thread.

---

## 5.4 Privacy

### NFR-PRV-001 — Local-first ownership
**Priority:** P0  
Core recording MUST NOT require uploading location history to a remote service.

---

### NFR-PRV-002 — Least required permissions
**Priority:** P0  
The application MUST request only permissions justified by defined functionality.

---

### NFR-PRV-003 — Explain sensitive permissions
**Priority:** P0  
Sensitive permissions MUST be explained in user-facing context before or when requested, according to platform requirements.

---

### NFR-PRV-004 — No hidden public sharing
**Priority:** P0  
Trip location data MUST remain private unless the user explicitly initiates sharing/export.

---

## 5.5 Safety & Interaction

### NFR-SAFE-001 — Low interaction while moving
**Priority:** P0  
The product MUST minimize required interaction while the rider is moving.

---

### NFR-SAFE-002 — Essential active controls
**Priority:** P0  
Active Trip controls SHOULD prioritize only essential actions such as Pause/Resume/Finish.

---

### NFR-SAFE-003 — No competitive speed design
**Priority:** P0  
The application MUST NOT introduce public speed leaderboards or mechanics designed to encourage unsafe speed competition.

---

## 5.6 Maintainability

### NFR-MNT-001 — Modular responsibilities
**Priority:** P0  
Detection, recording, persistence, processing and UI responsibilities SHOULD remain separable enough to test and evolve independently.

---

### NFR-MNT-002 — Documented architectural decisions
**Priority:** P0  
Material architectural decisions MUST be documented through ADRs or equivalent decision records.

---

### NFR-MNT-003 — Traceability
**Priority:** P0  
Implementation work SHOULD reference relevant requirement IDs.

---

### NFR-MNT-004 — Controlled agent tasks
**Priority:** P0  
Codex and Claude Code implementation prompts SHOULD use bounded tasks with acceptance criteria rather than broad project-wide instructions.

---

## 5.7 Testability

### NFR-TST-001 — Deterministic business logic where possible
**Priority:** P0  
Trip-detection and processing logic SHOULD be isolated from platform-specific code enough to support deterministic automated testing where feasible.

---

### NFR-TST-002 — Field-test support
**Priority:** P0  
The development build MUST support collection of the diagnostic information needed for real motorcycle field testing.

---

### NFR-TST-003 — Synthetic scenarios
**Priority:** P0  
Core Trip state transitions SHOULD be testable using synthetic/replayed sequences without requiring a real ride for every test.

---

## 5.8 Compatibility

### NFR-CMP-001 — Android version policy
**Priority:** P0 research-gated  
Minimum and target Android versions MUST be explicitly decided before Phase 1 implementation.

---

### NFR-CMP-002 — Platform restriction compliance
**Priority:** P0  
The architecture MUST comply with Android background execution, location and foreground-service restrictions applicable to supported versions.

---

# 6. Core Scope Baseline

The following capabilities currently define the intended **Core V2 baseline**:

1. Automatic Trip candidate/start/stop behavior.
2. Manual start.
3. Manual pause/resume.
4. Manual finish.
5. Offline-capable GPS recording.
6. Raw/processed track concept.
7. Distance and time metrics.
8. Filtered speed metrics.
9. Elevation metrics where reliable.
10. Active Trip state persistence.
11. Persistent active-recording notification/control.
12. Trip map.
13. Trip history.
14. Trip detail.
15. Favorite Trip.
16. Delete Trip.
17. Merge Trips.
18. Split Trip.
19. Basic recovery after interruption.
20. Diagnostic/event logging sufficient to investigate detection and recording failures.

This is the current product baseline, not the first coding milestone.

The roadmap may deliver these capabilities incrementally.

---

# 7. Post-Core Scope

The following are expected after the recording foundation is stable:

- reusable Routes;
- Route favorites;
- search/filtering;
- notes and tags;
- markers;
- calendar view;
- advanced charts;
- aggregate analytics;
- multiple Motorcycle profiles;
- GPX export;
- backup/restore;
- shareable Trip cards;
- privacy zones;
- widgets / Quick Settings;
- route similarity detection where feasible.

---

# 8. Future Scope

Potential expansions include:

- maintenance tracking;
- fuel/economy tracking;
- weather enrichment;
- road/off-road classification;
- cloud sync;
- GPX import;
- advanced Route comparison;
- photo/location integration;
- additional external integrations.

---

# 9. Explicit Out of Scope for Initial Core

The following MUST NOT be treated as Core V2 requirements:

- turn-by-turn navigation;
- complex route planner;
- public social network;
- chat;
- public live tracking;
- public speed rankings;
- gamified speed competition;
- marketplace;
- full vehicle-maintenance platform;
- mandatory cloud account;
- mandatory Internet connection for Trip recording.

---

# 10. Research-Gated Requirements

Some requirements are intentionally not fully specified yet.

The following require evidence before architecture/implementation is finalized:

| Area | Open decision |
|---|---|
| Automatic detection | Which Android signals can reliably indicate motorized/motorcycle travel? |
| Start logic | Thresholds, confidence, timing and hysteresis |
| Stop logic | Grace period and evidence needed to finish a Trip |
| Background operation | Exact Android restrictions by supported OS version |
| Location | Sampling strategy and accuracy/power tradeoff |
| Speed | Whether platform-reported speed or derived speed is preferred in each case |
| Elevation | Reliability, smoothing and ascent/descent method |
| Maps | Provider, offline behavior, licensing and cost |
| Recovery | Realistic guarantees after process death/reboot |
| Storage | Database structure and raw TrackPoint retention policy |
| Battery | Measurable passive/active consumption targets |
| Permissions | Final permission flow for supported Android versions |

These questions are inputs to F0.3–F0.11.

---

# 11. Requirement Traceability Convention

Future work should reference requirement IDs.

Example:

```text
Task: DET-004 Candidate stop detector

Implements:
- FR-DET-003
- FR-DET-004
- NFR-BAT-001
- NFR-TST-001
```

Architecture decisions should also identify affected requirements.

Example:

```text
ADR-005
Decision: Preserve raw TrackPoints separately.

Supports:
- FR-REC-005
- NFR-REL-004
```

---

# 12. Definition of F0.2 Complete

F0.2 can move from Draft to Accepted when:

- Core, Post-Core and Future boundaries are agreed;
- all known major product behaviors have requirement IDs;
- contradictions with F0.1 are resolved;
- research-gated requirements are clearly marked;
- no implementation technology has been chosen prematurely;
- the document is suitable as input for Trip Detection and Android research.

---

# 13. Next Phase 0 Document

**F0.3 — Trip Detection Specification & Research Questions**

F0.3 should define:

- Trip state machine;
- candidate-start behavior;
- candidate-stop behavior;
- traffic-light and congestion behavior;
- manual pause/resume behavior;
- forgotten-pause behavior;
- short Trip handling;
- walking vs vehicle ambiguity;
- car vs motorcycle ambiguity;
- GPS loss;
- sensor disagreement;
- recovery scenarios;
- initial hypotheses to validate in field tests.

Exact algorithms and thresholds should remain hypotheses until field evidence exists.
