# ADR-007 — Detector multi-señal con Activity Recognition como trigger pasivo

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

Una regla única de velocidad/distancia falla con ruido GPS, caminatas, semáforos y congestión. Android tampoco expone una clase MOTORCYCLE confiable.

## Decision

Activity Recognition Transition API será trigger pasivo principal para despertar validación. El inicio/final real lo decide una máquina de estados multi-señal con confirmación temporal, histéresis y evidencia GPS/contextual.

## Rationale

- Reduce consumo frente a GPS preciso permanente.
- Evita que IN_VEHICLE se interprete como motocicleta por sí solo.
- Permite tolerar transitorios y registrar razones de decisión.

## Consequences

- Los thresholds finales son experimentales y no quedan congelados en Fase 0.
- El detector debe ser testeable con replay.
- Se requiere fallback manual y degradación por permisos/capacidades.

## Alternatives considered

- Velocidad > X como único disparador. Rechazado.
- Activity label como decisión final. Rechazado por ambigüedad de vehículo.

## Reopen / supersede triggers

- Field tests muestran que Activity Recognition no aporta señal útil en los dispositivos objetivo o Android depreca la API.

## Traceability

- F0.3 Trip Detection Specification
- F0.4 Android Platform Research
- F0.6 Field Experiment Design

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
