# ADR-003 — Room/SQLite es la fuente local de verdad

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

Un Trip activo debe sobrevivir cierres de UI, process death y reinicios de componentes. Los datos de ruta y lineage no pueden depender de memoria transitoria.

## Decision

Room sobre SQLite será la fuente local de verdad para TripCapture, Trips, RawTrackPoints, lineage y estado persistente relevante. UI, ViewModels, services y workers reconstruyen su estado desde persistencia.

## Rationale

- Durabilidad transaccional y consultas estructuradas.
- Soporte de migraciones y pruebas.
- Evita fuentes de verdad duplicadas entre UI y service.

## Consequences

- El esquema requiere versionado y migraciones desde el inicio.
- Los comandos críticos deben persistir su resultado de forma atómica.
- DataStore queda limitado a settings/preferencias, no a datos de Trip.

## Alternatives considered

- Estado en memoria + archivos ad hoc. Rechazado por recovery y consistencia.
- Base remota como source of truth. Rechazada por offline-first y privacidad.

## Reopen / supersede triggers

- Room demuestra un bloqueo técnico medible para el volumen real de TrackPoints que no puede resolverse con índices, batching o diseño de tablas.

## Traceability

- F0.7 Domain & Data Model
- F0.8 System Architecture
- F0.10 Reliability & Recovery
- NFR-REL-001

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
