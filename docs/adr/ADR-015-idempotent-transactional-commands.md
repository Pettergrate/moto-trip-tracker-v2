# ADR-015 — Comandos críticos idempotentes y cambios estructurales transaccionales

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

Notificaciones, process recreation, retries y doble toque pueden repetir comandos. Finish/Merge/Split no pueden dejar estados parciales.

## Decision

Start/Finish/Pause/Resume y workers críticos deben tolerar reintentos. Finish/Merge/Split/Boundary Edit actualizan estructura dentro de transacciones, revalidando invariantes antes del commit.

## Rationale

- Reduce corrupción por duplicados o interrupciones.
- Permite recovery seguro después de fallos.
- Simplifica pruebas de consistencia.

## Consequences

- Cada operación requiere precondiciones y resultado explícito.
- Derived processing se programa después del commit estructural.
- No se confía en que UI impida dobles acciones.

## Alternatives considered

- Confiar en debounce de UI. Rechazado porque no cubre intents/retries/process death.
- Mutaciones parciales sin transacción. Rechazadas.

## Reopen / supersede triggers

- Solo si la capa de persistencia elegida cambia y ofrece atomicidad/idempotencia con otra semántica documentada.

## Traceability

- F0.7 Domain & Data Model
- F0.10 Reliability & Recovery
- F0.12 Testing Strategy

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
