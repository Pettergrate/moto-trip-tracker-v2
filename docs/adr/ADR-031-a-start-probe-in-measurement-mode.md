# ADR-031 — A start probe, in measurement mode: a short GPS look when the phone stops being still, to learn whether a ride begins before Android calls it one

**Status:** Accepted (measurement mode; making it start captures is a separate, later decision)
**Date:** 2026-10-04
**Project:** Moto Trip Tracker V2
**Phase:** Phase 1 — `DET-012` (owner-approved: "sí" to the recommendation after `ADR-030`'s measurement)

---

## Context

Every automatic ride that began recording late did so for the same reason: Activity Recognition's `IN_VEHICLE` label arrives after the rider is at speed (or after the first stop), and the GPS only starts when it does (`ADR-026`, `ADR-027`; the numbers are in `ADR-030`). `ADR-030` tried a trigger that does not depend on the label - a geofence around the parked phone - and measured it: one EXIT in seven rides, 2 min 28 s *after* the label trigger, in the wrong place most of the time. It was removed.

The same measurement pointed at a signal that Android *does* deliver promptly and that was already being recorded: the `STILL` → `WALKING` transition (the phone stops being still), about twenty a day. In nine rides it preceded the label trigger by 1.6, 2.0, 4.1, 6.2 and 10.1 minutes in five of them and was absent in the other four. What it cannot do alone is say whether the walking is a ride beginning (a rider walking to the bike and riding off) or just a walk; GPS speed can.

Cost, from the app's own battery statistics: the foreground service (which is what GPS recording runs under) used 10.6 mAh in 55 min 38 s, ≈ 0.19 mAh per minute. A day of twenty probes of up to six minutes is at most ~23 mAh (0.4 % of the 6,000 mAh battery), typically a fraction of that. The app's whole attributed share over 1.5 days was 22.6 mAh.

## Decision

1. **A start probe is opened when Activity Recognition reports `STILL` EXIT.** `ActivityTransitionReceiver.maybeStartProbe` starts `TrackingForegroundService` with `ACTION_START_PROBE`; the service runs `TrackingSessionCoordinator.runStartProbe` for at most `StartProbeProfile.windowMs` (6 minutes).
   Not when the same batch carries a vehicle ENTER (the real detection opens its own, better-informed candidate), not while a capture is ACTIVE, not unless Auto Tracking can start trips by itself (the same capability gate as the real detection), and not within `quietAfterRideMs` (3 minutes) of a ride ending: that `STILL`→`WALKING` is the rider walking away from the bike.
2. **Speed decides, never distance.** The probe uses the detector's own `CandidateStartEngine`, opened by a new `openByProbe` (no vehicle label behind it), with a profile (`StartProbeProfile.toCandidateProfile`) whose displacement bar is unreachable: three consecutive fixes at 2.5 m/s or faster after at least 5 s. A person walking 300 m confirms nothing; displacement from an anchor is exactly what would.
3. **Measurement mode: it records what it would have done and does none of it.** It writes `START_PROBE_STARTED`, `START_PROBE_WOULD_START` (the first time speed says a ride has begun, with the evidence and `afterMs`) and `START_PROBE_ENDED` (reason `WINDOW_EXPIRED` / `SUPERSEDED_BY_DETECTION` / `CAPTURE_STARTED` / `CANCELLED`, with `elapsedMs`, `fixCount`, `maxSpeedMps`, `displacementM`, `wouldHaveStarted`, `wouldStartAfterMs`, `displacementAtWouldStartM`). **No capture is created, no raw point is written, nothing about how rides are recorded changes.** The coordinates stay in memory for the length of the probe and appear in no event (`ADR-009`) - only counts, speeds, milliseconds and straight-line metres.
4. **The probe never gets in the way.** A real `ACTION_AUTO_DETECT` (or a manual start) takes the phone over: the service starts the real work first and only then ends the probe with the reason, so the probe's own end never stops a service that is needed. A probe that ends by itself stops the service only if nothing else is running. A probe is not started when a capture is active or a detection is running (checked again in the service). A failure to consider or start a probe never disturbs the detection that precedes it in the receiver; a refused service start is recorded (`START_PROBE_NOT_STARTED`, `SERVICE_START_FAILED`).
5. **The notification is the existing "checking for a possible trip" one** (low importance, silent), shown for as long as a probe runs.

## How this is measured

From `START_PROBE_*` and the captures (phone database; copy with `adb exec-out run-as ... cat`, query only these tables, delete the copy):

- **Lead, per ride:** the capture's `startedAt` minus the `START_PROBE_WOULD_START` of the probe that overlapped its start. Positive means the probe would have started recording earlier than the label did; with the probe's `displacementM` at the end (`SUPERSEDED_BY_DETECTION`) versus `displacementAtWouldStartM` it estimates the route that would have been recovered.
- **False starts:** `START_PROBE_WOULD_START` with no capture within the next five minutes: a run, a bicycle, a phone in a car - each would have been a trip nobody wanted.
- **Rides it misses:** captures with no probe, or whose probe never reached `WOULD_START` before the label did (the four of nine with no preceding transition, and any whose walking was slower than 2.5 m/s until it was too late).
- **Cost:** the sum of `START_PROBE_ENDED.elapsedMs` per day (GPS minutes) against the battery statistics of the app's uid before and after (`dumpsys batterystats --charged`, compared by rate - the counter resets on a charge). Baseline before the probe ever ran: 22.6 mAh over 36 h 54 min on battery (0.61 mAh/h), of which the foreground service 10.6 mAh (55 min 38 s).

## Rationale

- The signal is already delivered promptly and already stored; what was missing was something to look at it with. GPS speed is what separates a ride beginning from a walk, and the detector already trusts exactly that (`ADR-025`).
- Measuring first costs one or two days of a bounded GPS cost and changes nothing about how the owner's rides are recorded. Making it act (open a real capture on `WOULD_START`, with `ADR-027`'s retained fixes beginning at the probe) needs one more piece - trimming the start of the Trip to where the movement began, the mirror of `ADR-025`'s end trim, so that a trip does not begin with the walk to the bike - and should be built knowing how often the probe is right.
- Reusing the detector's own engine keeps one definition of "speed says a ride has begun".

## Consequences

- **A silent status notification appears for up to six minutes after each time the phone stops being still** (about twenty a day, fewer when the quiet-after-ride rule or an active capture suppresses them). It is the same low-importance notification the detector already shows while validating a candidate; it is the visible price of GPS running in the background.
- GPS runs for those minutes: bounded by the window and, from the numbers above, at most a few tenths of a percent of the battery a day. Measured, not assumed, after the first days.
- A probe that overlaps a real ride ends with the reason and what it had seen, so a ride gives a paired record (probe evidence, then capture) to read the lead from.
- `START_PROBE_*` rows are added to the diagnostic table (about three per probe, a few dozen probes a day); bounded by `DIA-004`'s retention.
- The foreground-service start from the receiver relies on Activity Recognition's broadcast being allowed to start one, which it already is for the real detection.

## Alternatives considered

- **Build it acting from day one.** Rejected (owner-approved plan to measure first): a probe that starts captures could record walks, runs and bicycles before anyone knows how often it is wrong, and it needs the start trim first.
- **Open the probe on `WALKING` ENTER as well.** Rejected: it always coincides with `STILL` EXIT in the data (the same event), so it would double the probes.
- **A longer window (10 minutes).** Rejected for now: it would cover the fifth ride (10.1 min before) but cost 70 % more GPS for the same probes; the window is a parameter to revisit with the data.
- **A geofence, significant motion, or distance-filtered location updates.** Rejected by `ADR-030`'s measurement and reasoning.
- **Do nothing.** Remains the fallback if the probe turns out to be late, wrong or expensive.

## Reopen / supersede triggers

- The measurement: a lead too small to matter, too many `WOULD_START` with no ride, or a cost that is not small - remove or retune (window, speed bar, quiet time).
- A clear lead at a bearable cost - the next ADR makes `WOULD_START` open a real capture, with the start trim.
- Rides that begin without any `STILL` EXIT (the four of nine): nothing here helps them; look for another signal.

## Traceability

- `ADR-007` (Activity Recognition as the passive trigger), `ADR-009` (no coordinates in diagnostics), `ADR-017`, `ADR-018` (every number a placeholder, here chosen to be measured), `ADR-025` (what speed means), `ADR-026` (labels), `ADR-027` (retained fixes), `ADR-030` (the measurement that led here)
- `trip-detection-spec.md` DP-001/002/008 (high-detail location only while validating or recording), `F0.3` §6 requirement 5
- Backlog `DET-012`

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
