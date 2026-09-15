# ADR-002 — Android nativo con Kotlin y Jetpack Compose

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

El producto depende intensamente de APIs Android de background execution, Activity Recognition, foreground services, permisos, ubicación, WorkManager y recovery.

## Decision

Core V2 se implementará como aplicación Android nativa en Kotlin con Jetpack Compose/Material 3 y single-activity.

## Rationale

- Acceso directo y actualizado a capacidades Android críticas.
- Menor fricción para foreground services, permisos y lifecycle que una capa cross-platform.
- Alineación con arquitectura y tooling oficial de Android.

## Consequences

- Flutter, React Native y KMP no forman parte del Core V2.
- Las decisiones de UI y navegación pueden optimizarse para Compose.
- Una futura expansión multiplataforma requeriría un ADR nuevo.

## Alternatives considered

- Flutter/React Native. Rechazados para Core por mayor complejidad alrededor de servicios/background y por no aportar valor inmediato.
- KMP. Diferido; no existe requisito de iOS en Core.

## Reopen / supersede triggers

- Se aprueba oficialmente otra plataforma o existe evidencia fuerte de que compartir lógica compensa el coste sin degradar integración Android.

## Traceability

- F0.4 Android Platform Research
- F0.8 System Architecture
- NFR-CMP-002

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
