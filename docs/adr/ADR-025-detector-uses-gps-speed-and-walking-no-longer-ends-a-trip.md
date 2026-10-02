# ADR-025 — The detector corroborates with GPS speed; Activity Recognition's WALKING no longer ends a Trip by itself

**Status:** Accepted
**Date:** 2026-10-01
**Project:** Moto Trip Tracker V2
**Phase:** Phase 1 — `DET-008` (owner-reviewed: "intentemoslo")

---

## Context

`DET-003` (`CandidateStopEngine`) treated an Activity Recognition `WALKING`/`ON_FOOT` ENTER, arriving while a stop candidate was open (right after an `IN_VEHICLE` EXIT), as "the single strongest, still zero-threshold signal" and confirmed the end of the Trip on the spot. The 3-minute grace period only applied when Activity Recognition said nothing at all.

A real day of riding on the owner's phone (2026-10-01, Honor DNY-NX9, read back from the app's own `diagnostic_event` and `trip_capture` tables) showed what that does in practice:

- **13 of 13** automatic Finishes happened at the very second Activity Recognition reported `WALKING` — at stops, in slow traffic, on slow turns, not at the end of rides. A ride that should have been one Trip became 13 captures of 20 s to 4.6 min, all `startSource=AUTO, endSource=AUTO, COMPLETED`. Android's classifier evidently reads "motorcycle idling at a light, foot down" as walking.
- In the same day Android reported ~64 min `IN_VEHICLE`; ~23 min were recorded. Four `IN_VEHICLE` windows (18 m 44 s, 8 m 52 s, 5 m 22 s, 3 m 27 s) never produced a recording at all, and nothing recorded why: an abandoned start candidate left no trace. In all four, Activity Recognition kept `IN_VEHICLE` the whole time — so a flip out of `IN_VEHICLE` (which abandons a candidate) is *not* the cause. What remains is the 120-second candidate window expiring (`maxCandidateWindowMs`) or no usable fixes arriving, and the data cannot tell which. Activity Recognition emits no second `IN_VEHICLE` ENTER while the state persists, so one expired window loses the rest of the ride.
- `DET-006`'s 2-minute post-Finish suppression let two automatic starts through 67 s and 79 s after a Finish. Cause found in code: `TripCaptureDao.findMostRecentlyEnded` ordered by `endElapsedRealtimeNanos`, a counter that restarts at every boot, so a capture sealed after an earlier reboot could carry a larger value than "now", sort first, and trip the caller's own reboot guard ("now earlier than its end → not suppressed"). A regression test reproduces it; whether *that* row was the one returned on the day could not be checked (the phone was offline when this was written).

This contradicts the project's own spec: `trip-detection-spec.md` DP-001 ("a single … activity label MUST NOT be enough by itself to start or stop a Trip"), §7 requirement 2 ("a short stop MUST remain part of the active Trip"), requirement 4 ("resumed movement during candidate-stop MUST return to TRACKING without creating a split") and requirement 5 (walking away "SHOULD contribute to confirming", not decide).

## Decision

1. **`WALKING`/`ON_FOOT` no longer ends a Trip.** While a stop candidate is open it is only recorded as evidence (`CandidateEvidence.walkingSeen`). The Trip ends when the grace period (`CandidateStopProfile.minConfirmationDurationMs`, 180 s, unchanged) passes with the vehicle not moving again.
2. **Movement cancels a stop candidate.** `FixSpeed.effectiveSpeedMps` (the fix's reported speed when it is usable; otherwise the speed between two consecutive fixes, only if the phone moved by more than the two fixes' own accuracy) at `CandidateStopProfile.resumeSpeedMps` (3.0 m/s) or faster on `resumeFixesRequired` (2) consecutive fixes returns the engine to tracking, in addition to an `IN_VEHICLE` ENTER. One spike never cancels (DP-001).
3. **Start confirms on speed as well as displacement.** After `minConfirmationDurationMs` (15 s), either 40 m from the anchor *or* `vehicleSpeedFixesRequired` (3) consecutive fixes at `vehicleSpeedMps` (2.5 m/s, above brisk walking and below the ~10 km/h slow departure of F0.3 §6 req. 3) confirm. `maxCandidateWindowMs` grows from 2 to 5 minutes.
4. **An automatic Finish ends the Trip where the vehicle stopped.** `finishCapture(..., tripEndsAtElapsedRealtimeNanos)` ends the `TripPart` at the last raw point recorded at or before the moment the stop candidate opened (`RawTrackPointDao.findLastAtOrBefore`): `endSequenceNumber` and `endElapsedRealtimeNanos` are that point's, and the Trip's `createdAt` is its `capturedAt` — so the route, the duration and the Trip's own date all agree, as in `EDT-003`'s boundary correction. Waiting out the grace period and walking away do not extend the Trip (§7 req. 5). The raw points after it stay stored (ADR-006). With no point at or before the bound nothing is trimmed. A manual Finish never trims.
5. **Every candidate decision leaves its evidence** (DP-007): `CANDIDATE_START_CONFIRMED`/`CANDIDATE_START_REJECTED` and `CANDIDATE_STOP_ENTERED`/`CANDIDATE_STOP_CANCELLED`/`CANDIDATE_STOP_CONFIRMED` `DETECTOR` diagnostic events (named after `observability-diagnostics.md` §5.1's vocabulary - those codes are a versioned contract), whose metadata is only counts, speeds and one rounded straight-line distance (`elapsedMs`, `fixCount`, `firstFixDelayMs`, `maxSpeedMps`, `displacementM`, `walkingSeen`) — never a coordinate (ADR-009).
6. **`findMostRecentlyEnded` orders by `endedAt`** (wall clock, comparable across reboots); the caller still does its "how much time has passed" math on the returned row's elapsed value, with its existing reboot guard.
7. **No post-Finish suppression after an automatic Finish.** `DET-006`'s 2-minute window now applies only when the last capture ended `MANUAL`. An automatic Finish happens only after 180 s of no movement, so a new `IN_VEHICLE` ENTER shortly after it is either the same ride resuming or the next one — and the previous behaviour (suppress it) is exactly what lost rides after a long red light.
8. **The trim is conservative.** It applies only when the fixes seen since the candidate opened show the vehicle stayed stopped: `maxSpeedMps` present and below `resumeSpeedMps`. With no usable speed (poor accuracy, no fix) or one vehicle-like spike (not the two in a row that would have cancelled the stop), the points after the stop might be real riding, and trimming would orphan them — the Trip then simply keeps its tail, as before this ADR.
9. **A manual pause discards an open stop candidate.** While a pause is open the stop engine never sees events (DP-005) and a candidate that was open when the pause began is replaced by a fresh engine. The engine counts grace time in absolute timestamps; left open across a pause it would confirm on the first fix after Resume and — with decision 4 — cut off the riding that follows. Cost: if the user pauses during a stop candidate, the grace period restarts after Resume.

All thresholds remain **field-gated placeholders** (ADR-018, `EXP-008`/G4): the owner chose to try them now, and the evidence in point 5 is what the next field session tunes them with.

## Rationale

- The evidence in Context is the project's own first real field data for the stop path, and it points at one rule, not at tuning: with a zero-threshold shortcut, no grace period can protect a stop. Making the activity label a *witness* instead of a *verdict* is what DP-001 already required.
- GPS speed is the signal a fix reports most reliably, and it separates the two cases the activity label confuses: standing at a light (speed 0, then ≥ walking pace within seconds of green) versus the ride having ended (speed stays at 0 or walking pace).
- Trimming at the stop instead of at "now" keeps the long, conservative grace period free: without it every Trip would gain minutes of standing/walking tail and a walking route stub.
- Logging the evidence is cheap and is the only way the four unexplained lost rides — and any future ones — become tunable instead of anecdotal.

## Consequences

- An automatic Trip is **finalised at least 180 s after the vehicle stops** (previously immediately on `WALKING`). The foreground service, its notification and high-detail GPS stay on for those minutes. The Trip's own end and duration are not affected (point 4).
- A rider crawling in dense traffic with no fix at 3 m/s or faster for three minutes straight would still be ended early; a long stop with the phone in a pocket and no GPS (tunnel) is ended by the grace period as before.
- The trim bound is the Activity Recognition `IN_VEHICLE` EXIT timestamp, which may lag the true stop by some seconds: those seconds of standing still stay in the Trip. And because of decision 8, an automatic Trip whose last minutes had no usable speed keeps its standing/walking tail (the pre-ADR behaviour) rather than risk cutting real riding.
- `CANDIDATE_STOP_CONFIRMED` is logged after the Finish commits, so the event never claims an end that did not happen; the diagnostic snapshot shows the last 10 detector events (was 5), since one ride now produces several.
- Samples seen while a start candidate was open are still not persisted once it confirms (the documented v1 gap in `runAutoDetection`); the longer window only widens how late a confirmation can come, and speed-based confirmation narrows it for most rides.
- `DET-006`'s suppression window, now limited to manual Finishes (decision 7), is enforced correctly across reboot-affected history; the `DiagnosticExporter` also picks its capture by wall clock.
- Up to a few dozen small diagnostic events per ride (one per stop candidate); bounded by `DIA-004`'s retention (14 days, 20,000 rows; the new types are not exempt from the purge). A rejected start candidate is logged while idle with no capture id, for any `IN_VEHICLE` candidate (a car or a bus too) - the same shape and retention as the existing `AUTO_DETECTION_*` rows.
- The tail past the trimmed `TripPart` is still raw data of its capture: the same deletion and purge paths remove it with the capture, and the opt-in route attachment of the diagnostic export (a diagnostic of the *capture*, which is where a stop-detection problem shows) still carries it, under the same explicit warning as before.
- New API: `CandidateStartDecision.Abandoned`/`CandidateStopDecision.Abandoned` carry a reason and evidence (previously a bare object); `CandidateStopEngine.REASON_WALKING_AWAY` is removed; `runAutoDetection` accepts the two profiles (tests only).

## Alternatives considered

- **Keep the instant `WALKING` finish but veto it when GPS speed says "moving".** Rejected: at a light the speed is 0 and the label says WALKING — exactly the failing case; speed cannot distinguish a stop from an end, only time can.
- **Shorten the grace period when `WALKING` is seen** (e.g. to 60–90 s). Rejected for now: a 2-minute red light in San José with `WALKING` flapping would still cut the ride; with the tail trim the cost of a long grace period is only a late finalisation. Revisit with field evidence (reopen trigger).
- **Merge the fragments automatically afterwards.** Rejected by the owner: fixing the cause is preferable to gluing the symptom, and an auto-merge needs an undo that does not exist (`EDT-001` built the data for one, no UI).
- **Back-date the whole capture's `endedAt`.** Rejected: the capture's own end stays "when recording actually stopped" (REC invariants, diagnostics); only the `TripPart` range moves, as `EDT-003` already does.
- **Rely on the `IN_VEHICLE` ENTER alone to resume.** Rejected: Activity Recognition did not reliably emit it at lights in the field data.

## Reopen / supersede triggers

- Field data (`EXP-004`/`005`/`007`, or any ride with the new events) showing the grace period ends real rides early (look for `CANDIDATE_STOP_CONFIRMED` followed within minutes by a new start nearby) or finalises too late to be tolerable.
- Evidence that a walking-away signal combined with displacement can safely shorten the grace period (§7 req. 5).
- `CANDIDATE_START_REJECTED` events showing the lost rides were *waits* (fixes arriving, speed 0) rather than missing fixes — then a low-power movement watcher after expiry is the next step; if they show `fixCount = 0`, the cause is location delivery, not the detector.
- Rides whose automatic Trips keep a long standing/walking tail (decision 8 declining to trim) — then a speed estimate that tolerates poor accuracy, or trimming on displacement, is the next step.
- `EXP-008` freezing different production values.

## Traceability

- `trip-detection-spec.md` DP-001/002/003/004/007, §5 CANDIDATE_STOP, §6 req. 3, §7 req. 1/2/4/5, §9 (post-finish suppression)
- `ADR-006` (raw preserved), `ADR-009` (no coordinates in diagnostics), `ADR-015`/`ADR-020` (finish idempotent and transactional, single active capture), `ADR-018` (thresholds field-gated)
- Backlog `DET-008`; corrects `DET-006`'s ordering note; `DET-002`/`DET-003`/`AUTO-001` behaviour changed

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
