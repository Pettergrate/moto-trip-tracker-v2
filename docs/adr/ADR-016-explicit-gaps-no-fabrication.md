# ADR-016 — Gaps y reboot son discontinuidades explícitas; no se fabrica trayectoria

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

GPS puede desaparecer en túneles/zonas malas y un reboot rompe el reloj monotónico y la ejecución continua.

## Decision

Cuando no hay evidencia de ubicación válida se registra LocationGap/partial state. No se interpolan posiciones como observadas ni se suma distancia inventada. Tras reboot, una captura anterior no se presenta como continuidad garantizada.

## Rationale

- Evita métricas falsas.
- Hace visibles limitaciones de señal y recovery.
- Protege confianza del usuario.

## Consequences

- La ruta puede mostrar discontinuidades reales.
- El procesamiento debe distinguir distancia observada de intervalos sin evidencia.
- UI puede etiquetar Trip parcial/degradado.

## Alternatives considered

- Línea recta automática entre puntos separados y contarla como distancia real. Rechazado.
- Continuar misma sesión tras reboot como si nada hubiera pasado. Rechazado.

## Reopen / supersede triggers

- Se incorpora map matching/interpolación como estimación explícita y etiquetada; nunca podrá confundirse con evidencia raw.

## Traceability

- F0.5 GPS & Location Research
- F0.10 Reliability & Recovery
- DP-006

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
