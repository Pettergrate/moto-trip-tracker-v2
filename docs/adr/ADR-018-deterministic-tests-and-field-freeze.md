# ADR-018 — Testing determinista + field validation antes de congelar detector

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

El detector necesita cobertura rápida de regresión, pero sus parámetros finales dependen del mundo real y consumo de batería.

## Decision

Lógica crítica se prueba con replay, FakeClock, fake capabilities y fixtures. Los thresholds/perfiles de producción no se congelan hasta superar el gate físico EXP-008/G4.

## Rationale

- Evita salir en moto por cada cambio.
- Evita sobreajustar parámetros a intuición o a una sola ruta.
- Separa corrección lógica de validación física.

## Consequences

- El Diagnostic Harness es parte temprana de Fase 1.
- Bugs reproducibles se convierten en fixtures cuando sea posible.
- Un build puede pasar tests automatizados y aun no estar listo para congelar detector.

## Alternatives considered

- Solo pruebas físicas. Rechazado por lentitud y baja reproducibilidad.
- Solo tests sintéticos. Rechazado porque no miden sensores/batería real.

## Reopen / supersede triggers

- Cambian objetivos del detector o se obtiene una fuente de datos de campo suficientemente representativa que sustituya parte del protocolo físico.

## Traceability

- F0.6 Field Experiment Design
- F0.12 Testing Strategy
- F0.13 Observability & Diagnostics

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
