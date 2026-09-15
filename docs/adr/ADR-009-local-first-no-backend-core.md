# ADR-009 — Core V2 es local-first y no requiere backend

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

El caso de uso principal debe funcionar sin Internet y los datos de rutas son sensibles.

## Decision

Core V2 no requiere cuenta, backend, cloud sync, analytics externo ni publicidad. Recording, history, processing y diagnostics funcionan localmente.

## Rationale

- Fiabilidad offline.
- Menor superficie de privacidad y seguridad.
- Reduce dependencias operativas durante la fase inicial.

## Consequences

- Backup/sync remoto quedan para una decisión futura separada.
- Map tiles online pueden fallar sin afectar tracking.
- No existe telemetry automática del uso del usuario.

## Alternatives considered

- Backend obligatorio. Rechazado por dependencia de red y privacidad.
- Analytics de terceros desde bootstrap. Rechazado porque no es necesario para validar Core.

## Reopen / supersede triggers

- Se aprueba sync multi-device/cloud como requisito P1/P2 con modelo de privacidad y seguridad específico.

## Traceability

- F0.1 Product Definition
- F0.8 System Architecture
- F0.11 Privacy & Permissions
- FR-REC-004

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
