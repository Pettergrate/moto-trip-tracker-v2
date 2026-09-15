# ADR-008 — Permisos por capacidades y degradación funcional

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

Full Auto requiere permisos sensibles y capacidades del sistema que el usuario puede negar o revocar.

## Decision

El producto expone modos de capacidad FULL AUTO, ASSISTED AUTO, MANUAL y LOCATION DEGRADED. Permisos se solicitan progresivamente en contexto; negar permisos opcionales no bloquea toda la app.

## Rationale

- Respeta least privilege y políticas Android/Play.
- Mantiene utilidad aunque no se otorgue background location.
- Hace el estado comprensible sin mostrar una lista técnica de permisos.

## Consequences

- CapabilityResolver debe ser determinista y testeado.
- La UX debe explicar qué falta y qué sigue funcionando.
- Full Auto puede degradarse dinámicamente durante un Trip.

## Alternatives considered

- Pedir todos los permisos en onboarding. Rechazado por UX/política.
- Bloquear uso sin background location. Rechazado porque Manual sigue siendo válido.

## Reopen / supersede triggers

- Cambian requisitos Android/Play o se elimina Full Auto del alcance.

## Traceability

- F0.4 Android Platform Research
- F0.9 UX & Navigation
- F0.11 Privacy & Permissions

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
