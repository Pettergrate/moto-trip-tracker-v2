# ADR-029 — Processing v1 rejects fixes whose own reported accuracy is too poor to be a position

**Status:** Accepted
**Date:** 2026-10-02
**Project:** Moto Trip Tracker V2
**Phase:** Phase 1 — `PRC-004` (found from the owner's observation: "al finalizarlo manualmente … se volvió un poco loco o difuso el lugar de finalización")

---

## Context

`PRC-001` (processing v0) accepts every point except a non-monotonic one, on purpose: F0.5 §7.1 says "no existirá inicialmente una regla global como si accuracy > X → borrar punto" without field data to choose X, and `ADR-006` keeps raw evidence whatever its quality. Its own comment promised that accuracy-cutoff rejection was "a later `processingVersion`" once data existed.

The owner's three automatic rides of 2026-10-02 supplied it. The 13.8 km ride ended in a scribble. Reading the points back (phone database, `raw_track_point`): at 09:58:21 the GPS fix was lost (entering a garage) and for about two and a half minutes the fused provider delivered **network-based positions reporting 78 to 400 m accuracy that jump 125 to 396 m from one fix to the next** (15 points). Processing v0 drew the route through all of them and summed them: **14.10 km over every point against 11.00 km over the points reporting 50 m or better, 3.1 km of distance that did not exist.** The live preview did the same, and `REC-005`'s signal watch took those fixes as proof of a working signal, so the app said nothing was wrong while it happened.

Across the whole phone database (13,101 points in 58 captures): 97.6 % report 20 m or better, 240 (1.8 %) report 20-50 m, **exactly one reports 50-75 m**, then 74 points report 78 m or worse (mostly 100-400 m; the few at 2,000 m are the approximate-location ones) in 11 of the captures. The distribution has a natural gap between the two populations.

## Decision

1. **Processing version 1 rejects a fix whose `horizontalAccuracyM` is worse than 50 m** (`FixQuality.MAX_USABLE_HORIZONTAL_ACCURACY_M`; the limit itself is still usable), with reason `REJECTED_POOR_ACCURACY`. The rule sits after the approximate-location rule (that cause is reported first) and before the capture-boundary rule, so a poor fix cannot become a capture's first accepted point. The raw point stays untouched (`ADR-006`); it just is not evidence of where the rider went - the same treatment `ADR-022` gives a fix taken with only approximate location allowed.
2. **The stretch a rejected run leaves becomes an explicit gap**, through the existing gap detection (`GAP_NO_FIX` once 30 s pass between accepted points) - not bridged, not invented (`ADR-016`).
3. **`CURRENT_PROCESSING_VERSION` goes from 0 to 1** (`ADR-014`). Existing Trips are reprocessed by `DerivedDataReconciler`, which already runs at every app start and treats a Trip with only an older version's rows as missing the current one; the version-0 rows stay.
4. **The live views use the same rule**: `liveDistanceMeters` and the live route skip a fix that is not a route point (`RawTrackPointEntity.isRoutePoint()`: not approximate, and accurate enough), so the figure shown while riding agrees with the final one.
5. **The signal watch counts such a fix as "heard, but not signal"**, like an approximate one: a stretch of only poor fixes becomes a `LOCATION_GAP` (notification and Active Trip say the GPS is lost) and a usable fix ends it. The poor fixes are still stored as raw points.

The limit is a placeholder in the sense of `ADR-018` - derived from this one phone and revisited with `EXP-008` - but it is read from data, not guessed: a threshold anywhere in the empty 50-75 m stretch separates the two populations, and 50 m is the round number inside it.

## Rationale

- The two populations are different things, not two ends of one: the 20-50 m fixes are ordinary GPS in a street with buildings; the 78-400 m ones are what the platform falls back to when it has no GPS. Rejecting only the second group changes nothing for the first.
- Rejecting in processing, not at capture, keeps `ADR-006`'s invariant (raw is preserved, processed is reproducible): a later version with a better rule can re-derive from the same raw points.
- Drawing the lost stretch as a gap tells the truth about it: the rider was somewhere under cover for 161 s; the app does not know where.

## Consequences

- The 13.8 km ride's distance drops by about 3 km once reprocessed (to about 11 km); its route ends where the good fixes end, then a gap, then the destination. Every trip is reprocessed at the next app start (about 58 captures; each takes a moment).
- The first fix of a capture is often poor (71 and 88 m seen) and is now rejected; the next one, seconds later, starts the route. Distance and duration move by that one point.
- A rider in a long tunnel or garage sees a gap instead of a scribble; the capture stays ACTIVE and keeps storing whatever arrives (`REC-005` §13.2).
- Speed-spike and teleport rejection remain undone: no data yet shows a high-accuracy fix that teleports (2 "impossible" jumps among 11,986 points at 10 m or better, both worth a look before a rule).
- The threshold is global, not per provider: network and GPS fixes are told apart by what they report, not by who produced them.

## Alternatives considered

- **Filter at capture (drop the fix before storing it).** Rejected: it destroys evidence (`ADR-006`) and cannot be redone with a better rule.
- **A lower limit (20-30 m).** Rejected: 240 points (1.8 %) sit between 20 and 50 m, ordinary urban GPS; cutting there would punch holes in normal rides and create gaps that are not gaps.
- **A speed/jump-based rule (reject a point implying more than N m/s).** Rejected for now: it needs a second threshold with no data behind it, and the accuracy the fix reports already separates the cases seen. Reopen if a high-accuracy teleport shows up.
- **Smoothing instead of rejecting.** Rejected: it would draw a route the phone never measured.

## Reopen / supersede triggers

- A ride whose route has holes that a human would draw through (look for runs of `REJECTED_POOR_ACCURACY` between accepted points of 20-50 m).
- A high-accuracy fix that still teleports - then a jump-based rule is the next step.
- A different phone whose GPS reports accuracy on a different scale.
- `EXP-008` freezing different production values.

## Traceability

- `F0.5` §7.1 (no global accuracy rule without field data - now there is), §16 (pipeline); `ADR-006` (raw preserved), `ADR-014` (processing versioning), `ADR-016` (explicit gaps), `ADR-018` (thresholds field-gated), `ADR-022` (fixes that are not positions)
- Backlog `PRC-004`; extends `PRC-001`, `UI-001`, `REC-005`

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
