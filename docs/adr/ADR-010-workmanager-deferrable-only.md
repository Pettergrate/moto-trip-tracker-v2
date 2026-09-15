# ADR-010 — WorkManager solo para trabajo diferible/persistente

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

Post-processing, rebuilds y purgas pueden continuar después; el live tracking necesita ejecución continua y control inmediato.

## Decision

WorkManager se usa para processing/reprocessing, mantenimiento y trabajos diferibles. No posee live tracking ni decisiones en tiempo real.

## Rationale

- Separa ejecución inmediata de trabajo persistente diferible.
- Aprovecha retries/backoff y persistencia de WorkManager donde sí corresponde.
- Evita acoplar una sesión activa a semántica de scheduler.

## Consequences

- Workers deben ser idempotentes y versionados.
- TrackingForegroundService y coordinator gestionan el viaje en vivo.
- Unique work protege contra duplicados de processing.

## Alternatives considered

- WorkManager para capturar GPS en vivo. Rechazado por semántica y timing.
- Procesamiento pesado dentro del service. Rechazado por interferir con tracking.

## Reopen / supersede triggers

- Android ofrece un primitive persistente diferente específicamente adecuado al procesamiento diferible o cambia WorkManager sustancialmente.

## Traceability

- F0.8 System Architecture
- F0.10 Reliability & Recovery
- F0.12 Testing Strategy

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
