# ADR-019 — Mapas son presentación; proveedor aislado y diferido

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

El mapa mejora review del Trip, pero el tracking no debe depender de Internet, tiles o de un proveedor concreto.

## Decision

El renderer de mapas consume Processed Track detrás de un boundary de UI. El proveedor de mapas se decide más adelante y su fallo no puede afectar captura, persistencia ni estadísticas.

## Rationale

- Mantiene offline recording independiente.
- Evita lock-in prematuro antes de evaluar costes/licencias/offline.
- Permite que Trip Detail siga siendo útil sin mapa.

## Consequences

- Core tracking no importa SDK de mapas.
- El proveedor puede cambiar sin tocar domain/persistence.
- F0.15 puede dejar la selección como tarea acotada.

## Alternatives considered

- Elegir proveedor en F0.8 por conveniencia. Diferido por falta de necesidad.
- Usar polyline del mapa como source of truth. Rechazado.

## Reopen / supersede triggers

- Se selecciona formalmente proveedor tras evaluar licencias, coste, Compose support y comportamiento offline; se crea ADR específico que supersede solo la parte de proveedor.

## Traceability

- F0.2 FR-MAP-006
- F0.8 System Architecture
- F0.9 UX & Navigation

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.

### Clarifying note (2026-09-18, `MAP-001`)

The provider is no longer undecided: `ADR-021` selects MapLibre Native + OpenFreeMap, exactly via the evaluation this ADR's own reopen trigger called for. This note does not supersede this ADR - the isolation/presentation-adapter decision above is unchanged and still governs; only the "provider TBD" state is now resolved, in `ADR-021` specifically, per §2's "minor clarification" provision rather than a full supersession.
