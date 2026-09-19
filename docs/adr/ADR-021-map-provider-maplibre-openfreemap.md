# ADR-021 — Map provider: MapLibre Native + OpenFreeMap

**Status:** Accepted
**Date:** 2026-09-18
**Project:** Moto Trip Tracker V2
**Phase:** Phase 1 — `MAP-001`

---

## Context

`ADR-019` deliberately deferred the concrete map provider until it could be evaluated against real licensing, cost, Compose support and offline-behavior criteria, rather than picked for convenience during F0.8. `MAP-001` is that evaluation.

Requirements that shaped the evaluation (`FR-MAP-001/002/003/006`, `ADR-019`, `system-architecture.md` §18, `ux-navigation.md` §19):

- a completed Trip's route must be viewable on a map, with start/end distinguishable;
- the map must stay usable for a route with thousands of points;
- failure to load map content must never affect capture, persistence or statistics (already guaranteed structurally by `ADR-019`'s presentation-adapter boundary, independent of which provider sits behind it);
- no API key, billing account, or per-request cost should be required for this project's actual usage pattern (F0.5's own precedent: Google's Elevation API was already rejected as a *processing* dependency for exactly this reason — "requiere Internet, API key/billing y no puede ser requisito del Core offline-first" — the same reasoning applies here to map *display*, even though display, unlike processing, is allowed to need network at all).

## Decision

The map SDK is **MapLibre Native for Android** (`org.maplibre.gl:android-sdk`, pinned to `13.6.1` — the newest stable release verified on Maven Central as of this task, not a guess), rendering vector tiles from **OpenFreeMap**'s public instance (`https://tiles.openfreemap.org/styles/liberty`).

Both facts below were verified directly (Maven Central's own listing, OpenFreeMap's own site), not assumed from prior knowledge:

- MapLibre Native's Android SDK is licensed **BSD-2-Clause** (Maven Central POM, confirmed live) — no royalty, no usage-based billing, no API key of its own.
- OpenFreeMap's public instance requires **no API key, no registration, no request limits**, and explicitly allows commercial use; it states no SLA guarantee, funded by donations (its own FAQ, read directly, not summarized from memory).

## Rationale

- Matches this project's own established posture (`ADR-009` local-first, F0.5's Elevation API rejection): no billing account or API key gates a Core feature.
- BSD-2-Clause/open-source end to end (SDK and tile source) - no vendor lock-in, consistent with `ADR-019`'s own "evita lock-in prematuro" rationale.
- MapLibre is the actively-maintained continuation of pre-proprietary Mapbox GL, a mature, widely-deployed vector-map renderer (not an obscure or abandoned project) - verified via its own live GitHub releases (`android-v13.6.1`, published days before this decision).
- Vector tiles (not raster) render a route polyline and camera fit cheaply and scale well with zoom, a better fit for `FR-MAP-003`'s "usable with thousands of points" than a raster-tile SDK would be.

## Consequences

- No official Jetpack Compose artifact exists for MapLibre Native's Android SDK (verified against its own API documentation: the public API surface is the classic View-based `org.maplibre.android.maps.MapView`, no `androidx.compose` package). The map is integrated via Compose's own standard `AndroidView` interop, not a hack - this is a normal, supported Compose pattern for a View-based SDK, not a deviation from `ADR-002`.
- OpenFreeMap gives **no SLA guarantee** - a real, accepted risk, but one `ADR-019`'s own architecture already isolates: a tile-fetch failure degrades to the existing "Map not available yet" placeholder (`UX-15`), never touching capture, persistence or statistics. If OpenFreeMap's public instance becomes unreliable in practice, the fix is swapping the one style URL behind this same adapter (or self-hosting OpenFreeMap's own open-sourced stack), not an architecture change.
- `MAP-001`'s own scope is the **completed Trip's map** (`FR-MAP-001`'s literal wording, "a completed Trip MUST be viewable"), reading `ProcessedTrackPointEntity` (`ADR-006`'s derived, versioned track). A live-updating map *during* an active trip is a distinct, harder problem (no Processed Track exists yet for an in-progress capture) and is deliberately not this task's scope - `ActiveTripScreen`'s own "Map not available yet" placeholder stays as-is.
- A route with thousands of raw/processed points needs simplification before rendering (`FR-MAP-003`) - see the accompanying `RouteSimplifier` (Ramer-Douglas-Peucker), a separate, independently-testable domain algorithm, not part of this ADR's own decision.

## Alternatives considered

- **Google Maps SDK / Maps Compose.** Rejected: requires a Google Cloud billing account and API key even within the free tier's usage cap, the exact dependency this project has already rejected once (F0.5, Elevation API) for a Core feature.
- **Mapbox Maps SDK.** Rejected: proprietary since v10, requires an access token and has a metered free tier beyond which it bills - reintroduces the same billing dependency, and is the SDK MapLibre itself exists to provide an open alternative to.
- **osmdroid + raw OpenStreetMap tile servers.** Rejected: osmdroid itself is a reasonable, free, no-API-key library, but OpenStreetMap's own tile-usage policy explicitly discourages production apps from depending on its raw tile servers at any real scale without a proper caching/mirroring arrangement - trading one "don't depend on a single free backend without a fallback" problem for another, without MapLibre's vector-tile performance advantage for large routes.

## Reopen / supersede triggers

- OpenFreeMap's public instance becomes unreliable or shuts down in practice - the fix is a new tile source behind the same adapter, not necessarily a new ADR unless the SDK itself also changes.
- A real need emerges for a live map during an active trip, turn-by-turn navigation, or 3D terrain - out of this ADR's evaluated scope.
- MapLibre Native ships an official Compose artifact - worth adopting to drop the `AndroidView` bridge, but not an architecture change either way.

## Traceability

- `ADR-019` (this ADR resolves only the provider half of that one - `ADR-019`'s own presentation-adapter/isolation decision is unchanged and still Accepted)
- F0.2 `FR-MAP-001/002/003/006`
- F0.8 System Architecture §18
- F0.9 UX & Navigation §19

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
