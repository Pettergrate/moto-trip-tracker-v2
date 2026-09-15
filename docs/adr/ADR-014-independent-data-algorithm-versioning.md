# ADR-014 — Versionado independiente de schema, detector, location y processing

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

App version no identifica qué algoritmo o perfil produjo una captura/estadística; un mismo binario puede reprocesar datos con reglas nuevas.

## Decision

Se mantienen identificadores independientes: schemaVersion, detectorVersion/profileVersion, locationProfileVersion, processingVersion y backupSchemaVersion cuando aplique.

## Rationale

- Reproducibilidad.
- Diagnóstico y comparación de experimentos.
- Migrar schema no implica cambiar algoritmo, y viceversa.

## Consequences

- Las entidades/eventos relevantes deben registrar versiones asociadas.
- Reprocessing puede invalidar caches por processingVersion.
- Diagnostic bundles incluyen estas versiones.

## Alternatives considered

- Usar solo appVersion. Rechazado por ambigüedad.

## Reopen / supersede triggers

- Se demuestra que algún version dimension no aporta valor y puede derivarse inequívocamente de otra, sin perder reproducibilidad.

## Traceability

- F0.7 Domain & Data Model
- F0.8 System Architecture
- F0.13 Observability & Diagnostics

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
