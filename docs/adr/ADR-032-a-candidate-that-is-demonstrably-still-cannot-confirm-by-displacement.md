# ADR-032 — A start candidate that is demonstrably still cannot confirm by displacement

**Status:** Accepted
**Date:** 2026-10-06
**Project:** Moto Trip Tracker V2
**Phase:** Phase 1 — `DET-013` (found by reading the owner's rides; the owner identified the cause: "cuando estaba en la caminadora")

---

## Context

On 2026-10-06 at 17:49 an automatic Trip of **24 minutes and 0.14 km** was recorded. The owner was on a treadmill. Reading it back from the phone's database:

- Android's Activity Recognition said `IN_VEHICLE` from 17:48:59 to 18:09:32 (a person walking on a treadmill, phone on them).
- The candidate's first fix was **73 m away** from where the phone really was, while *reporting 36 m of accuracy* - inside any sensible limit, and inside `FixQuality`'s 50 m (`ADR-029`). The second fix, 1 s later, reported 27 m and sat 73 m from the first; every later fix sat within 1-7 m of the second and **reported 0.0 m/s** (Doppler speed: not moving).
- `CandidateStartEngine` anchors on the candidate's first fix and, after 15 s, confirms when a fix is 40 m from it. At 17:49:15 it confirmed `CONFIRMED_DISPLACEMENT` with `maxSpeedMps=0.3`, `displacementM=73`, six fixes in 16.6 s: **a ride, by the engine's rule, that the GPS's own speed said was not happening.**
- The Trip then stayed open for 24 minutes (607 points, 0.14 km). It ended only because Android killed the process at 18:13:18 and `AUTO-002`'s replay finished it on restart (the stop candidate that opened at 18:09:32 could not confirm earlier: the app was silent for 233 s, an OEM background-restriction effect handled separately).

A first fix that is off by more than its reported accuracy is ordinary after a cold start (the first points of every capture on this phone report 71-88 m, and this one reported 36 m and was wrong by 73). An accuracy filter on the anchor - what `ADR-029` does for processing - would not have caught it. What distinguishes this from a ride is not the accuracy of any one fix but that **the phone is not moving**, which the fixes themselves say.

## Decision

1. **Displacement cannot confirm a start while the phone is demonstrably still.** "Demonstrably still" = the candidate's last `CandidateStartProfile.stillFixesRequired` (3) fixes all have an effective speed (`FixSpeed`: the reported speed when usable, else the speed between two close fixes) **known** and below `stillSpeedMps` (0.5 m/s, under any walking pace). Then the displacement path is closed; the candidate stays open (until its window, `WINDOW_EXPIRED`) and confirms later if the phone actually starts moving.
2. **An unknown speed never counts as still.** A ride with a weak GPS - speeds that cannot be reported or derived - must still start by displacement, exactly as before. Fewer than three recent fixes are not enough to call the phone still either.
3. **The speed path is unchanged** (three consecutive fixes at 2.5 m/s or faster); this rule only closes the displacement path in the one situation where the fixes themselves contradict it. The evidence logged when such a candidate is rejected still shows the displacement that no longer confirms (`CANDIDATE_START_REJECTED`, `displacementM`, `maxSpeedMps`).

## Rationale

- The rule uses the one signal that is independent of where the (possibly wrong) fixes say the phone is: the speed the receiver measures. It is the same evidence the detector already trusts for starting (`ADR-025`) and for ending (`resumeSpeedMps`).
- It is narrow on purpose: it blocks only on positive evidence of stillness, so it cannot cost a real ride unless three consecutive known speeds under 0.5 m/s coincide with a 40 m displacement - in which case the candidate simply waits (up to five minutes) for the movement to show.
- It protects every indoor activity Android may call a vehicle (treadmill, a stationary bike, an elevator, a car parked with the engine on) without naming any of them.

## Consequences

- A treadmill session (or any `IN_VEHICLE` label on a phone that is not going anywhere) no longer produces a Trip; it produces one `CANDIDATE_START_REJECTED` (`WINDOW_EXPIRED`) and a few minutes of GPS (the candidate window), as any rejected candidate.
- No raw point is stored for a rejected candidate (`ADR-027`): the fixes were only in memory.
- Rides: of the confirmed starts whose evidence was read back while this was written (2026-10-02 to 10-05), four reported a maximum speed between 6.3 and 29.5 m/s when they confirmed; this one reported 0.3. **A check of every confirmed start since 2026-10-02 against the rule is still to be done** (the phone was disconnected when this was written) - a real ride with three known speeds under 0.5 m/s at the moment it reached 40 m would now wait for movement instead of starting at once.
- A Trip that has already started and never moves (the second line of defence: end or discard an automatic capture that shows no movement for N minutes) is **not** built here; reopen trigger below.

## Alternatives considered

- **Filter poor fixes from the anchor/displacement** (extending `ADR-029`). Rejected for this case: the bad fix reported 36 m, inside the limit; and tightening the limit to catch it would discard ordinary urban fixes (240 of 13,101 report 20-50 m).
- **Anchor on a better fix** (the most accurate so far, or the median of the first few). Rejected for now: it would shift the error, not remove it, and changes what "displacement" means for every ride; the speed evidence is a cleaner signal.
- **Abort an automatic capture that never moves.** Sound as a second line of defence, but it needs its own thresholds (how long, how little) with no field data beyond this one case; deferred.
- **Ignore `IN_VEHICLE` when the previous label was `WALKING`.** Rejected: riders walk to their bike, and Android labels many real rides `WALKING` first.

## Reopen / supersede triggers

- Another false Trip with the phone not going anywhere but with *non-zero* reported speeds (indoor drift with noise in the Doppler speed): then the stillness window or threshold, or the second line of defence, is the next step.
- A real ride refused: a `CANDIDATE_START_REJECTED` with a large `displacementM` followed within minutes by a manual start or a later automatic start nearby.
- `EXP-008` freezing different production values.

## Traceability

- `trip-detection-spec.md` DP-001/002 (no single signal confirms; temporal confirmation), SCN-015/025 (a jump alone is not a trip), §6; `ADR-018` (thresholds field-gated), `ADR-025` (what speed means), `ADR-026` (labels), `ADR-027` (a rejected candidate stores nothing), `ADR-029` (accuracy limit for processing)
- Backlog `DET-013`

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
