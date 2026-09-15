# ADR-005 — TripCapture raw separado de Trip lógico

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

El usuario necesita merge, split, boundary correction y undo sin destruir la evidencia GPS original.

## Decision

TripCapture representa una sesión física de captura. Trip representa una unidad lógica de biblioteca compuesta mediante TripPart sobre uno o más rangos de captura.

## Rationale

- Permite edición no destructiva.
- Conserva provenance y facilita reprocessing.
- Merge/split dejan de requerir copiar o reescribir RawTrackPoints.

## Consequences

- El modelo de datos incluye lineage/superseded states.
- Las métricas del Trip se recalculan desde sus partes.
- La UI nunca asume relación 1:1 Trip ↔ Capture.

## Alternatives considered

- Trip y captura como la misma entidad. Rechazado porque vuelve destructivos merge/split.
- Duplicar puntos al editar. Rechazado por tamaño, inconsistencia y pérdida de provenance.

## Reopen / supersede triggers

- Solo si merge/split/boundary edit se eliminan del producto o un modelo alternativo demuestra igual trazabilidad con menos complejidad.

## Traceability

- F0.2 FR-EDT-001..006
- F0.7 Domain & Data Model
- F0.10 Reliability & Recovery

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
