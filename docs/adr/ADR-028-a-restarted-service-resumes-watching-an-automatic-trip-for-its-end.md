# ADR-028 — A restarted service goes back to watching an automatic Trip for its end, rebuilding the stop engine from the record

**Status:** Accepted
**Date:** 2026-10-01
**Project:** Moto Trip Tracker V2
**Phase:** Phase 1 — `AUTO-002` (owner-approved: "si me gusta la 1 y la 2")

---

## Context

An automatic Trip is started and ended by one coroutine, `TrackingSessionCoordinator.runAutoDetection` (`AUTO-001`): it validates the start, persists the raw points, and watches for the end with `CandidateStopEngine`. All of its state - the stop engine, the writer, the signal watch - lives in memory.

When Android kills the process mid-ride (`REC-001`/`REC-002`: the service is `START_STICKY`), the service comes back with a null intent and `rehydrateOrStop` resumes **plain recording** (`recordLocationUpdates`) into the still-ACTIVE capture. No data is lost - but nothing watches for the end any more: an automatic capture has no forgotten-finish reminder either (`DET-007` is only for manual ones), so the capture stays open until someone finishes it by hand, collecting points indefinitely. `AUTO-001` documented this as one of two accepted v1 gaps. The owner's phone is an Honor, whose battery management is known to be aggressive, which is why the gap was worth closing. (No process death during a Trip has been observed on it so far - every automatic capture of 2026-10-01 ended with an automatic Finish - so this is protection for a case the field data has not met yet.)

Restarting the monitoring is not enough by itself. A fresh `CandidateStopEngine` knows nothing: if the vehicle stopped while the process was dead, the engine would never see the `IN_VEHICLE` EXIT (it was delivered, and recorded, while nobody was subscribed) and the trip would never end. And the opposite error is worse: if a stop candidate had been open and then *cancelled* because the vehicle moved off (two fast fixes), a naive rebuild from the activity record alone would still see an EXIT with no ENTER, consider the candidate open, and end a ride that was still going at the first fix after the restart - exactly the fragmentation `ADR-025` set out to remove.

## Decision

1. **The service resumes the auto monitoring for an automatic capture.** On a restart that finds an ACTIVE capture with `startSource = AUTO`, `rehydrateOrStop` starts `runAutoDetection(resumeCaptureId = ...)` instead of plain recording. A manual capture still gets plain recording. The run skips the start phase, continues the capture's sequence numbering from `max + 1`, seeds the signal watch with the last usable stored fix (so the silence of the restart is reported as a gap, `REC-005`), writes the points itself, and logs `AUTO_STOP_MONITORING_RESUMED` (`reasonCode` `TRACKING` or `STOP_CANDIDATE_OPEN`, metadata `replayedTransitions` / `replayedPoints`).
2. **The stop engine is rebuilt by replaying what was recorded** - the activity transitions the receiver stored (it records them even when the app is not running; read through a narrow query on the diagnostic table, bounded by wall time as well as elapsed time because the elapsed counter restarts at every boot) and the raw points stored for the capture - merged by their own timestamps, activity first at a tie, a label change's ENTER before its EXIT (`ADR-026`). The engine is deterministic, so replaying the same inputs reproduces the state the live one had, including a candidate that the vehicle moving off had cancelled.
3. **The replay starts at the last moment the engine's state could have been reset**: the capture's start, or the end of its latest manual pause (a pause discards any stop candidate, `ADR-025`). If a pause is open at the restart nothing is replayed (no candidate can exist during one).
4. **A replay that already confirms the end finishes the Trip at once**, through the same code as the live path (`finishAutoCapture`: the same speed-evidence rule for trimming, the same logging after the commit). The process may have died after the last point proving the stop was stored but before the Finish ran.
5. **Reading the record never decides to *keep recording less*.** A pause open, a record that cannot be read, or a transition row that cannot be parsed leaves the engine fresh: not knowing is a reason to keep recording, never to end a Trip. Nothing is written from the replay itself - the decisions it reproduces were logged when they first happened.

## Rationale

- Replaying real, already-stored evidence through the same engine is the only way to keep one definition of "when does a Trip end". A second, simplified rule for the restarted case (for example "end it if nothing moved for N minutes") would be a new threshold with no field data behind it (`ADR-018`), and would diverge from the live behaviour the owner has just accepted.
- The activity transitions are already persisted, durably, by a component that runs with or without the app (`DET-001`); adding a second store for them would duplicate it.
- Rebuilding from raw points as well as transitions is what protects against the worse error above; the cost (one read of the points since the last pause, once per restart) is small.

## Consequences

- An automatic Trip whose process was killed now ends by itself, with the same grace period and trim as any other automatic Trip. If the vehicle had stopped during the downtime, the Trip's end is the last stored point before the stop when the points since then show it stayed stopped (`ADR-025` decision 8), otherwise the moment the Finish runs - the unrecorded interval is then part of its duration, as it already is for a manual Finish after a restart.
- If Auto Tracking was switched off in the meantime, the receiver drops the transitions (`PERM-002`); the resumed monitoring then sees fixes but no EXIT, so the Trip is not ended automatically - the same as before this decision, not worse.
- Reading `diagnostic_event` for behaviour is a coupling to a table with a retention policy (14 days / 20,000 rows, `DIA-004`); it is bounded to the capture's own span, which is far inside it, and a missing record degrades to "fresh engine" (decision 5).
- A candidate that was open when the process died but whose cancelling fixes were never stored (they arrived during the downtime) can still end the Trip once the grace period has passed - the downtime had no evidence either way.
- New diagnostic event `AUTO_STOP_MONITORING_RESUMED`; new DAO queries `DiagnosticEventDao.findActivityTransitionsSince` and `RawTrackPointDao.findByCaptureSince`; `entersFirstAtTheSameInstant` moved from the receiver to the domain so both use the one ordering.

## Alternatives considered

- **Resume plain recording and add a forgotten-finish reminder for automatic captures too.** Rejected: it asks the rider to do what the detector exists to do, and the reminder (`DET-007`) was built for the case where there is no automatic stop at all.
- **Persist the stop engine's state periodically and load it after a restart.** Rejected: a new table and a write on every state change, for state that is exactly derivable from data already stored.
- **Start a fresh engine and accept missing a stop that happened during the downtime.** Rejected: the downtime is when the ride is most likely to have ended (the phone was in a pocket, the system reclaimed memory).
- **Rebuild from the activity transitions only.** Rejected: see Context - it would end rides that were still going.

## Reopen / supersede triggers

- The first real process death during an automatic Trip (`PROCESS_EXIT` with an ACTIVE automatic capture): check `AUTO_STOP_MONITORING_RESUMED` and whether the Trip ended where it should.
- Evidence that activity transitions are not always recorded while the app is not running, or that the diagnostic retention removes them early.
- A change to the stop engine's state that the replay inputs (transitions and stored points) can no longer reproduce - then the engine's state needs its own durable form.

## Traceability

- `trip-detection-spec.md` DP-001/002/004/007, §7 (stop behaviour), §9 (post-finish handling); `ADR-006` (raw preserved), `ADR-015`/`ADR-020` (finish idempotent and transactional, single active capture), `ADR-018` (no new threshold), `ADR-025` (what ends a Trip), `ADR-026` (label order)
- `F0.10` §7 (recovery), `REC-001`/`REC-002`/`REC-005`; Backlog `AUTO-002`; closes the second of `AUTO-001`'s two documented v1 gaps

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
