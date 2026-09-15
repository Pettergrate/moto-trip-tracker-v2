# ADR-006 — Raw Track se preserva; Processed Track es derivado versionado

**Status:** Accepted  
**Date:** 2026-09-15  
**Project:** Moto Trip Tracker V2  
**Phase:** F0.14 — ADR Baseline

---

## Context

Filtrado GPS, distancia, velocidad y elevación pueden mejorar con algoritmos posteriores. Perder la observación original impediría recalcular o auditar resultados.

## Decision

RawTrackPoint se preserva como evidencia fuente. ProcessedTrack, assessments, statistics y polylines son derivados regenerables asociados a processingVersion.

## Rationale

- Permite corregir algoritmos sin perder datos.
- Facilita explicar por qué un punto fue rechazado.
- Separa precisión analítica de optimizaciones de visualización.

## Consequences

- El almacenamiento raw puede crecer y deberá medirse.
- Las métricas se invalidan/recalculan al cambiar composición o processingVersion.
- Polyline simplificada nunca es fuente para distancia/velocidad.

## Alternatives considered

- Guardar solo track filtrado. Rechazado por pérdida irreversible.
- Calcular stats una sola vez. Rechazado por imposibilidad de reprocessing confiable.

## Reopen / supersede triggers

- El coste de almacenamiento real resulta inaceptable incluso con políticas razonables; cualquier cambio debe conservar una forma de provenance suficiente.

## Traceability

- F0.5 GPS & Location Research
- F0.7 Domain & Data Model
- FR-REC-005
- NFR-REL-004

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.
