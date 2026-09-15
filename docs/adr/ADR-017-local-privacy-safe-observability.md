# ADR-017 — Observabilidad local, estructurada y privacy-safe; sin telemetry automática

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

El detector y recovery deben ser explicables, pero rutas y hábitos de movilidad son sensibles.

## Decision

DiagnosticEvent y snapshots se guardan localmente con reason codes, correlation IDs y versiones, sin coordenadas/notas por defecto. El diagnostic ZIP se genera solo por acción explícita y no se sube automáticamente.

## Rationale

- Permite root-cause sin crear una plataforma de rastreo.
- Alinea diagnóstico con local-first.
- Reduce riesgo de filtración accidental por logs.

## Consequences

- Logcat release es sanitizado.
- Debug Screen reconstruye estado desde repositories; no es source of truth.
- Route data en un export diagnóstico exige opt-in separado.

## Alternatives considered

- Analytics/telemetry remota por defecto. Rechazada en Core.
- Logs de texto libre con objetos completos. Rechazados por privacidad y poca estructura.

## Reopen / supersede triggers

- Se propone telemetry opt-in con propósito claro, minimización, retención y revisión de privacidad independiente.

## Traceability

- F0.11 Privacy & Permissions
- F0.13 Observability & Diagnostics

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
