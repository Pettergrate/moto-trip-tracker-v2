# F0.14 — ADR Baseline
## Moto Trip Tracker V2

**Status:** Closed baseline  
**Version:** 0.1  
**Date:** 2026-09-15

---

## 1. Purpose

F0.14 converts the structural decisions already accepted in F0.1–F0.13 into Architecture Decision Records (ADRs). It does not reopen or redesign those workstreams by default.

An ADR exists so a future implementation agent can answer five questions without guessing:

1. What was decided?
2. Why was it decided?
3. What consequences are accepted?
4. What alternatives were considered?
5. What evidence would justify reopening the decision?

---

## 2. ADR governance

- ADR IDs are permanent.
- **Accepted** means implementation should follow the decision.
- A changed decision creates a new ADR with status **Accepted** and marks the old ADR **Superseded by ADR-XXX**.
- Minor clarifications that do not change the decision may edit the ADR with a dated note.
- Implementation tasks must cite relevant ADR IDs when architecture or behavior is constrained by them.
- Codex/Claude Code must not silently replace an Accepted ADR because another approach is easier.
- A reopen trigger permits review; it does not automatically reverse the ADR.

---

## 3. Accepted baseline

| ID | Decision | Status | File |
|---|---|---|---|
| ADR-001 | V2 es greenfield e independiente de V1 | Accepted | `ADR-001-greenfield-v2.md` |
| ADR-002 | Android nativo con Kotlin y Jetpack Compose | Accepted | `ADR-002-android-native-kotlin-compose.md` |
| ADR-003 | Room/SQLite es la fuente local de verdad | Accepted | `ADR-003-room-source-of-truth.md` |
| ADR-004 | El foreground location service posee el tracking activo | Accepted | `ADR-004-foreground-service-owns-live-tracking.md` |
| ADR-005 | TripCapture raw separado de Trip lógico | Accepted | `ADR-005-capture-vs-logical-trip.md` |
| ADR-006 | Raw Track se preserva; Processed Track es derivado versionado | Accepted | `ADR-006-raw-preserved-processed-reproducible.md` |
| ADR-007 | Detector multi-señal con Activity Recognition como trigger pasivo | Accepted | `ADR-007-multi-signal-trip-detection.md` |
| ADR-008 | Permisos por capacidades y degradación funcional | Accepted | `ADR-008-capability-modes-graceful-degradation.md` |
| ADR-009 | Core V2 es local-first y no requiere backend | Accepted | `ADR-009-local-first-no-backend-core.md` |
| ADR-010 | WorkManager solo para trabajo diferible/persistente | Accepted | `ADR-010-workmanager-deferrable-only.md` |
| ADR-011 | Navigation 3 para navegación Compose-first | Accepted | `ADR-011-navigation3-compose-first.md` |
| ADR-012 | Bootstrap single-module con boundaries explícitos | Accepted | `ADR-012-single-module-bootstrap.md` |
| ADR-013 | Detector y processing desacoplados de tipos Android | Accepted | `ADR-013-domain-decoupled-from-android.md` |
| ADR-014 | Versionado independiente de schema, detector, location y processing | Accepted | `ADR-014-independent-data-algorithm-versioning.md` |
| ADR-015 | Comandos críticos idempotentes y cambios estructurales transaccionales | Accepted | `ADR-015-idempotent-transactional-commands.md` |
| ADR-016 | Gaps y reboot son discontinuidades explícitas; no se fabrica trayectoria | Accepted | `ADR-016-explicit-gaps-no-fabrication.md` |
| ADR-017 | Observabilidad local, estructurada y privacy-safe; sin telemetry automática | Accepted | `ADR-017-local-privacy-safe-observability.md` |
| ADR-018 | Testing determinista + field validation antes de congelar detector | Accepted | `ADR-018-deterministic-tests-and-field-freeze.md` |
| ADR-019 | Mapas son presentación; proveedor aislado y diferido | Accepted | `ADR-019-map-rendering-isolated-provider-deferred.md` |
| ADR-020 | Solo una TripCapture ACTIVE a la vez | Accepted | `ADR-020-single-active-capture-invariant.md` |
| ADR-021 | Proveedor de mapa: MapLibre Native + OpenFreeMap | Accepted | `ADR-021-map-provider-maplibre-openfreemap.md` |
| ADR-022 | Fixes tomados solo con ubicación aproximada: se conservan, no se usan como ruta | Accepted | `ADR-022-approximate-location-fixes-kept-not-used-as-route.md` |

---

## 4. Coverage by workstream

| Source workstream | ADR coverage |
|---|---|
| F0.1 Product Definition | ADR-001, ADR-009 |
| F0.2 Requirements & Scope | ADR-005, ADR-008, ADR-019 |
| F0.3 Trip Detection | ADR-007, ADR-020 |
| F0.4 Android Platform | ADR-004, ADR-007, ADR-008 |
| F0.5 GPS & Location | ADR-006, ADR-016 |
| F0.6 Field Experiments | ADR-018 |
| F0.7 Domain & Data Model | ADR-003, ADR-005, ADR-006, ADR-014, ADR-020 |
| F0.8 System Architecture | ADR-002, ADR-003, ADR-004, ADR-010, ADR-011, ADR-012, ADR-013, ADR-014, ADR-019 |
| F0.9 UX & Navigation | ADR-008, ADR-011, ADR-019 |
| F0.10 Reliability & Recovery | ADR-003, ADR-015, ADR-016, ADR-020 |
| F0.11 Privacy & Permissions | ADR-008, ADR-009, ADR-017 |
| F0.12 Testing | ADR-013, ADR-018 |
| F0.13 Observability | ADR-014, ADR-017, ADR-018 |

---

## 5. Deferred decisions that are intentionally NOT ADRs yet

The following remain open because Phase 0 has not produced enough evidence or because the decision belongs to a later milestone:

- final detector thresholds and confidence/scoring model;
- final location sampling/min-distance/batching production profile;
- exact elevation smoothing/ascent algorithm;
- final diagnostic retention limits after measurement;
- cloud sync/backend architecture if ever introduced;
- multi-module extraction points;
- concrete backup file schema and encryption policy;
- future route-matching algorithm;
- maintenance/fuel domain architecture.

Deferring these is deliberate. F0.14 must not create false certainty.

---

## 6. ADR use in implementation tasks

Example:

```text
Task: TRK-001 Tracking foreground service
Implements: FR-REC-008, FR-NOT-001
Must follow: ADR-003, ADR-004, ADR-015, ADR-020
Validation: TST-FGS-001..006
```

Architecture-changing pull requests or agent tasks should identify the ADRs affected. If a task conflicts with an Accepted ADR, the conflict must be resolved before coding rather than hidden in implementation.

---

## 7. F0.14 closure criteria

- [x] Structural decisions from F0.1–F0.13 have explicit ADR IDs.
- [x] Each ADR has context, decision, rationale, consequences, alternatives and reopen triggers.
- [x] Each ADR has status and traceability.
- [x] Open research decisions remain explicitly deferred rather than invented.
- [x] Governance for superseding/reopening ADRs is defined.
- [x] F0.15 can build a roadmap that cites ADR constraints.

### Decision v0.14

> **F0.14 queda CERRADO como ADR baseline v0.1.**

Next workstream: **F0.15 — V2 Roadmap**.
