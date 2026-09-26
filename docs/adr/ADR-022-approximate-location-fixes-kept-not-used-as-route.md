# ADR-022 — Fixes tomados solo con ubicación aproximada: se conservan, no se usan como ruta

**Status:** Accepted  
**Date:** 2026-09-26  
**Project:** Moto Trip Tracker V2  
**Phase:** Phase 1 — REC-005 follow-up

---

## Context

Observado en el teléfono real (Android 16): con el permiso *preciso* revocado y el *aproximado* concedido, la app reinició el servicio, siguió grabando y aceptó un fix con `horizontalAccuracyM = 2000` a ~920 m de la posición real. Con el teléfono quieto, la distancia en vivo subió de 0,0 a 1,0 y luego a 1,9 km, y el único aviso era "No GPS signal". El procesamiento aceptaba ese punto por diseño (F0.5 §7.1: sin cutoff global de accuracy hasta tener datos de F0.6), así que la distancia del viaje terminado habría cargado el mismo error.

## Decision

1. Cada RawTrackPoint lleva `isApproximateLocation` (`Boolean?`): `true` si al recibirlo solo había permiso aproximado, `false` si había preciso, `NULL` = desconocido (todo lo grabado antes del schema v3; nunca se adivina, ADR-016).
2. El punto **se conserva** (ADR-006), marcado.
3. El procesamiento lo **rechaza** (`REJECTED_APPROXIMATE_LOCATION`): no entra a la ruta, no es baseline de comparación y no tapa un gap. La distancia en vivo lo omite. No es un umbral de accuracy: es una causa conocida registrada con el punto.
4. Un fix aproximado no cuenta como señal viva ni alimenta las heurísticas de movimiento (pausa/fin olvidados).
5. El cambio de capacidad se registra (`LOCATION_ACCURACY_DEGRADED` / `LOCATION_ACCURACY_RESTORED`) y se muestra (notificación "Approximate location only", tarjeta en Active Trip) con prioridad sobre "No GPS signal", porque la causa es conocida.
6. El viaje **no se cierra** ni se pausa (§12.1: conservar la captura).

## Rationale

- Conservar el dato es lo que exige ADR-006 y deja abierta una versión futura que sí lo use (p. ej. contexto grueso).
- Un marcador registrado en el momento es reproducible desde raw; una regla basada en eventos de diagnóstico (con retención propia) no lo sería.
- Es un cambio aditivo y nullable (como `MIGRATION_1_2`): no hay backfill inventado.

## Consequences

- Schema v3 y `MIGRATION_2_3`. Probada con `MigrationTestHelper` en JVM contra `app/schemas/.../2.json` y 3.json, con un ensayo sobre una copia de la base real del dueño (mismos conteos, checksums de raw idénticos, `integrity_check` ok) y luego en el teléfono (v3, mismos conteos y checksums). El test instrumentado equivalente está escrito pero **no se corrió** (`connectedAndroidTest` borró la base de producción en MET-001).
- **`processingVersion` no se sube.** Ningún punto existente lleva el marcador, así que para todo el raw que existe hoy la salida es idéntica; subirla obligaría a recomputar todos los viajes reales sin beneficio. La regla solo actúa sobre puntos nuevos con `true`. Es una lectura deliberada de ADR-014, no un olvido.
- El CSV crudo de experimentos (`RawTrackCsvWriter`) no incluye la columna nueva (cambiaría un formato documentado, F0.6 §20); las sesiones de campo corren con permiso preciso.
- Solo hay dato de un dispositivo (Honor, Android 16).

## Alternatives considered

- **No guardar** los fixes aproximados: más simple y sin migración, pero pierde evidencia irrecuperable y contradice ADR-006. Rechazado.
- **Filtrar por accuracy** (p. ej. > 1000 m): es exactamente el umbral sin datos que F0.5 §7.1 prohíbe, y confundiría un fix preciso pero malo con un fix aproximado. Rechazado.
- **Cerrar el viaje** como hacía V1 (parity fila 37): pierde la captura en curso; §12.1 pide conservarla. Rechazado por el dueño (eligió mantener la grabación).

## Reopen / supersede triggers

- Datos de campo (F0.6) que permitan un criterio de calidad por accuracy fundamentado.
- Un dispositivo/versión de Android donde el fix aproximado sea utilizable como ruta.

## Traceability

- `reliability-recovery.md` §12.1, §13, §21
- ADR-006, ADR-014, ADR-016
- REC-005 (gap d), PERM-003
