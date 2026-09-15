# ADR-020 — Solo una TripCapture ACTIVE a la vez

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

Dos capturas simultáneas generarían puntos duplicados, notificaciones inconsistentes y ambigüedad sobre ownership del service.

## Decision

El dominio y la persistencia mantienen la invariante de máximo una TripCapture ACTIVE por instalación/perfil activo. Start automático/manual debe revalidarla antes de crear una nueva captura.

## Rationale

- Simplifica ownership del tracking.
- Previene duplicación de datos.
- Hace recovery y comandos de notificación deterministas.

## Consequences

- Start duplicado debe ser idempotente/suprimido.
- Room debe poder verificar/forzar la invariante de forma segura.
- Merge ocurre después, a nivel lógico; no requiere capturas concurrentes.

## Alternatives considered

- Permitir capturas paralelas. Rechazado porque no existe caso de uso Core que lo justifique.

## Reopen / supersede triggers

- Aparece un requisito explícito de múltiples sensores/dispositivos capturando simultáneamente y se rediseña ownership.

## Traceability

- F0.3 Trip Detection Specification
- F0.7 Domain & Data Model
- F0.10 Reliability & Recovery
- TST-DB-002

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
