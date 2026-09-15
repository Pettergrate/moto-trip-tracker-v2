# ADR-012 — Bootstrap single-module con boundaries explícitos

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

Modularizar demasiado pronto aumenta configuración y ralentiza iteración; no modularizar responsabilidades aumenta acoplamiento.

## Decision

Fase 1 inicia con un único módulo :app organizado por packages/capas y contracts claros. Se crea multimódulo solo cuando aparezcan triggers medibles.

## Rationale

- Reduce complejidad de build al inicio.
- Permite evolucionar arquitectura sin coste prematuro.
- Los boundaries lógicos siguen siendo testeables aunque no sean Gradle modules.

## Consequences

- No se permite usar “single module” como excusa para acoplar UI, platform y domain.
- Se documentan triggers de extracción futura.

## Alternatives considered

- Multimódulo completo desde FND-001. Rechazado como optimización prematura.
- Sin boundaries internos. Rechazado por testabilidad/mantenibilidad.

## Reopen / supersede triggers

- Build times, ownership, feature isolation o dependencias justifican módulos separados con evidencia concreta.

## Traceability

- F0.8 System Architecture
- NFR-MNT-001

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
