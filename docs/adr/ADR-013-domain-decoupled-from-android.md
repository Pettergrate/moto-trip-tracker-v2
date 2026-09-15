# ADR-013 — Detector y processing desacoplados de tipos Android

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

La lógica crítica necesita replay determinista, fake clocks y pruebas locales sin dispositivo.

## Decision

State machine, evidence evaluation, GPS processing y estadísticas operan sobre modelos propios del dominio. Adaptadores convierten Location/ActivityRecognition/Android APIs a esos modelos.

## Rationale

- Permite unit tests rápidos y deterministas.
- Reduce dependencia de framework en reglas de negocio.
- Facilita replay de sesiones y regresiones.

## Consequences

- Se necesita mapping explícito platform → domain.
- Android types no atraviesan boundaries de dominio salvo wrappers deliberados.

## Alternatives considered

- Usar android.location.Location directamente en todo el dominio. Rechazado por acoplamiento/testability.

## Reopen / supersede triggers

- Una API específica obliga técnicamente a usar tipos Android en un boundary estrecho; el cambio debe limitarse al adaptador.

## Traceability

- F0.8 System Architecture
- F0.12 Testing Strategy
- NFR-TST-001

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
