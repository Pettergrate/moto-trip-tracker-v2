# ADR-023 — Live map during an active Trip, and an externally-driven route scrubber

**Status:** Accepted
**Date:** 2026-09-30
**Project:** Moto Trip Tracker V2
**Phase:** Phase 1 — `MAP-003`/`MAP-004` (owner-requested feature investigation)

---

## Context

The owner asked for two features while the phone was connected: (1) a scrubber on Trip Detail - dragging a point along a bar highlights the corresponding point on the map, and (2) seeing the route drawn on the map *while* a Trip is being recorded, instead of the "Map not available yet" placeholder that `ActiveTripScreen` always shows today.

`ADR-021` scoped `MAP-001` to the **completed** Trip's map (`FR-MAP-001`'s literal wording) and named this exact gap explicitly, twice: its Consequences section states "a live-updating map *during* an active trip is a distinct, harder problem (no Processed Track exists yet for an in-progress capture)", and its own Reopen/supersede triggers list "a real need emerges for a live map during an active trip... out of this ADR's evaluated scope." That need has now emerged. Per the owner's instruction, this ADR follows a full investigation of the existing code (not a guess) before any implementation, mirroring how Phase 0's research docs preceded their own ADRs.

**Investigation findings** (`app/src/main/java/com/mototriptracker/app/`, exact paths below):

1. **The "no Processed Track exists yet" framing is only half true.** It is accurate for the *processed* track (`ProcessedTrackPointDao`, populated only by `TripProcessingWorker` after a capture commits, per `ADR-015`) - there is genuinely no `tripId` and no processed row during an active capture. But **raw points are already live and observable**: `ActiveTripViewModel.kt:77` already subscribes to `rawTrackPointDao.observeAllByCapture(capture.id)`, a `Flow<List<RawTrackPointEntity>>` that re-emits as `RawPointWriter` inserts each new point - already wired into `ActiveTripUiState`'s live-distance calculation, just never mapped to `GeoPoint` and handed to a map.
2. **`TripRouteMap` (`feature/map/TripRouteMap.kt`), the sole MapLibre entry point (`ADR-019`'s isolation boundary), is not built for a growing point list.** `configureRoute()` calls `map.setStyle(Style.Builder().fromUri(...))` (a full vector-style re-fetch/rebuild) inside a `LaunchedEffect(points, map, ...)` that re-runs on every `points` change (lines 112-115, 231-233). Only the already-existing "selected point" marker layer updates incrementally (`updateSelectedPointLayer`, `source.setGeoJson(...)`, no `setStyle`). Feeding it a point list that grows every few seconds, unmodified, would re-trigger a full style rebuild on every emission - expensive and the kind of thing that already caused a real bug once (the file's own lines 97-110 document a prior remember/lifecycle issue from exactly this interaction).
3. **`RouteSimplifier.simplifyRoute` is a full, non-incremental Ramer-Douglas-Peucker recompute** (`domain/RouteSimplifier.kt`) - no streaming variant exists. `PERF-003`'s own benchmark (10,000 points, <2s on this JVM) suggests it is workable if not re-run on every single GPS fix, but running it on every live-point emission at a 2s interval is untested and plausibly wasteful.
4. **Device GPU render time for `TripRouteMap` has never been measured** (`PERF-003`'s own stated honest gap: "no device or emulator... in this environment"). With the phone connected this session, that gap can finally be closed empirically as part of implementing this ADR, rather than staying a guess.
5. **The scrubber's core mechanism already exists and already ships**, for a different purpose: `TripRouteMap`'s `markerPoints` parameter (lines 82-83, 152-154, 201) puts the map under *external* control - the caller says which point(s) to highlight, and `updateSelectedPointLayer` updates that layer cheaply (`source.setGeoJson`, no style rebuild). `TrimScreen.kt`/`TripSplitViewModel`'s Split screen already drive this exact channel from a slider (`RangeSlider` in `TrimScreen.kt:85-90` → `TrimViewModel.onRangeChanged` → `markerPoints`). A read-only single-thumb scrubber is the same shape, minus the write-a-new-Trip step. The one missing primitive is **camera follow**: today only `fitCameraToRoute()` (whole-route bounds) exists; there is no "pan/ease camera to point N" API.
6. **`ADR-022` (approximate-location fixes) applies here too**: a live view must exclude or mark `isApproximateLocation == true` points the same way `LiveDistance.kt:17` already filters them from the live-distance figure, and the way `ProcessingEngine`'s `REJECTED_APPROXIMATE_LOCATION` excludes them from the final route - a live map must not show a route that the final map would never have drawn.

## Decision

Both features are **approved**, scoped as follows, extending rather than replacing the existing architecture:

1. **`MAP-003` - live map during an active Trip.** `ActiveTripViewModel` maps its already-live raw-point `Flow` to `GeoPoint`s (excluding `isApproximateLocation == true`, per finding 6). `TripRouteMap` gains an **incremental-update mode**: `configureRoute`'s full `setStyle` rebuild runs once, on first mount; subsequent point-list growth updates the existing route `GeoJsonSource` in place (`source.setGeoJson(...)`, the same cheap mechanism the selected-point layer already uses), never re-fetching the style. Simplification is throttled (re-run at most every N new points or every few seconds, not per-fix) rather than run on every emission. `ActiveTripScreen` replaces its unconditional placeholder with `TripRouteMap` fed this live list, falling back to the existing "Map not available yet" placeholder on any rendering failure (`FR-MAP-006`, unchanged degrade path) or while fewer than 2 points exist (`TripRouteMap`'s own existing behavior).
2. **`MAP-004` - route position scrubber on Trip Detail.** A single-thumb slider drives `TripRouteMap`'s existing `markerPoints` channel exactly as Split's does today, over the already-loaded, already-simplified `routePoints` (no new data path, no live concerns - Trip Detail's data is static and post-processed already). `TripRouteMap` gains a new, additive `focusPoint: GeoPoint?` parameter that eases the camera toward that point without changing zoom-to-fit behavior when absent. Strictly read-only: no DAO write, no new Room table, no `TripEditOperation`.

Both stay behind `ADR-019`'s existing presentation-adapter boundary - `TripRouteMap.kt` remains the only file importing `org.maplibre.*`; nothing about capture, persistence, `ProcessedTrackPointEntity`, or statistics changes.

## Rationale

- Extends the existing isolated adapter (`ADR-019`) instead of building a second, parallel live-only renderer - one file still owns the MapLibre boundary, consistent with why that boundary exists at all.
- Reuses a mechanism (`markerPoints`, externally-driven marker updates) already shipped and proven for Split/Trim, rather than inventing a new one for the scrubber.
- Keeps the completed-Trip map (`MAP-001`, `MAP-002`) entirely unchanged - `MAP-004`'s scrubber is additive UI over already-loaded data, not a rework of `TripDetailViewModel`.
- Respects `ADR-006`/`ADR-016`: raw points, not fabricated ones, are what a live view draws; a gap stays a gap, an approximate fix stays excluded, exactly as the final map already treats them (`ADR-022`).
- The one real open question - actual on-device render cost of incremental updates at recording cadence - gets answered by measurement during implementation (the phone is available this session), not asserted here.

## Consequences

- `TripRouteMap`'s public API grows: an incremental-update code path (internal) and a new optional `focusPoint` parameter (additive, default `null`, no existing caller changes behavior). `ActiveTripUiState`/`ActiveTripViewModel` gain a route field; `ActiveTripScreen` gains a conditional map/placeholder branch.
- `RouteSimplifier` gains a throttling caller (in `ActiveTripViewModel` or a small new helper), not a change to the pure algorithm itself.
- A live map adds a small, continuous rendering/GPU cost while a Trip records - unmeasured until implementation's own on-device verification; if that cost proves too high on real hardware, the fallback is a lower live-update cadence (e.g. re-render every 10s instead of every fix), not abandoning the feature outright, per the reopen triggers below.
- No change to `RawTrackPointDao`, `TripCaptureDao`, `RawPointWriter`, `TripProcessingWorker`, or any Room schema - both features are read-only consumers of data that already exists.

## Alternatives considered

- **Build a separate, lightweight live-only route renderer instead of extending `TripRouteMap`.** Rejected: duplicates `ADR-019`'s isolation boundary in a second place, doubles the MapLibre-specific code surface to maintain, and gains nothing - the incremental-update path `MAP-003` needs is worth having in the one shared renderer regardless (it also removes the existing full-rebuild-per-change design's own latent inefficiency for anything that updates `points` after first mount).
- **Defer both indefinitely, keeping `ADR-021`'s exclusion as-is.** Rejected: the owner has a real, current need, and this investigation found no fundamental blocker - only real, boundable engineering work (an incremental-update path, a throttled simplifier call, a new camera-follow parameter) plus one genuinely unmeasured cost (device render time) that implementation itself will measure.
- **Run `simplifyRoute` on every single live-point emission, unthrottled.** Rejected without even trying it: `PERF-003`'s 10k-point JVM number (<2s) is for a one-shot call, not a call repeated every ~2s against a growing list for the length of a ride; throttling costs nothing in correctness and removes an obvious, foreseeable waste.

## Reopen / supersede triggers

- On-device measurement (this implementation's own verification) shows even a throttled incremental update is too expensive for the intended recording cadence - the fallback (lower cadence, not removal) would itself then need this ADR revisited.
- MapLibre Native ships a genuine streaming/incremental route API of its own, making the hand-rolled throttling here unnecessary.
- OpenFreeMap's public instance becomes unreliable (`ADR-021`'s own trigger, unchanged, now doubly relevant since `MAP-003` would fetch a style on every cold start of an active Trip too).

## Traceability

- `ADR-019` (isolation boundary, unchanged), `ADR-021` (provider decision, unchanged; this ADR resolves the trigger it named), `ADR-006`/`ADR-016` (raw preserved, no fabrication), `ADR-022` (approximate fixes excluded from a live view too)
- `FR-MAP-007` (live route during active recording), `FR-MAP-008` (route position scrubber)
- `ux-navigation.md` §19 (updated 2026-09-30), `UX-19`/`UX-20`
- Backlog `MAP-003`, `MAP-004`; investigation triggered by the owner directly, 2026-09-30

---

### Clarifying note (2026-09-30) - `MAP-005`, full-screen map view

The owner separately asked for a way to expand Trip Detail's map full-screen for easier manipulation (`FR-MAP-009`). Two shapes were considered: a `Dialog`-based full-screen overlay, or a real Navigation 3 destination (`Destination.TripMap(tripId)`) matching how `Split`/`Trim` already work. **Decided: a dedicated destination**, not a `Dialog` - no full-screen `Dialog` pattern exists anywhere else in this codebase (a first-of-its-kind UI paradigm would have to be introduced for it), while every other "focused sub-view of a Trip" already uses a pushed back-stack destination, which gets Predictive Back (`UX-16`) for free instead of needing its own back-handling. This does not change this ADR's decision, only extends `TripRouteMap`'s reuse to a second call site (`TripMapScreen`, reusing `TripDetailViewModel` directly rather than duplicating its route-loading query). Backlog `MAP-005`.

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
