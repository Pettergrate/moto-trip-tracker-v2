# ADR-026 — `ON_BICYCLE` is treated as a vehicle label: it can start a candidate, and a change between the two labels is not an end

**Status:** Accepted
**Date:** 2026-10-01
**Project:** Moto Trip Tracker V2
**Phase:** Phase 1 — `DET-009` (owner-reviewed: chose this option over a location-based trigger "por la batería")

---

## Context

`DET-008` (`ADR-025`) fixed how a Trip *ends*. The first rides after it was installed (2026-10-01, Honor DNY-NX9, read back from the app's own `diagnostic_event` table) failed on the other side: two real motorcycle rides of about 1 km (about 2.7 minutes each) recorded nothing.

- **18:39** — Activity Recognition said `IN_VEHICLE` for 4 s, then `ON_BICYCLE` for the remaining 2 min 39 s. The detector had opened a start candidate on the `IN_VEHICLE` ENTER and abandoned it on the `IN_VEHICLE` EXIT 3.5 s later (`CANDIDATE_START_REJECTED`, reason `IN_VEHICLE_EXIT`, already 5.3 m/s on the fixes it had).
- **21:02** — `ON_BICYCLE` for 70 s, then `IN_VEHICLE` for 90 s, then `WALKING`. Nothing opened during the `ON_BICYCLE` minute; the `IN_VEHICLE` candidate saw 83 s of arrival (max 1.5 m/s, 23 m) and was abandoned by the next label. The riding had already happened under a label the detector does not listen to. (Inferred: a candidate's own fixes are not stored, so the speed of those minutes cannot be read back.)

Both rides were seen by Android; the detector's only trigger is `IN_VEHICLE` ENTER (`ADR-007`), and the label arrives late, briefly, or not at all. The field-experiment plan had already asked how often a motorcycle is classified `IN_VEHICLE`, `ON_BICYCLE` or something else (`field-experiment-design.md`); this is the first answer: on short rides, often the bicycle.

A second fact from the same data decides the design: **a change of label always arrives as an EXIT of the old label and an ENTER of the new one with the very same timestamp, EXIT first** — every pair, to the nanosecond, delivered in the same broadcast and recorded 10–20 ms apart.

## Decision

1. **`ON_BICYCLE` is a *vehicle-like* label** (`isVehicleLike()`: `IN_VEHICLE` or `ON_BICYCLE`). Its ENTER starts automatic detection exactly like `IN_VEHICLE`'s: the receiver starts the service, and `CandidateStartEngine` opens a candidate. Nothing else about confirming changes — the candidate still needs 15 s and 40 m of displacement or three fast fixes (`ADR-025`), so a bicycle label on someone standing still confirms nothing.
2. **A candidate follows which vehicle-like label is current.** `CandidateStartEngine` abandons on the EXIT of the *current* label (reason `IN_VEHICLE_EXIT` or `ON_BICYCLE_EXIT`); an ENTER of the other label while a candidate is open makes it the current one, and an EXIT of a label that is no longer current is ignored.
3. **The same for the stop side.** `CandidateStopEngine` opens a stop candidate on the EXIT of the current vehicle-like label (reason says which: `CandidateOpened(reasonCode)`, logged as `CANDIDATE_STOP_ENTERED`) and cancels it on an ENTER of either label (`IN_VEHICLE_ENTER` / `ON_BICYCLE_ENTER`). It starts with no label known (it is created at the moment a ride is confirmed), when any EXIT counts.
4. **The receiver hands a label change over ENTER first.** `ActivityTransitionReceiver` puts the ENTER before the EXIT between transitions of the *same instant* (everything else stays as delivered) when publishing to the bus. The diagnostic record keeps the order they arrived in. With that order decision 2/3 need no timers: the new label is current before the old one's EXIT arrives.
5. **The decision is explained.** `AUTO_DETECTION_STARTED` carries reason `ON_BICYCLE_ENTER` (next to the existing `IN_VEHICLE_ENTER`); abandonment and stop reasons carry the label. The existing codes are unchanged (they are a versioned contract, `observability-diagnostics.md` §7.1); the new ones sit beside them.

## Rationale

- The data says the label is the problem, not the thresholds: both rides had the movement the detector looks for and neither ever got a candidate (or kept one). Listening to the label Android actually uses for the owner's motorcycle is the smallest change that reaches them.
- Choosing a start trigger by label keeps `ADR-007`'s posture — Activity Recognition is a *passive, low-power* trigger, the GPS only runs once a candidate is open — which is why the owner preferred it over a location-based trigger (continuous GPS or geofence-style watching costs battery and is its own architectural decision).
- Ordering ENTER-first at the receiver, instead of a timer inside the engines, relies on something observed rather than assumed (identical timestamps, same broadcast), keeps the engines pure and their existing immediate-abandon behaviour intact, and leaves nothing waiting on a later event to decide.

## Consequences

- **A real bicycle ride is now a candidate too**, and records if it moves at 2.5 m/s or faster (three fixes in a row) or 40 m from where the candidate opened. This is the price of the owner's choice; the app is for motorcycles and Android cannot tell them apart. A cyclist would see Trips they did not want (delete or merge them). If that proves common, a higher speed bar for a candidate that has only ever been `ON_BICYCLE` is the next step (reopen trigger).
- More candidates open: each `ON_BICYCLE` ENTER turns the foreground service and high-detail GPS on for up to the 5-minute window (or until its EXIT). Every rejection logs its evidence (`CANDIDATE_START_REJECTED`), so the real cost is measurable from the field data.
- A label change leaves a few extra diagnostic rows: the receiver decides again on every vehicle-like ENTER, so `AUTO_DETECTION_STARTED` repeats while a candidate is open (the service ignores the duplicate) and `AUTO_DETECTION_NOT_STARTED` / `CAPTURE_ALREADY_ACTIVE` appears once recording. On the stop side a label change leaves nothing, when the ENTER-first order holds.
- **If an EXIT and its ENTER ever arrive in different broadcasts** (never seen in the field data), the EXIT-first order abandons the candidate and the later ENTER starts a new one: a few seconds lost, not a ride. A test pins this behaviour so the receiver's ordering stays the thing that prevents it.
- The engines' public decisions changed shape in one place: `CandidateStopDecision.CandidateOpened` is now a data class carrying the reason (it was an object).
- Rides that start with `STILL`/`WALKING`/`RUNNING`/`UNKNOWN` labels only are still invisible to the detector; so is a ride whose label never changes from `STILL`.

## Alternatives considered

- **A location-based trigger (movement watcher independent of Activity Recognition).** Rejected by the owner for battery; it remains the answer if rides turn out to be mislabelled in ways no label list can cover. Reopen trigger below.
- **A tolerance timer in the start engine (a pending EXIT that only abandons if no ENTER follows within a few seconds).** Rejected: it needs a later event (a fix or a tick) to decide, ripples into every caller and test that expects the immediate abandon, and solves a case the data does not show (a split pair) at the cost of the one it does.
- **Treat every label except `STILL` as a possible start.** Rejected: walking and running are the dominant false starts (a rider walking to the bike), and nothing in the field data needs them.
- **Make a candidate that is only `ON_BICYCLE` demand more speed from the start.** Deferred: no field evidence yet of bicycles being mistaken for rides; adding a second threshold now would be a guess (ADR-018).

## Reopen / supersede triggers

- `CANDIDATE_START_REJECTED` events after an `ON_BICYCLE_ENTER` showing walks or genuine cycling being recorded often enough to matter (look at `maxSpeedMps` of the rejected and, for the ones that confirmed, at the Trips deleted).
- Rides still missing with labels this ADR does not cover, or with an EXIT and ENTER in separate broadcasts.
- A rise in candidates/GPS minutes per day large enough to show on the phone's battery.
- `EXP-008` freezing different production values.

## Traceability

- `trip-detection-spec.md` DP-001/002/007, §5 CANDIDATE_START / CANDIDATE_STOP; `ADR-007` (Activity Recognition as the passive trigger), `ADR-018` (thresholds field-gated), `ADR-025` (what confirms and ends a candidate), `ADR-013` (the domain stays free of Android)
- `observability-diagnostics.md` §7.1 (reason codes), `field-experiment-design.md` (how often a motorcycle is `IN_VEHICLE` or `ON_BICYCLE`)
- Backlog `DET-009`; extends `DET-008`/`DET-001`/`AUTO-001` behaviour

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
