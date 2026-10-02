# ADR-027 — A confirmed automatic Trip keeps the fixes seen while it was being validated, and begins at the first of them

**Status:** Accepted
**Date:** 2026-10-01
**Project:** Moto Trip Tracker V2
**Phase:** Phase 1 — `DET-010` (owner-approved: "si me gusta la 1 y la 2")

---

## Context

An automatic Trip is recorded in two phases (`AUTO-001`): a start candidate is validated (at least 15 s and 40 m, or three fast fixes — `ADR-025`), and only then does a capture exist and raw points get stored. Everything seen during validation was thrown away. `AUTO-001` documented this as a deliberate v1 gap against `trip-detection-spec.md` F0.3 §6 requirement 5 ("SHOULD not lose a large initial route section"): the first 15–20 seconds of every automatic ride were missing from the route, the distance and the duration.

The first field rides made the cost concrete (2026-10-01): the owner's rides are about 1 km and 2.7 minutes, so the discarded start was close to a tenth of each — and, because those fixes were never stored, the speed of the ride's first minutes could not be read back when a ride failed to record (`ADR-026`), leaving the analysis to inference.

## Decision

1. **The detector keeps the location fixes it sees before a capture exists**, in memory, in order. When the candidate is confirmed they are written as the capture's first raw points (sequence numbers 0, 1, 2 …, continuing without a gap into the live points), including the fix that confirmed it. At most `TrackingSessionCoordinator.MAX_CANDIDATE_FIXES` (600) are held; past that the oldest are dropped.
2. **The capture begins at the first kept fix**, not at the moment of confirmation: `startedAt` and `startElapsedRealtimeNanos` are that fix's own wall and elapsed time (`startAutoCapture(firstKeptFix)`). Everything that measures a Trip starts from the capture's start — the part's range and so its duration (`TripMetricsCalculator`), the live timer, the sealing paths that build a part after a restart — so this is the one place to change, not one per consumer. The Trip's route, distance and duration therefore include the start.
3. **The retained points say where they came from.** Their `detectorStateSnapshot` is `CANDIDATE_START` (the live ones keep `TRACKING`): the detector had not yet decided there was a Trip when each was taken, and a later analysis can tell the two apart.
4. **A candidate that is abandoned leaves nothing behind.** The held fixes are only ever written once a capture exists; an abandoned or lost-race candidate (another capture, usually manual, started first) discards them. The same holds if the process dies while a candidate is open.

Nothing is invented: every retained point is a real fix with its own timestamp, in the order it arrived (`ADR-006`, `ADR-016`). No threshold changes (`ADR-018`).

## Rationale

- The data was already being received and evaluated; only the decision to drop it was made. Keeping a few hundred fixes in memory for at most five minutes is the smallest mechanism that closes the gap, and it needs no schema change.
- Moving the capture's start, instead of leaving it at confirmation and adjusting the part, keeps one definition of "when did this Trip begin" for every consumer, including the ones written before this change.
- Holding the fixes in memory rather than writing them to Room immediately keeps abandoned candidates — a rider walking through a car park, a bus — from ever storing coordinates, in line with how little the detector is allowed to persist before it knows there is a Trip.

## Consequences

- Automatic Trips are longer by what was missing: the route starts where recording would have if the rider had pressed Start at the right moment; the distance, duration and average speed move accordingly. Trips recorded before this change are unchanged.
- The first points of a capture now predate its `CAPTURE_STARTED` bookkeeping event and the `AUTO_DETECTION_STARTED`-to-confirmation delay; `startedAt` is the first fix's time, which can be some seconds before the service saw the confirmation.
- Memory: at most 600 `LocationSample`s (a few tens of kilobytes) while a candidate is open. Persisting them costs one burst of inserts at confirmation, through the same `RawPointWriter` as live points (so a persistence failure is buffered and retried, `REC-006`).
- The candidate's own diagnostic evidence (`CandidateEvidence`) is unchanged; the retained points are the richer evidence for the same minutes.
- The fixes held when the process dies before confirmation are lost (nothing was stored, by design).
- A `DiagnosticExporter` route attachment, an export CSV and the Trip map now include the start; a raw-point CSV shows `CANDIDATE_START` for those rows.

## Alternatives considered

- **Write every fix to Room as soon as a candidate opens, and delete them if it is abandoned.** Rejected: it stores coordinates for rides that never happened and needs a delete path on an append-only table (`ADR-006`).
- **Leave the capture's start at confirmation and only extend the part's start.** Rejected: the live timer, the sealing paths and any future consumer would each need the same adjustment; one earlier start is simpler and true.
- **Keep only the most recent N seconds by time instead of a count.** Rejected for now: the window is already bounded by the candidate's five-minute limit, and a count bound is simpler to reason about and test; reopen if fix rates change a lot.

## Reopen / supersede triggers

- A change of the candidate window or of the location profile's fix rate that makes 600 fixes too few (a ride's first minutes truncated), or a memory concern.
- Evidence that retained points taken before the vehicle really moved (a long wait inside the window) distort Trip metrics — then trimming the start to the first moving fix is the follow-up, the mirror of `ADR-025`'s end trim.
- Field data showing `startedAt` earlier than the rider expects.

## Traceability

- `trip-detection-spec.md` F0.3 §6 requirement 5; `ADR-006` (raw preserved), `ADR-016` (explicit gaps, nothing fabricated), `ADR-009`/`ADR-017` (no new storage of coordinates beyond a confirmed capture), `ADR-015`/`ADR-020` (start stays idempotent and single-active), `ADR-025` (what confirms a start)
- Backlog `DET-010`; closes the first of `AUTO-001`'s two documented v1 gaps and `DET-008`'s gap (e)

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
