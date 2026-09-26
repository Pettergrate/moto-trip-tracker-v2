# REF-001 — V1 Feature Parity Checklist

**Status:** Complete, first pass (2026-09-20)
**Scope:** F0.16 §3 methodology, options 2 (feature parity) only — no V1 architecture/code review, no lessons-learned retrospective (explicitly declined by the project owner 2026-09-19).
**Sources read:** V1 `README.md`, `docs/product-requirements.md`, `docs/data-model.md` (§8), `docs/roadmap.md` (full "Estado de etapas" table + Etapa 8 blocks + Etapa 9/10 planned scope). Read-only — no V1 code touched, no V1 install touched.

---

## 1. How to read this

- **V1 status**: what V1's own roadmap says, verbatim in spirit (not copied text) — "Completa", "Implementado, pendiente de validación física", or "solo documentado, sin código" (documented only, no code — V1 hasn't built it either).
- **Classification**:
  - **Covered** — V2 already ships this (cite the task/commit).
  - **Planned** — a V2 backlog task exists for it.
  - **Gap** — V1 has it (or a self-documented placeholder admits V2 doesn't), V2's backlog doesn't currently name a task for it.
  - **V2 ahead** — V2 already has this and V1 doesn't (V1 has it only as an unbuilt idea, or not at all).
  - **N/A (both undecided)** — neither project has built or fully designed this; informational only.

---

## 2. Core recording (V1 FR-001..FR-013, product-requirements.md)

| # | V1 feature | V1 status | V2 status | Classification |
|---|---|---|---|---|
| 1 | FR-001 enable/disable automatic mode, manual fallback explained | Complete | `AutoTrackingPreferences` + `CapabilityResolver`'s `MANUAL` mode; Home shows "Auto Tracking is off — start your trips manually" | **Covered** |
| 2 | FR-002 low-power candidate-motion signal (Activity Recognition) | Complete (Etapa 4) | `DET-001` Activity Recognition transitions | **Covered** |
| 3 | FR-003 confirm start via speed+displacement+time+accuracy | Complete (Etapa 4/5) | `DET-002` Candidate Start engine | **Covered** |
| 4 | FR-004 manual start | Complete | `TRK-001` | **Covered** |
| 5 | FR-005 background recording (point/speed/accuracy/time/altitude via FGS+notification) | Complete | `TRK-001`/`TRK-002`/`NOT-001` | **Covered** |
| 6 | FR-006 incremental persistence (not only at finish) | Complete | `TRK-002` (`RawTrackPointDao.insert` per sample, append-only per ADR-006) | **Covered** |
| 7 | FR-007 moving/stopped time classification, deterministic rules | Complete | `PRC-002` core distance/time/speed metrics | **Covered** |
| 8 | FR-008 automatic finish after ~5 min stopped in a bounded zone | Complete (Etapa 5, field-validated 2026-09-03) | `DET-003` Candidate Stop engine + `DET-004` temporary-stop hysteresis | **Covered** |
| 9 | FR-009 manual finish, from app and from the active notification | Complete | `TRK-004` + `NOT-001` | **Covered** |
| 10 | FR-010 history: date/distance/duration/moving/stopped/avg/max speed | Complete | `HIS-001` | **Covered** |
| 11 | FR-011 route detail with per-point/segment speed | Complete (Etapa 6D) | `HIS-001`/`MAP-001` (route drawn; per-point speed-on-tap not yet confirmed built) | **Planned/partial** — verify against `MET-001`/`MAP-002` scope |
| 12 | FR-012 offline capture/calc/persistence, map availability never gates route preservation | Complete | `TRK-002`/`PRC-*` have no network dependency; `MAP-001`'s `TripRouteMap` explicitly separate from capture (ADR-019) | **Covered** |
| 13 | FR-013 process-recreation recovery, no duplicate session, explicit close reason | Complete (Etapa 7A) | `REC-001` | **Covered** |

Every core MVP requirement V1 has is already covered in V2. No gaps in this section.

---

## 3. Automatic detection refinements (beyond bare FR-002/003/008)

| # | V1 feature | V1 status | V2 status | Classification |
|---|---|---|---|---|
| 14 | Manual-pause-aware detection (a manual pause is never confused with automatic stop-verification or an abandoned trip) | Implied by Etapa 5's design, not a named sub-feature | `DET-005` manual-pause warning logic — named, tested | **V2 ahead** (more explicit) |
| 15 | Duplicate-start suppression after a just-finished trip | Not documented as a distinct feature in V1 | `DET-006` | **V2 ahead** |
| 16 | Forgotten-finish reminder for a manual capture left running | Not documented in V1 | `DET-007` | **V2 ahead** |
| 17 | Diagnostic categorization of *why* a start attempt timed out (late AR delivery vs. gate lock vs. rejected GPS vs. buffer state) — 7G.1/7G.2 | Implemented, installed incrementally in the pilot, awaiting 3-5 field rides | `DIA-001` + `DET-002`'s own diagnostic stamping exist; exact category granularity not verified 1:1 against V1's 7G.1/7G.2 categories | **Planned/needs verification** — not a blocking gap, revisit once `EXP-004`'s detector campaign runs |
| 18 | Reanchoring a stale `IN_VEHICLE` event to processing time + median-based buffer speed (7G.3) | Implemented, awaiting first field round | V2's `DET-002` design not yet compared line-for-line against this specific correction | **Deferred (owner decision, 2026-09-20)** — revisit during `EXP-004`; likely moot if V2's own design never had the bug it fixes. |

---

## 4. History, detail & map (V1 Etapa 6, 8A, 8B)

| # | V1 feature | V1 status | V2 status | Classification |
|---|---|---|---|---|
| 19 | History list with reactive queries | Complete (6A) | `HIS-001` | **Covered** |
| 20 | Trip detail: full stats, offline local route (Canvas), start/end markers | Complete (6C.1) | `HIS-001` | **Covered** |
| 21 | Optional remote base map (MapLibre + OpenFreeMap), offline fallback preserved | Complete (6C.2), field-validated on Android 16 | `MAP-001` (same provider choice, independently arrived at — see ADR-021) | **Covered** |
| 22 | Compact map with remembered consent, no map traffic before first opt-in, one MapLibre instance at a time (8A) | Implemented, pending physical/visual validation | `MAP-001`'s `TripRouteMap` always renders (no separate compact/expanded consent gate); consent-to-show-remote-map is not a V2 concept yet | **Declined (owner decision, 2026-09-20)** — V2 keeps its current always-render behavior; not replicating V1's opt-in gate. |
| 23 | Route thumbnail in each history card, bounded single-query sampling (8B) | Implemented and field-validated | Not built; `HIS-001`'s `RecentTripRow`/History list is text-only | **Planned (owner decision, 2026-09-20)** — added to `HIS-002`'s scope. |
| 24 | Selected-point contrast/accessibility marker on the map (8A) | Implemented, pending device validation | Not applicable yet — V2 has no point-selection-on-map interaction at all | **Planned (owner decision, 2026-09-20)** — added to `MAP-002`'s scope. |
| 25 | Stop markers on the route map | Not found as a distinct V1 feature | Backlog names `MAP-002` "stops/markers" | **N/A (both undecided)** — V2 is already ahead in *intent* even though neither has built it. |

---

## 5. Naming, favorites, organization & deletion (V1 8C, 8D, 8E)

| # | V1 feature | V1 status | V2 status | Classification |
|---|---|---|---|---|
| 26 | Custom trip name, validated/trimmed, ≤60 chars, atomic single-column write | Implemented, pending device validation | `HIS-001` (`TripEntity.name`, `TripDetailViewModel.onRename`, rename dialog) | **Covered** |
| 27 | Favorite flag, atomic write, visible in history/detail | Implemented, pending device validation | `TripEntity.isFavorite` + star icon exist (since `HIS-001`); no UI action to actually toggle it yet | **Planned** — `FAV-001` |
| 28 | "Mis rutas" tabs: all trips / favorites-only, reactive filter, no extra query | Implemented, tested on-device (8D) | `Destination.Favorites` exists only as `FavoritesPlaceholderScreen` (empty) | **Planned** — `FAV-001` |
| 29 | Safe trip deletion: confirmation dialog, atomic transaction, cascade-delete points, generic failure message, never optimistic | Implemented, tested on-device (8E) — **direct delete, no trash/restore step** | Not built | **Planned** — `TRS-001`, and deliberately **more cautious than V1**: V2's own spec asks for "non-immediate destructive delete with restore path", which V1 never implemented (V1 deletes immediately after the confirm dialog, no undo). Worth keeping V2's stricter version rather than matching V1's simpler one. |
| 30 | Trip merge (combine consecutive trips) | **Documented only, no production code** — V1 itself has not built this. Its own design write-up (selection/preview, sequence renumbering under a unique `(trip_id, sequence)` constraint, temporal-gap tolerance, statistics recompute, name/favorite inheritance, status/reason inheritance, all-or-nothing transaction) is a real, useful reference | `EDT-001` Merge Trips (planned, not started) | **Planned** — recommend reading V1's own unresolved design questions (roadmap.md "Después del MVP" item 8, block 8F) before designing `EDT-001`; they're free, already-thought-through open questions, not V1 code to copy. |
| 31 | Trip split | Not present in V1 at all, not even documented | `EDT-002` Split Trip (planned) | **V2 ahead** |
| 32 | Boundary correction | Not present in V1 | `EDT-003` (planned) | **V2 ahead** |
| 33 | Recalculation/invalidation of derived stats after an edit, with an explicit processing version | V1 has no formal versioning concept (`data-model.md`: "Recalcular estadísticas durante diagnóstico para detectar divergencias" — ad hoc, not versioned) | `EDT-004` (planned) + V2 already has `ADR-014`'s `processingVersion` in production (`PRC-*`) | **V2 ahead** (more rigorous foundation already in place) |
| 34 | Search/filter/sort beyond favorites-only | Not present in V1 | `HIS-002` (planned) | **V2 ahead** (in intent; not built yet either) |

---

## 6. Reliability, permissions & recovery (V1 Etapa 7A/7B)

| # | V1 feature | V1 status | V2 status | Classification |
|---|---|---|---|---|
| 35 | Same-process-recreation recovery, closes as `INTERRUPTED` using last persisted evidence, never invents duration | Complete (7A) | `REC-001` (closes as `ABORTED` using last persisted evidence) — same philosophy | **Covered** |
| 36 | Reboot never auto-resumes GPS; prior trip stays closed, next trip starts fresh | Complete (7A) | `REC-001` | **Covered** |
| 37 | Mid-capture permission revocation or location-services-disabled closes the trip explicitly (`INTERRUPTED`/`PERMISSION_REVOKED`, `INTERRUPTED`/`LOCATION_DISABLED`); losing only background location or only Activity Recognition never ends a manual trip; losing only Activity Recognition never ends an already-confirmed automatic trip | Complete (7B) | `REC-002` (permission removed -> degraded restart instead of a crash), `REC-005` "GPS gap and location-degraded handling" (done 2026-09-25); `PERM-002`/`PERM-003` (W4, not started) | **Partly closed, and a deliberate divergence from V1.** V2 does not close the trip when location services go off or fixes stop: per `reliability-recovery.md` §12.1/§13 the capture stays `ACTIVE`, the gap is recorded (`LOCATION_GAP_STARTED`/`ENDED`, reason `NO_FIX` or `LOCATION_SERVICES_OFF`) and the rider is told in the notification and the Active Trip screen. Still open: an approximate-only downgrade is not flagged (`PERM-003`), and the background-location/Activity-Recognition rules are unchanged (`PERM-002`). |

---

## 7. Branding, field validation & diagnostics (V1 7C, 7D, 7G)

| # | V1 feature | V1 status | V2 status | Classification |
|---|---|---|---|---|
| 38 | Custom app icon/branding (adaptive + monochrome + splash, replacing the default template robot) | Complete (7C), visually validated | `ic_launcher_foreground.xml` itself says: *"Placeholder mark; not a final product icon. Revisit with real branding later."* — V2 has explicitly self-documented this as unfinished | **Deferred (owner decision, 2026-09-20)** — not declined, just not scheduled to a task or wave yet. |
| 39 | Doze/Battery-Saver/lock-screen field validation protocol | Complete audit (7D.1) + partial physical validation (7D.2, Doze forced case done, Battery Saver isolated case still pending in V1 itself) | No V2 task specifically names Doze/lock-screen validation; `EXP-006` "Battery campaign" is the closest, broader in scope | **Gap (loosely tracked)** — recommend explicitly folding a Doze/lock-screen scenario into `EXP-006`'s design rather than opening a new task. |
| 40 | Privacy-scoped diagnostic logging (no coordinates/routes/speeds/timestamps/exception text) for auto-start reliability | Complete (7G.1) | `DIA-001` structured diagnostic event foundation exists; privacy-scoping of its actual field contents not yet audited against this specific bar | **Planned/needs verification** — candidate: `PRV-001` (W4, privacy/backup enforcement) should explicitly check `DiagnosticEvent`'s fields against this same bar. |

---

## 8. What V1 itself hasn't built yet (Etapa 9/10) — informational only

Neither "Gap" nor "Covered": these are V1's own **unbuilt plans**, useful only as a signal of where V1's owner was already heading, not as evidence V2 is behind.

- **Etapa 9 (visual rearrange + live tracking on the active screen)** — V1's own plan explicitly calls for *"una estética agresiva orientada a motociclistas"* (an aggressive aesthetic aimed at motorcyclists) across Home/Active/Map/Detail/History. **V2 already did this independently** (today's black-and-orange theme + sharp shapes, project-owner request, unrelated to V1's plan) — a real, if coincidental, confirmation that both projects converged on the same instinct.
- **Etapa 10 (manual pause/resume)** — V1 has **not built this yet** (only a design outline: pause distinct from finish, available from notification too, no distance/moving-time accrual while paused, explicit interaction with auto-stop-verification and process recovery). **V2 already has this fully built and shipped** (`TRK-003`, plus `NOT-001`'s notification pause/resume actions, plus `DET-005`'s manual-pause-aware detection). **V2 is ahead of V1 here.**

---

## 9. Gaps requiring an explicit owner decision

Per REF-001's own acceptance criterion, every Gap needs either a task ID or an explicit decision to exclude. **Resolved by the project owner, 2026-09-20:**

1. **Map-consent gating before any remote-map network traffic** (row 22) — **Declined.** V2 keeps its current always-render `TripRouteMap` behavior (ADR-019); V1's stricter opt-in-before-network-traffic flow is not being replicated.
2. **Route thumbnail in history cards** (row 23) — **Approved.** Added to `HIS-002`'s scope.
3. **Selected-point map marker/accessibility** (row 24) — **Approved.** Added to `MAP-002`'s scope.
4. **Custom app icon/branding** (row 38) — **Deferred**, not declined. No task or wave assigned yet; revisit later.
5. **7G.3's stale-`IN_VEHICLE`-reanchor correction** (row 18) — **Deferred to `EXP-004`**, per the recommendation above (likely moot).

---

## 10. Traceability

- `docs/00-master/f0-16-v1-reference.md` §3 — the methodology this checklist follows.
- `ADR-001` clarifying note (2026-09-19) — the authorization to read V1 for this purpose.
- `docs/05-roadmap/phase1-backlog.md` — `REF-001`'s own acceptance criteria.
