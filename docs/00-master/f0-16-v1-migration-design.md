# REF-002 — V1 Data Migration Design

**Status:** Design/planning complete, first pass (2026-09-20)
**Scope:** F0.16 §4 methodology, option 3 (data migration planning) only. Design only — no import code written, no execution against the real V1 pilot database. Nothing here authorizes building the importer; that needs its own explicit go-ahead.
**Sources read:** V1 `docs/data-model.md` (§2 Trip, §3 TrackPoint, verbatim field tables); V2's real entities (`TripEntity`, `TripCaptureEntity`, `TripPartEntity`, `TripStatisticsEntity`, `ProcessedTrackPointEntity`, `PointAssessmentEntity`, `RawTrackPointEntity`) and taxonomies (`Taxonomies.kt`, `Versions.kt`).

---

## 1. The one decision everything else depends on

**V1's `TrackPoint` maps to V2's `ProcessedTrackPointEntity`, not `RawTrackPointEntity`.**

V1's own schema says a `TrackPoint` is *"una muestra **aceptada** de la ruta"* — V1 already ran its own filtering before ever writing a row; it never persisted a rejected sample at all. V2's `RawTrackPointEntity` is explicitly the opposite: *"Append-only source evidence... never mutated"*, the pre-filter input `PRC-001`'s point assessment runs against. Importing V1's already-filtered points as if they were unfiltered raw evidence would misrepresent them — and would be internally inconsistent the moment anyone ran V2's own assessment pipeline against "raw" points that were never actually raw.

Treating them as `ProcessedTrackPointEntity` instead is honest: that table is documented as a *"regenerable geometry cache... never a source of truth"* — which is exactly what an imported trip's points are relative to V2 (V2 cannot regenerate them, because it never received the real GPS stream behind them, but V1's own points were already somebody's "final" answer, same as this table's role for a native V2 trip).

**Direct, permanent consequence:** an imported trip has **zero** `RawTrackPointEntity` rows and **zero** `PointAssessmentEntity` rows, forever. It can never be reprocessed by `PRC-001`/`PRC-002`/`PRC-003` — there is no raw evidence to reprocess *from*. Its processed points and statistics are frozen at import time. This must be surfaced to whoever designs the importer's UI, not left implicit.

---

## 2. Trip → TripEntity + TripCaptureEntity + TripPartEntity

V1's `Trip` is one flat row. V2 splits the same information across three tables (`ADR-005`), so importing one V1 trip means synthesizing three linked V2 rows, not one.

| V1 `Trip` field | Maps to | Notes |
|---|---|---|
| `id` (UUID) | `TripEntity.id` | Reuse V1's own UUID string directly — V2 ids are opaque strings, and preserving it costs nothing and keeps a stable cross-reference. |
| *(none — V1 has no separate capture identity)* | `TripCaptureEntity.id` | Must be freshly generated; V1 never had this concept. |
| *(none — link table)* | `TripPartEntity` (id fresh, `tripId`, `captureId`, `orderIndex = 0`, `startElapsedRealtimeNanos`/`endElapsedRealtimeNanos` = the capture's own synthesized values below, `startSequenceNumber = 0`, `endSequenceNumber` = last point's V1 `sequence`) | Exactly one part per imported trip — V1 never merges/splits, so there is nothing to represent beyond a single whole-capture span. |
| `status` | `TripEntity.status` + `TripCaptureEntity.status` | See §4 — not a 1:1 enum rename, V1's four values don't line up with V2's two separate status enums. |
| `startMode` | `TripCaptureEntity.startSource` | Recommend `StartSource.IMPORTED` for every imported trip regardless of V1's own AUTOMATIC/MANUAL — see §4. |
| `endReason` | `TripCaptureEntity.endSource` | See §4 — `EndSource` has no `IMPORTED` value today; this is a real, small open question, not a silent default. |
| `startedAt` (Instant UTC) | `TripCaptureEntity.startedAt` (epoch ms) | Direct. |
| `endedAt` (Instant UTC, nullable) | `TripCaptureEntity.endedAt` | Direct; an imported trip should always have this (see §6 on excluding `ACTIVE` V1 trips). |
| `startedElapsed`/`endedElapsed` (monotonic) | `TripCaptureEntity.startElapsedRealtimeNanos`/`endElapsedRealtimeNanos` | **Not directly reusable.** `elapsedRealtimeNanos` resets every boot and means nothing outside the device/boot session that produced it — using V1's raw values on a different device, or the same device long after, would be meaningless or actively misleading. Recommend synthesizing a fresh pair that preserves the correct *duration* only (e.g. `0` and `(endedAt - startedAt)` converted to nanos) — safe as long as nothing downstream assumes these two fields correspond to real device uptime, which nothing should for a trip that was never actually captured by this device's location stack. |
| `distanceMeters` | `TripStatisticsEntity.distanceM` | Direct. |
| `totalDurationMillis` | `TripStatisticsEntity.totalDurationMs` | Direct. |
| `movingDurationMillis` | `TripStatisticsEntity.movingDurationMs` | Direct. |
| `stoppedDurationMillis` | `TripStatisticsEntity.stoppedDurationMs` | Direct. |
| `averageSpeedMps` | `TripStatisticsEntity.averageSpeedMps` | Direct. |
| `movingAverageSpeedMps` | `TripStatisticsEntity.averageMovingSpeedMps` | Direct. |
| `maxSpeedMps` | `TripStatisticsEntity.maxSpeedMps` | Direct. |
| `minAltitudeMeters`/`maxAltitudeMeters` | `TripStatisticsEntity.minElevationM`/`maxElevationM` | Direct. |
| `pointCount` | `TripStatisticsEntity.validPointCount` | Direct. |
| *(none — V1 has no pause/resume, per `REF-001`)* | `TripStatisticsEntity.manualPauseDurationMs` | Must be `0` for every imported trip — not unknown, genuinely zero, since V1 could not have recorded a manual pause at all. |
| *(none — V1 has no ascent/descent)* | `TripStatisticsEntity.ascentM`/`descentM` | Unknown, not zero — must be `null`. |
| *(none — V1 never tracked this)* | `TripStatisticsEntity.suspectPointCount`/`rejectedPointCount`/`gapCount` | **Schema tension worth flagging**: F0.7's own rule is "an unknown metric stays null, never zero," but these three columns are non-nullable `Int` today. An import would have to write `0` here, which is not quite true (V1 *did* reject points during capture, it just never recorded how many) — a technically inaccurate default forced by the current schema, not a deliberate claim that zero points were ever rejected. Worth a schema-nullability discussion only if importing actually gets built, not before. |
| `createdAt`/`updatedAt` | `TripEntity.createdAt`/`updatedAt` | Recommend stamping with the *import's* wall-clock time, not V1's original audit timestamps — these fields are V2's own bookkeeping. |
| `customName` | `TripEntity.name` | Direct — same concept, same validation spirit (`REF-001` row 26 already confirmed V2 has equivalent trimming/length validation). |
| `isFavorite` | `TripEntity.isFavorite` | Direct. |
| *(none)* | `TripEntity.motorcycleId`/`routeId` | `null` — V1 has no motorcycle-management or reusable-route concept at all. |
| *(none)* | `TripEntity.notes` | Free for a future importer to stamp with provenance, e.g. "Imported from V1 on 2026-09-20" — recommended, not required. |
| *(none)* | `TripEntity.deletedAt` | `null` at import time; participates normally in `TRS-001` afterward like any other trip. |

---

## 3. TrackPoint → ProcessedTrackPointEntity

| V1 `TrackPoint` field | Maps to | Notes |
|---|---|---|
| `id` (Long, local) | *(dropped)* | V1's own surrogate key has no meaning in V2; V2's table doesn't have one either (composite key). |
| `tripId` | `ProcessedTrackPointEntity.tripId` | Direct (the same id reused from §2). |
| `sequence` | `ProcessedTrackPointEntity.orderIndex` + `sourceSequenceNumber` | Both set to V1's `sequence` — `orderIndex` because it's V2's real ordering key here, `sourceSequenceNumber` as an honest provenance pointer, understood to reference a raw row **that does not exist** (see §1) — an intentional, documented exception to what that field normally means for a native V2 trip. |
| `recordedAt`, `elapsedRealtimeNanos` | *(dropped)* | `ProcessedTrackPointEntity` doesn't carry per-point timestamps at all (it's a geometry cache, not a timeline) — no loss relative to what V2 itself keeps for processed points. |
| `latitude`/`longitude` | `ProcessedTrackPointEntity.latitude`/`longitude` | Direct. |
| `horizontalAccuracyMeters`, `speedMps`, `speedAccuracyMps`, `altitudeMeters`, `verticalAccuracyMeters`, `bearingDegrees`, `bearingAccuracyDegrees`, `source` | *(dropped)* | None of these exist on `ProcessedTrackPointEntity` either — V2 itself doesn't keep per-point accuracy/speed/bearing past the raw layer, so this isn't an import-specific loss. |
| *(none)* | `ProcessedTrackPointEntity.sourceCaptureId` | The synthesized `TripCaptureEntity.id` from §2. |
| *(none)* | `ProcessedTrackPointEntity.pointRole` | `null`, unless a future importer wants to mark first/last explicitly the way V2's own processing might. |

---

## 4. Enum mapping

### `startMode` → `StartSource`

V2's `StartSource` already has a reserved `IMPORTED` value with the exact doc comment *"reserved for a future import feature"* — this design is that future feature.

| V1 `startMode` | Recommended V2 `StartSource` |
|---|---|
| `AUTOMATIC` | `IMPORTED` (not `AUTO`) |
| `MANUAL` | `IMPORTED` (not `MANUAL`) |

**Recommendation:** always `IMPORTED`, never `AUTO`/`MANUAL`, regardless of V1's own value. `AUTO`/`MANUAL` in V2 carry real behavioral meaning elsewhere (e.g. `DET-007`'s forgotten-finish reminder only arms for `MANUAL` captures) that an already-finished imported trip should never trigger. V1's own automatic-vs-manual distinction isn't lost — it's exactly the kind of detail worth preserving in `TripEntity.notes` as provenance text, just not conflated with V2's own `StartSource` semantics.

### `endReason` → `EndSource`

**Open gap, not a silent default:** V2's `EndSource` is `{ AUTO, MANUAL, RECOVERY, ABORTED }` — no `IMPORTED` value, unlike `StartSource`. Two honest options, neither decided here:

| Option | Trade-off |
|---|---|
| A. Map to the closest existing value (table below) | No schema change; loses the "this was imported" signal at the `EndSource` level — but `StartSource.IMPORTED` on the same row already carries that signal, so nothing is silently lost overall. |
| B. Add `EndSource.IMPORTED` for symmetry with `StartSource` | Small, real domain-model change — outside this design-only task's scope; a concrete recommendation to hand off if importing is ever approved. |

Closest-value mapping for Option A:

| V1 `endReason` | Closest V2 `EndSource` |
|---|---|
| `AUTO_STOP` | `AUTO` |
| `MANUAL_STOP` | `MANUAL` |
| `SERVICE_STOPPED` | `RECOVERY` |
| `PROCESS_RECOVERY` | `RECOVERY` |
| `PERMISSION_REVOKED` | `ABORTED` |
| `LOCATION_DISABLED` | `ABORTED` |
| `ERROR` | `ABORTED` |

### `status` → `TripStatus` + `CaptureStatus`

| V1 `status` | V2 `TripEntity.status` | V2 `TripCaptureEntity.status` |
|---|---|---|
| `COMPLETED` | `COMPLETED` | `COMPLETED` |
| `INTERRUPTED` | `COMPLETED` | `ABORTED` — matches `REC-001`'s own established pattern ("an ABORTED capture MAY get a visible partial Trip") |
| `ACTIVE` | *(exclude — see §6)* | *(exclude)* |
| `DISCARDED` | *(exclude by default — see §6)* | *(exclude)* |

---

## 5. Marking imported statistics so they're never confused with a real V2 computation

`TripStatisticsEntity.processingVersion` is a plain ascending `ProcessingVersion(Int)` — V2's own pipeline uses it to mean "these numbers came from processing algorithm version N," implicitly comparable to other trips computed under the same N. An imported trip's numbers came from **V1's own, different algorithm** — comparing them to a native V2 trip under the same nominal `processingVersion` would be misleading (e.g. investigating why two "version 3" trips have wildly different point density would waste time if one of them was never actually processed by V2 at all).

**Recommendation:** reserve a negative sentinel, e.g. `ProcessingVersion(-1)`, meaning "imported from V1, frozen, never regenerable" — distinct from the existing `DetectorVersion(0)`/`LocationProfileVersion(0)`-style placeholders (which mean "no real value decided yet," not "permanently external data"). Any future UI showing a trip's data should treat this sentinel as a reason to hide "recalculate" affordances entirely, not just skip them once.

---

## 6. Product decisions this design surfaces, not resolves

Per `REF-002`'s own acceptance criterion, these are for the project owner, not defaulted silently:

1. **Mandatory or optional?** Should moving to V2 require importing V1 history, or is it opt-in/skippable? Nothing here assumes either answer.
2. **`ACTIVE` V1 trips:** never migrate one — an in-progress trip should keep running in whichever app is actually tracking it. The importer should refuse/skip these, not attempt a partial import.
3. **`DISCARDED` V1 trips:** default recommendation is to skip them (the user already chose to discard them in V1), but this is a recommendation, not a decision — an owner could reasonably want them preserved as `TRASHED` in V2 instead.
4. **One-time action or ongoing?** Nothing here assumes a user could import "the same" V1 trip twice, or that V1 and V2 would ever run side by side long-term needing repeated imports. If that scenario matters, idempotency (e.g. "already imported this V1 id, skip") needs its own design.
5. **`EndSource.IMPORTED`:** add it, or accept the closest-value mapping in §4 — an explicit choice, not made here.

---

## 7. What is permanently unrecoverable, regardless of how the importer is built

- **Raw, unfiltered GPS stream.** V1 never stored it; only its own filtered result exists. No `RawTrackPointEntity` rows, ever, for an imported trip.
- **Rejected/suspect points.** V1 discarded them at capture time without a trace. No `PointAssessmentEntity` rows, ever.
- **Per-point speed/accuracy/bearing/altitude/source.** V1 had these on `TrackPoint`; V2's `ProcessedTrackPointEntity` doesn't carry per-point detail at all (for *any* trip, native or imported) — so geometry survives, per-point telemetry doesn't.
- **Detected stops as discrete entities, location gaps.** V1 has no equivalent concept to populate `TripStopEntity`/`LocationGapEntity` from.
- **Manual pause history.** V1 has never built pause/resume (`REF-001`); `manualPauseDurationMs` is genuinely `0`, not just unknown.
- **Edit/lineage history.** An imported trip starts with no `TripEditOperationEntity`/`TripLineageLinkEntity` rows — same as any freshly-created V2 trip, not a special loss.
- **Real device elapsed-time continuity.** V1's monotonic timestamps cannot be reused on a different boot/device (§2) — only the wall-clock instants and the derived duration survive.

None of this is new information invented for this document — it follows directly from what V1's own schema was never designed to keep, per `REF-001`'s reading of it.

---

## 8. Explicitly out of scope here

- No import code. No Room migration. No UI.
- No execution against the real V1 pilot database — it is never touched, read, or copied by this design work.
- No decision on §6's open questions — they're surfaced for the project owner.
- No `EndSource.IMPORTED` addition — recommended, not implemented.

---

## 9. Traceability

- `docs/00-master/f0-16-v1-reference.md` §4 — the methodology this design follows.
- `docs/00-master/f0-16-v1-parity-checklist.md` (`REF-001`) — the feature-level comparison this data-level design complements.
- `ADR-005`/`ADR-006`/`ADR-014` — the V2 architecture decisions this design respects (Trip/Capture/Part split, raw-vs-processed, independent versioning).
- V1 `docs/data-model.md` §2/§3 — the exact V1 schema this design maps from.
