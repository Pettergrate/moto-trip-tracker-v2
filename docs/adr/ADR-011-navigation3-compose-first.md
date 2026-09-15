# ADR-011 — Navigation 3 para navegación Compose-first

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

La UI es greenfield Compose-first y necesita navegación adaptable, deep links y estado de Trip activo independiente de las pestañas principales.

## Decision

Navigation 3 es el baseline de navegación. Destinos Core: Home, History y Favorites; Active Trip es un destino especial persistente, no una pestaña.

## Rationale

- Alineación con UI Compose moderna.
- Modelo claro para back stack y adaptive navigation.
- Evita convertir el Trip activo en una sección aislada del resto del flujo.

## Consequences

- La versión estable se revalida al bootstrap.
- Settings permanece secundario.
- La arquitectura de navegación debe ser testeada con restore/deep links.

## Alternatives considered

- Navigation 2 por inercia. No elegido para greenfield Compose-first.
- Navegación manual ad hoc. Rechazada por estado/back/deep links.

## Reopen / supersede triggers

- Navigation 3 presenta regresiones graves en la versión estable disponible al bootstrap o cambia su soporte oficial.

## Traceability

- F0.8 System Architecture
- F0.9 UX & Navigation

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
