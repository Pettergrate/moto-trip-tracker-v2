# ADR-024 — Motorcycle Profiles (`FR-MOTO-001/002/003`), and a per-vehicle map icon with heading

**Status:** Accepted
**Date:** 2026-09-30
**Project:** Moto Trip Tracker V2
**Phase:** Phase 1 — `MOTO-001`/`MAP-006` (owner-requested)

---

## Context

`MotorcycleEntity` has existed in the schema since `FND-003` (F0.7 §11), explicitly scoped "Post-Core": `Trip.motorcycleId` is optional and nothing blocks the association, but no `MotorcycleDao` exists and nothing anywhere ever writes a real row - confirmed by grep, every Trip-creation call site in `TrackingSessionCoordinator` hardcodes `motorcycleId = null`. `requirements-scope.md` §4.12 already specifies three requirements for this: an entity able to associate a Trip with a Motorcycle (`FR-MOTO-001`), eventual support for more than one (`FR-MOTO-002`), and distance aggregated per Motorcycle (`FR-MOTO-003`).

Separately, the owner asked for the map's "current position" marker (today a plain fixed-color circle, both live during a Trip - `MAP-003` - and as the "end" marker on a completed Trip) to show a chosen vehicle icon (car/motorcycle/truck/...), and for that icon to rotate to face the direction of travel. Investigated and confirmed by a real compile check: MapLibre's `SymbolLayer` with `PropertyFactory.textField`/`textRotate` renders an emoji character as a rotatable marker, with **no new dependency and no bitmap asset pipeline** - the same "plain glyph over the extended icon pack" posture `TripRouteMap.kt` already established for its "⤢"/"⛶" buttons.

The owner explicitly asked for `FR-MOTO` to be built "grande y completo" (large and complete), with the icon/rotation feature tied to it, rather than the smaller, decoupled single-global-preference version investigated first.

## Decision

1. **Schema.** `MotorcycleEntity` gains `vehicleType: VehicleType` (new plain enum, `Taxonomies.kt`: `MOTORCYCLE, CAR, TRUCK, BICYCLE, NONE`), `NOT NULL DEFAULT 'MOTORCYCLE'` - safe because this table has never had a real row written by any code path (confirmed above), so there is nothing to backfill or fabricate (`ADR-016` does not apply: this is a user selection with a sensible default, not measured/sensor data). `MIGRATION_3_4`, version 3 -> 4. `NONE` (owner-requested, same session, after the icon shipped) is an explicit opt-out - the plain colored dot the marker used before this task - for whoever doesn't want a vehicle-specific icon; added after the other four with no migration impact (both Room and `MapMarkerPreferences` persist this enum by `.name`, with an existing "unrecognized falls back to a safe default" path already in place for exactly this kind of safe append).
2. **`MotorcycleDao`** (new): insert, update (name/make/model/year), archive/unarchive (`isArchived`, never a hard delete - matches the entity's own existing field and F0.7 §11's "borrar/archivar una moto no debe volver ilegible su historial"), set vehicle type, `observeAll`/`observeActive`, and a reactive per-motorcycle distance aggregate (`FR-MOTO-003`) - `SUM(trip_statistics.distanceM)` joined through `trip.motorcycleId`, `COALESCE`d to `0.0` for a motorcycle with no completed Trips yet.
3. **Assignment is manual, from Trip Detail - never automatic at Start/Finish.** `TrackingSessionCoordinator` is not touched. A Trip is created exactly as today (`motorcycleId = null`); Trip Detail gets a field to assign/reassign a motorcycle at any time (the same `TripDao.rename`-shaped `UPDATE` pattern), defaulting to unassigned. This is a deliberate scope boundary, not an oversight: `TrackingSessionCoordinator` is this codebase's most reliability-critical file (every `REL-INV` invariant, the whole `REC`/`DET` family), and one of the two real Trip-creation call sites is the abrupt-process-death *recovery* path specifically - touching it for a cosmetic association is not a risk worth taking for what a manual, always-available assignment already satisfies just as completely for `FR-MOTO-001/002/003`'s own wording (none of which says assignment must happen automatically).
4. **The live map's icon** (no `TripEntity` exists yet for an in-progress capture, so there is nothing to look up) comes from a separate, purely-cosmetic preference: which Motorcycle is "currently selected" (`MapMarkerPreferences`, DataStore, same shape as `AppearancePreferences`) - read only by `ActiveTripViewModel` to decide which icon to draw, never written back to or read by any tracking/persistence code.
5. **Icon precedence:** a Trip's own assigned motorcycle's `vehicleType`, else the "currently selected" motorcycle (live map only), else a global default `vehicleType` (same `MapMarkerPreferences`) - so the feature is fully useful even for someone who never opens a Motorcycles list at all.
6. **Rotation** is computed from the already-available route geometry - a new pure `bearingDegrees(from: GeoPoint, to: GeoPoint): Double` (`domain/Bearing.kt`, standard initial-bearing formula) over the last two points of whatever list `TripRouteMap` already has, fed to `PropertyFactory.textRotate`. This needs no schema/entity change (`GeoPoint` stays exactly `{latitude, longitude}`) and works identically for the live map and a completed Trip's map. Fewer than two points: no rotation (an honest degrade, not a guess).

## Rationale

- Builds the requirement family that already exists in the requirements document, rather than inventing new scope - `FR-MOTO-001/002/003` are P1, already written, already have a schema placeholder waiting.
- Archiving instead of deleting matches the entity's own existing field and the domain doc's own rule; `SET_NULL` (already the FK's `onDelete`, unchanged) is what keeps an archived or deleted motorcycle's Trip history legible.
- Keeping assignment manual and Start/Finish untouched respects this project's own established caution around `TrackingSessionCoordinator` (`REL-001`'s own investigation tonight treated it the same way) without giving up any of the three FRs.
- Deriving rotation from existing geometry instead of adding a stored bearing column keeps `GeoPoint`'s boundary simple (`ADR-019`) and avoids a second schema change for a presentation-only detail.

## Consequences

- Schema version 4; `MIGRATION_3_4` needs the same JVM proof (`MigrationTestHelper` against the real exported schema) `MIG-001` already established the pattern for.
- `TripDao` gains one new `UPDATE` query (`assignMotorcycle`); no existing query changes shape.
- A new Settings destination (Motorcycles list + add/edit) - reachable from `SettingsScreen`, not a tab, same standing as Trash/Auto Tracking.
- `TripRouteMap`'s end/current-position `CircleLayer` becomes a `SymbolLayer` (icon + rotation) - the start marker and the selected-point/scrubber marker are unchanged, since neither was part of what was asked.
- `TripDetailUiState`/`ActiveTripUiState` each gain the small additional field(s) needed to know which `vehicleType` to draw (resolved once, in the `ViewModel`, per the precedence rule above - `TripRouteMap` itself still only ever sees a plain value, never a `Motorcycle`/DAO type, preserving `ADR-019`'s isolation).

## Alternatives considered

- **A single global vehicle-icon preference, no Motorcycle CRUD at all** (the originally investigated, smaller scope). Rejected per the owner's explicit request for the full `FR-MOTO` family instead; kept anyway as the fallback tier (point 5), since it costs nothing extra and covers the no-motorcycle-assigned case.
- **Auto-assign the current motorcycle to every new Trip at Start/Finish.** Rejected - the only way to do this touches `TrackingSessionCoordinator`'s Trip-creation call sites, including the abrupt-death recovery path, for a benefit (not re-tapping a picker) that does not justify the added risk surface in this codebase's most reliability-critical file.
- **A stored per-point bearing/heading column.** Rejected - `RawTrackPointEntity.bearingDeg` already exists from the GPS fix itself, but `GeoPoint`/`ProcessedTrackPointEntity`/`RouteSimplifier`'s output carry none of that through, and threading it through every map-facing type only to feed one marker's rotation is a second schema surface for a value the existing two-point geometry already gives honestly.

## Reopen / supersede triggers

- A real, demonstrated need to auto-assign a motorcycle at Start (e.g. field feedback that manual assignment is a genuine friction point) - would need `TrackingSessionCoordinator` touched carefully, its own dedicated task.
- ~~MapLibre Native ships true icon-bitmap (not text-glyph) marker support this project later wants for a richer look than an emoji glyph provides.~~ Triggered sooner than expected, and for a different reason - see the correction below.

## Correction (2026-09-30, found during this task's own on-device verification)

Point 6's `textField`/`textRotate` premise was wrong in a way the throwaway compile check could not have caught: it proved MapLibre's API **accepts** an emoji string, not that the string **renders**. On the real phone it rendered as nothing at all - no crash, no `logcat` error, just an invisible marker at the route's end point, confirmed by zooming into that exact spot at high map zoom. Root cause: the vector style's own glyph server (`tiles.openfreemap.org`, and this is true of most vector-tile glyph backends) pre-renders SDF glyphs for a curated Unicode range meant for place-name labels - it does not carry emoji glyphs, so `textField` had nothing to draw and MapLibre silently skips a glyph it can't find.

**Fix:** the marker is now `iconImage`/`iconRotate` on the same `SymbolLayer`, pointing at a `Bitmap` rasterized from the emoji via Android's own `Canvas`/`Paint` text rendering (`vehicleIconBitmap`) - a real system font fallback chain that does include color emoji, registered once per `VehicleType` (`Style.Builder.withImage`) rather than re-rasterized on every marker update. This is still not a checked-in bitmap asset and not a new dependency - the "plain glyph over the extended icon pack" posture from the original Context holds - just rendered through a path that actually shows up on a real device. `dotBitmap` (the `NONE` option above) uses the same `iconImage` path with a plain filled circle instead of text.

This changes the file-level consequence in the Consequences section below only in *how* the `SymbolLayer` gets its image, not that it is one - `TripRouteMap`'s own `org.maplibre.*`-only boundary (`ADR-019`) is unaffected.

## Traceability

- `domain-data-model.md` §11 (Motorcycle), §12 (relationships); `requirements-scope.md` `FR-MOTO-001/002/003`
- `ADR-016` (no fabrication - does not apply to `vehicleType`'s default, reasoned above), `ADR-019` (map isolation, unchanged), `ADR-023` (`TripRouteMap`'s own marker/camera control surface, extended again)
- `REL-001`'s own established caution around `TrackingSessionCoordinator`
- Backlog `MOTO-001` (Motorcycle Profiles), `MAP-006` (vehicle icon + rotation)

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
