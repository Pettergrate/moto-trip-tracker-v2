# ADR-001 — V2 es greenfield e independiente de V1

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

V1 acumuló problemas estructurales y de fiabilidad. Heredar su arquitectura o código por defecto trasladaría deuda técnica a V2 antes de validar el nuevo diseño.

## Decision

Moto Trip Tracker V2 se construye como proyecto nuevo desde sus propios requisitos, investigación y decisiones arquitectónicas. Por decisión del propietario del 2026-09-15, V1 queda fuera del alcance y se elimina F0.16. No se requiere revisar ni replicar la versión anterior.

## Rationale

- Permite que requisitos, dominio, persistencia y recovery se diseñen sin restricciones heredadas.
- Reduce el riesgo de reintroducir fallos que precisamente motivaron V2.
- Mantiene el diseño centrado en los contratos propios de V2.

## Consequences

- No existe migración automática de arquitectura o código de V1.
- No se inspecciona ni reutiliza V1 dentro del alcance vigente.
- F0.17 evalúa la preparación usando la documentación de V2; la ausencia de F0.16 no es un bloqueo.

## Alternatives considered

- Refactorizar V1 incrementalmente. Rechazado por arrastre de deuda y ambigüedad de responsabilidades.
- Fork de V1 y reemplazo gradual. Rechazado porque mantiene dependencias y supuestos no validados.

## Reopen / supersede triggers

- El propietario solicita explícitamente reconsiderar el alcance respecto de V1. Cualquier cambio requiere revisión documentada de este ADR antes de reutilizar material anterior.

## Traceability

- F0.1 Product Definition
- F0.7 Domain & Data Model
- F0.17 Phase 1 Readiness Review
- Decisión del propietario del 2026-09-15: eliminar F0.16 y mantener V2 independiente de V1.

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.

### Clarifying note, 2026-09-19

Per this ADR's own reopen trigger ("el propietario solicita explícitamente reconsiderar el alcance respecto de V1"), the project owner narrowly reopened the "no se inspecciona ni reutiliza V1" clause — **not** the core decision. Scope: F0.16 is reinstated for exactly two purposes, a V1-vs-V2 feature parity checklist and V1-to-V2 data migration planning (`docs/00-master/f0-16-v1-reference.md`). V1's architecture and code are still not inspected for adoption into V2, and V2 remains greenfield: this note authorizes reading V1's product surface and data model for comparison/planning, not reusing its implementation.
