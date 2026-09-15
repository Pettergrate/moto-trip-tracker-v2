# ADR-004 — El foreground location service posee el tracking activo

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

El tracking debe continuar aunque la UI no esté visible y debe cumplir restricciones modernas de Android para uso continuo de ubicación.

## Decision

TrackingForegroundService de tipo location es el owner de una captura activa. La UI envía comandos; no posee la sesión de tracking.

## Rationale

- Alinea lifecycle del tracking con requisitos Android.
- Evita que cerrar una Activity termine el Trip.
- Permite notificación persistente y controles directos.

## Consequences

- Pause/Resume/Finish deben ser comandos idempotentes hacia el coordinator/service.
- El service rehidrata estado crítico desde Room.
- La app debe manejar restricciones de inicio de FGS desde background.

## Alternatives considered

- Tracking desde ViewModel/Activity. Rechazado por lifecycle.
- WorkManager para live tracking. Rechazado porque no es un scheduler para una sesión continua en tiempo real.

## Reopen / supersede triggers

- Android cambia sustancialmente el modelo de foreground services o aparece una API oficial superior específicamente para este caso.

## Traceability

- F0.4 Android Platform Research
- F0.8 System Architecture
- F0.10 Reliability & Recovery
- FR-REC-008

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
