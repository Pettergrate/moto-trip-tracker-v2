# F0.7 — Domain & Data Model
## Moto Trip Tracker V2

**Estado:** CERRADO baseline conceptual  
**Versión:** 0.1  
**Fecha:** 2026-09-15  
**Proyecto:** Moto Trip Tracker V2  
**Depende de:** F0.1–F0.6

---

## 1. Objetivo

Definir el modelo conceptual de dominio y las reglas de integridad que deberán soportar la detección, captura, procesamiento, historial, favoritos, merge/split, rutas y futuras extensiones de Moto Trip Tracker V2.

F0.7 define **qué información existe, qué representa, qué es fuente de verdad, qué puede recalcularse y cómo se relacionan los objetos**.

F0.7 **no** decide todavía:

- Room/SQLite u otra tecnología física;
- nombres definitivos de tablas/DAOs;
- índices físicos;
- representación binaria/compresión;
- módulos Kotlin;
- política final de backup;
- proveedor de mapas.

Esas decisiones pertenecen a F0.8 y bloques posteriores.

### Decisión principal

> El registro bruto e histórico de captura no debe reescribirse para satisfacer operaciones de biblioteca. La captura física (`TripCapture`) y el viaje lógico visible (`Trip`) serán conceptos distintos.

Esta separación permite merge, split, corrección de límites y reprocesamiento sin duplicar ni destruir los TrackPoints originales.

---

## 2. Principios de modelado

### 2.1 Fuente vs derivado

Los datos se clasifican explícitamente como:

1. **Fuente de verdad** — hechos capturados o acciones explícitas del usuario.
2. **Derivados reproducibles** — métricas o geometría generadas desde la fuente.
3. **Caché/UI** — información descartable que puede reconstruirse.

Un dato derivado nunca debe convertirse silenciosamente en la única copia de la evidencia original.

### 2.2 Raw data preservado

Los Raw TrackPoints, eventos del detector y pausas manuales permanecen asociados a su captura original.

Procesar, fusionar, dividir o renombrar un Trip no modifica silenciosamente esos registros fuente.

### 2.3 Identidad estable y offline

Toda entidad que deba sobrevivir exportaciones, restauraciones o futuras sincronizaciones tendrá un identificador estable generado localmente o definido por el dataset.

No se usarán nombres, posiciones visuales ni rowids como identidad lógica exportable.

La representación concreta del ID se decide en F0.8.

### 2.4 Orden explícito

Cuando el orden tenga significado se almacenará explícitamente.

Ejemplos:

- `RawTrackPoint.sequenceNumber`;
- `TripPart.orderIndex`;
- `ProcessedTrackPoint.orderIndex`;
- orden de markers cuando sea relevante.

No se dependerá del orden físico de filas.

### 2.5 Ausencia no significa cero

Si velocidad, altitude, accuracy u otro dato no está disponible, permanecerá nulo/no disponible.

No se convertirá la ausencia en `0`, porque eso crea mediciones falsas.

### 2.6 Tiempo civil y monotónico

El modelo debe distinguir:

- instante civil/UTC para historial y presentación;
- tiempo monotónico (`elapsedRealtime`) para ordenar eventos y calcular deltas dentro de una captura.

Un cambio manual del reloj del teléfono no debe alterar la secuencia física de un Trip activo.

### 2.7 Reprocesamiento versionado

Todo derivado que dependa de un algoritmo deberá conocer la versión de procesamiento que lo generó.

Esto permite mejorar posteriormente filtros GPS, distancia, velocidad o elevación y reconstruir estadísticas sin alterar los datos fuente.

### 2.8 Borrado seguro

Operaciones destructivas de biblioteca deberán poder usar estado `TRASHED`, `SUPERSEDED` o equivalente antes de purgar físicamente datos.

Merge/split no son borrados de datos raw.

### 2.9 No EAV genérico como núcleo

No se creará una tabla universal `key/value` para representar el dominio principal.

Los conceptos importantes serán entidades y campos tipados. Metadata diagnóstica opcional puede usar un payload estructurado, pero ningún estado crítico dependerá exclusivamente de un blob opaco.

---

## 3. Distinción fundamental: TripCapture vs Trip

### 3.1 TripCapture

`TripCapture` representa **lo que el sistema realmente registró durante una ejecución de tracking**.

Contiene o referencia:

- Raw TrackPoints;
- eventos del detector;
- transiciones de Activity Recognition;
- pausas manuales;
- gaps conocidos;
- versión del detector/location profile;
- timestamps de inicio/fin;
- origen del inicio/fin.

Una captura es evidencia técnica y debe cambiar lo mínimo posible después de cerrarse.

### 3.2 Trip

`Trip` representa **el viaje lógico que el usuario consulta en Historial**.

Normalmente un Trip recién registrado tendrá relación 1:1 con una TripCapture.

Sin embargo, el usuario puede después:

- fusionarlo con otro Trip;
- dividirlo;
- corregir inicio/fin;
- renombrarlo;
- asignarlo a una Route;
- marcarlo favorito;
- agregar notas/tags/markers.

Esas operaciones modifican la composición lógica del Trip, no el Raw Track original.

### 3.3 TripPart

`TripPart` es la pieza que une un Trip lógico con una porción de una TripCapture.

Un Trip puede contener uno o más TripParts ordenados.

Ejemplo inicial:

```text
Trip A
└── TripPart 1 → Capture 17 [inicio..fin]
```

Después de un merge:

```text
Trip C
├── TripPart 1 → Capture 17 [inicio..fin]
└── TripPart 2 → Capture 18 [inicio..fin]
```

Después de un split:

```text
Trip D
└── TripPart 1 → Capture 17 [inicio..punto X]

Trip E
└── TripPart 1 → Capture 17 [punto X..fin]
```

Los mismos Raw TrackPoints no necesitan duplicarse.

---

## 4. Capas conceptuales del dominio

| Capa | Entidades principales | Responsabilidad |
|---|---|---|
| Captura | `TripCapture`, `RawTrackPoint`, `CaptureEvent`, `ManualPauseInterval` | Evidencia original producida durante tracking. |
| Composición | `Trip`, `TripPart`, `TripEditOperation`, `TripLineageLink` | Viaje lógico y operaciones merge/split/corrección. |
| Procesamiento | `ProcessedTrackPoint`, `TripStatistics`, `TripStop`, `PointAssessment`, `LocationGap` | Resultados reproducibles derivados de la captura. |
| Organización | `Route`, `Tag`, `TripTag`, `Marker` | Biblioteca, clasificación y contexto de usuario. |
| Vehículo | `Motorcycle` | Asociar viajes y kilómetros a una moto. |
| Futuro | `Photo`, `PrivacyZone`, `RouteSignature`, mantenimiento/combustible | Extensiones reservadas; no Core inicial. |

---

## 5. Taxonomías y códigos conceptuales

Los códigos siguientes son contratos conceptuales; F0.8 decidirá si se representan como enums, strings, ints u otra forma.

### CaptureStatus

```text
ACTIVE
COMPLETED
ABORTED
```

`RECOVERED` no es un status final: una recuperación se registra como evento.

### TripStatus

```text
ACTIVE
COMPLETED
SUPERSEDED
TRASHED
```

- `SUPERSEDED`: reemplazado lógicamente por merge/split/corrección, conservado para lineage/undo.
- `TRASHED`: borrado por usuario dentro de la política de recuperación.

### StartSource

```text
AUTO
MANUAL
RECOVERY
IMPORTED        [futuro]
```

### EndSource

```text
AUTO
MANUAL
RECOVERY
ABORTED
```

### DetectorState

Se reutilizan los estados definidos en F0.3:

```text
IDLE
CANDIDATE_START
TRACKING
TEMPORARY_HOLD
MANUAL_PAUSED
CANDIDATE_STOP
FINALIZING
```

El detector state no sustituye `TripStatus` ni `CaptureStatus`.

### DataOrigin

```text
SYSTEM
USER
DETECTED
IMPORTED
```

### EditOperationType

```text
MERGE
SPLIT
BOUNDARY_EDIT
RESTORE
```

### TrackPointDecision

```text
ACCEPTED
SUSPECT
REJECTED
```

Los reason codes concretos se versionarán con el procesamiento.

---

## 6. Diccionario de entidades — captura

### 6.1 TripCapture

| Campo conceptual | Semántica |
|---|---|
| `id` | Identidad estable de la captura. |
| `status` | ACTIVE / COMPLETED / ABORTED. |
| `startedAt` | Instante civil de inicio. |
| `endedAt?` | Instante civil de fin. |
| `startElapsedRealtimeNanos` | Referencia monotónica de inicio. |
| `endElapsedRealtimeNanos?` | Referencia monotónica de fin. |
| `localTimeZoneId` | Zona local para reconstruir fecha/hora mostrada. |
| `startSource` | AUTO/MANUAL/RECOVERY. |
| `endSource?` | AUTO/MANUAL/RECOVERY/ABORTED. |
| `detectorVersion` | Versión de reglas/algoritmo. |
| `locationProfileVersion` | Perfil de location usado. |
| `createdAt`, `updatedAt` | Auditoría técnica. |

Reglas:

- máximo una TripCapture `ACTIVE` a la vez en Core;
- cerrar UI no cambia el status;
- `COMPLETED` requiere fin definido;
- process death puede generar eventos/recovery, no una nueva captura silenciosa si la anterior es recuperable.

### 6.2 RawTrackPoint

El contrato sigue F0.5 y F0.6.

Campos conceptuales:

```text
captureId
sequenceNumber
capturedAt
elapsedRealtimeNanos
receivedAtElapsedRealtimeNanos
latitude
longitude
horizontalAccuracyM
altitudeEllipsoidM?
altitudeMslM?
verticalAccuracyM?
speedMps?
speedAccuracyMps?
bearingDeg?
bearingAccuracyDeg?
provider/source?
isMock?
requestProfileId
callbackBatchId?
detectorStateSnapshot
isApproximateLocation?   (schema v3, ADR-022: true = solo había permiso aproximado; NULL = desconocido)
```

Reglas:

- `sequenceNumber` es único dentro de la captura;
- RawTrackPoint no se elimina porque el filtro posterior lo considere malo;
- un punto rechazado se marca en el derivado/assessment, no se “corrige” silenciosamente en raw;
- ausencia de un campo permanece nula.

### 6.3 CaptureEvent

Representa hechos/eventos cronológicos producidos durante captura.

Campos candidatos:

```text
captureId
eventIndex
timestamp
elapsedRealtimeNanos
eventType
stateFrom?
stateTo?
reasonCode?
source
metadata?
```

`metadata` puede contener información diagnóstica secundaria, pero ningún dato crítico de negocio deberá existir únicamente allí.

Ejemplos de `eventType`:

```text
ACTIVITY_TRANSITION
DETECTOR_STATE_CHANGED
MANUAL_PAUSE_STARTED
MANUAL_PAUSE_ENDED
GPS_GAP_STARTED
GPS_GAP_ENDED
SCREEN_OFF
SCREEN_ON
SERVICE_STARTED
PROCESS_RECOVERED
LOCATION_SETTINGS_CHANGED
```

### 6.4 ManualPauseInterval

Una pausa manual es semántica de usuario y merece entidad/intervalo explícito, aunque también existan eventos.

Campos:

```text
id
captureId
startedAt
endedAt?
startElapsedRealtimeNanos
endElapsedRealtimeNanos?
startReason?
endReason?
```

Reglas:

- una pausa abierta puede existir solo durante captura activa;
- las pausas no se convierten en Stop ordinario;
- `manualPauseDuration` se calcula aparte del tiempo detenido.

---

## 7. Diccionario de entidades — viaje lógico

### 7.1 Trip

| Campo conceptual | Semántica |
|---|---|
| `id` | Identidad estable del viaje lógico. |
| `status` | ACTIVE / COMPLETED / SUPERSEDED / TRASHED. |
| `name?` | Nombre editable por usuario. |
| `isFavorite` | Favorito de Trip. |
| `motorcycleId?` | Moto asociada si existe. |
| `routeId?` | Route principal opcional. |
| `notes?` | Nota libre de usuario. |
| `createdAt`, `updatedAt` | Auditoría. |
| `deletedAt?` | Soft-delete/recovery window. |

Reglas:

- un Trip puede existir sin Route;
- un Trip puede existir sin Motorcycle;
- un Trip completado puede sobrevivir aunque una captura tenga poca o ninguna ubicación válida;
- nombre, favorito, tags y notas no cambian la evidencia de tracking.

### 7.2 TripPart

| Campo conceptual | Semántica |
|---|---|
| `id` | Identidad de la pieza lógica. |
| `tripId` | Trip propietario. |
| `captureId` | Captura fuente. |
| `orderIndex` | Orden explícito dentro del Trip. |
| `startElapsedRealtimeNanos` | Inicio inclusivo de la porción. |
| `endElapsedRealtimeNanos?` | Fin de la porción. |
| `startSequenceNumber?` | Referencia al primer punto cuando exista. |
| `endSequenceNumber?` | Referencia al último punto cuando exista. |

Reglas:

- las partes del mismo Trip se ordenan explícitamente;
- un TripPart no crea copias de RawTrackPoint;
- dos partes del mismo Trip no deben contar dos veces el mismo rango de una captura salvo caso explícito de reparación que lo justifique;
- una boundary edit modifica límites lógicos, no los puntos raw.

---

## 8. Merge, split y lineage

### 8.1 TripEditOperation

Registra la operación que cambió la composición lógica.

Campos conceptuales:

```text
id
type
createdAt
undoneAt?
notes/reason?
```

### 8.2 TripLineageLink

Relaciona inputs y outputs de una operación.

```text
operationId
tripId
role = INPUT | OUTPUT
```

Esto soporta:

- múltiples inputs → un output (`MERGE`);
- un input → múltiples outputs (`SPLIT`);
- un input → un output revisado (`BOUNDARY_EDIT`).

### 8.3 Regla de merge

Merge:

1. crea un nuevo Trip;
2. copia/referencia los TripParts de los Trips origen en orden;
3. no duplica RawTrackPoints;
4. marca los Trips origen `SUPERSEDED`;
5. registra lineage;
6. invalida/recalcula ProcessedTrack y estadísticas;
7. conserva suficiente información para undo mientras la política lo permita.

### 8.4 Regla de split

Split:

1. crea dos o más Trips nuevos;
2. divide TripParts en una frontera monotónica/sequence válida;
3. el Trip origen pasa a `SUPERSEDED`;
4. RawTrackPoints permanecen iguales;
5. cada output recalcula estadísticas independientemente.

### 8.5 Boundary edit

Corregir inicio/fin se modela como modificación controlada de TripParts con lineage/provenance cuando sea necesario.

No se borran los puntos excluidos de la captura.

---

## 9. Datos derivados

### 9.1 ProcessedTrackPoint

Representa la geometría utilizada para métricas y mapa analítico.

Campos candidatos:

```text
tripId
processingVersion
orderIndex
latitude
longitude
sourceCaptureId
sourceSequenceNumber?
pointRole?
```

Puede ser:

- persistido como caché regenerable;
- generado bajo demanda;
- almacenado en forma simplificada adicional para mapas.

La decisión física pertenece a F0.8.

### 9.2 PointAssessment

Permite explicar por qué un RawTrackPoint fue aceptado, marcado sospechoso o rechazado.

```text
captureId
sequenceNumber
processingVersion
decision
reasonCodes
```

Es derivado y regenerable.

### 9.3 TripStatistics

Campos candidatos:

```text
tripId
processingVersion
computedAt
distanceM
totalDurationMs
movingDurationMs
stoppedDurationMs
manualPauseDurationMs
maxSpeedMps?
averageSpeedMps?
averageMovingSpeedMps?
minElevationM?
maxElevationM?
ascentM?
descentM?
validPointCount
suspectPointCount
rejectedPointCount
gapCount
```

Reglas:

- las métricas se recalculan tras merge/split/boundary edit;
- la velocidad máxima usa processed evidence, nunca `max(raw)`;
- elevation gain/loss solo se rellena si el algoritmo vigente se considera suficientemente fiable;
- una métrica desconocida queda nula, no cero.

### 9.4 LocationGap

Gap derivado explícito entre evidencias válidas.

```text
tripId
processingVersion
startedAt
endedAt
startSourceRef?
endSourceRef?
durationMs
reasonCode?
```

Un gap no se convierte automáticamente en una línea observada real ni se suma como distancia exacta.

### 9.5 TripStop

Representa una parada significativa, distinta de un semáforo/traffic hold y distinta de ManualPause.

Campos candidatos:

```text
id
tripId
origin = DETECTED | USER
startedAt
endedAt
centroidLat?
centroidLon?
durationMs
label?
lockedByUser
processingVersion?
```

Regla:

- Stops detectados y no editados pueden regenerarse;
- una corrección explícita del usuario no se sobrescribe silenciosamente por reprocesamiento.

---

## 10. Organización y contexto

### 10.1 Route

`Route` representa un recorrido lógico/repetible, no una ejecución concreta.

Campos conceptuales:

```text
id
name
description?
isFavorite
createdAt
updatedAt
```

Baseline:

- un Trip pertenece a cero o una Route principal;
- una Route contiene cero o muchos Trips;
- si en el futuro se requiere pertenencia múltiple, se reabre esta decisión en lugar de introducir complejidad prematura.

La geometría “canónica” de una Route será derivada y no se define todavía como fuente de verdad.

### 10.2 Tag / TripTag

Tags ofrecen clasificación N:M sin sobrecargar Route.

Ejemplos:

```text
#montaña
#offroad
#lluvia
#nocturno
#trabajo
```

### 10.3 Marker

Punto contextual creado por el usuario.

Campos candidatos:

```text
id
tripId
type
label?
note?
latitude?
longitude?
timestamp?
sourceCaptureId?
sourceSequenceNumber?
createdAt
```

Puede representar mirador, gasolinera, restaurante, inicio off-road, carretera dañada u otro punto.

### 10.4 Favorite

No se crea una entidad Favorite genérica en el baseline.

`Trip.isFavorite` y `Route.isFavorite` son suficientes para los casos definidos.

Si en el futuro aparecen más tipos favoritos, se reevaluará.

---

## 11. Motorcycle

Aunque múltiples motos son Post-Core, el modelo debe evitar bloquear la asociación desde el principio.

Campos conceptuales mínimos:

```text
id
name
make?
model?
year?
isArchived
createdAt
updatedAt
```

Reglas:

- Trip.motorcycleId es opcional;
- borrar/archivar una moto no debe volver ilegible su historial;
- mantenimiento, combustible y odómetro detallado permanecen fuera de F0.7 Core.

---

## 12. Relaciones y cardinalidades

```text
Motorcycle 1 ─────< Trip
Route      1 ─────< Trip

Trip 1 ─────< TripPart >───── 1 TripCapture
TripCapture 1 ─────< RawTrackPoint
TripCapture 1 ─────< CaptureEvent
TripCapture 1 ─────< ManualPauseInterval

Trip 1 ───── 0..1 TripStatistics        [derivado]
Trip 1 ─────< ProcessedTrackPoint       [derivado]
Trip 1 ─────< TripStop                  [derivado/editable]
Trip 1 ─────< LocationGap               [derivado]

Trip >─────< Tag                        [TripTag]
Trip 1 ─────< Marker

TripEditOperation 1 ─────< TripLineageLink >──── 1 Trip
```

Relaciones principales:

| Relación | Semántica |
|---|---|
| `TripCapture 1 → N RawTrackPoint` | Captura conserva puntos raw ordenados. |
| `TripCapture 1 → N CaptureEvent` | Eventos técnicos y de lifecycle. |
| `TripCapture 1 → N ManualPauseInterval` | Pausas explícitas del usuario. |
| `Trip 1 → N TripPart` | Composición lógica ordenada. |
| `TripPart N → 1 TripCapture` | Cada parte referencia una captura fuente. |
| `Route 1 → N Trip` | Route agrupa ejecuciones concretas. |
| `Motorcycle 1 → N Trip` | Moto agrupa sus viajes. |
| `Trip N ↔ M Tag` | Clasificación flexible. |
| `Trip 1 → N Marker` | Contexto voluntario del usuario. |

---

## 13. Fuente de verdad, derivados y caché

| Clase | Ejemplos | Política |
|---|---|---|
| Fuente de captura | TripCapture, RawTrackPoint, CaptureEvent, ManualPauseInterval | Preservar; no reconstruir desde estadísticas. |
| Fuente de biblioteca | Trip, TripPart, Route, Motorcycle, Marker, Tag, notas/favoritos | Respaldo/exportación; acciones explícitas del usuario. |
| Provenance | TripEditOperation, TripLineageLink | Mantener mientras exista capacidad de undo/auditoría. |
| Derivado versionado | ProcessedTrack, PointAssessment, TripStatistics, LocationGap, Stops detectados | Recalculable desde fuente + processingVersion. |
| Caché UX | polyline simplificada, thumbnails, búsquedas recientes | Eliminable sin pérdida de información. |

### Regla crítica

La polyline simplificada usada para renderizar un mapa **no** será fuente de verdad para distancia, velocidad o future reprocessing.

---

## 14. Unidades y representación semántica

F0.7 fija unidades canónicas conceptuales; F0.8 decide tipos físicos.

| Magnitud | Unidad canónica |
|---|---|
| Distancia | metros |
| Velocidad | m/s |
| Altitud | metros |
| Accuracy | metros |
| Bearing | grados [0, 360) |
| Duración | milisegundos o duración equivalente |
| Latitude/Longitude | grados WGS84 |

Altitud debe conservar su referencia cuando corresponda:

```text
ELLIPSOID
MSL
UNKNOWN
```

La UI podrá mostrar km, km/h u otras unidades sin cambiar la fuente canónica.

---

## 15. Reglas de integridad del flujo crítico

### Captura activa

- máximo una TripCapture `ACTIVE` a la vez;
- máximo un Trip de usuario activo asociado al flujo Core;
- cerrar Activity/UI no finaliza ninguno.

### Completar captura

- `COMPLETED` requiere `endedAt`;
- una pausa manual abierta debe cerrarse o resolverse explícitamente;
- puntos/eventos persistidos permanecen accesibles.

### TripPart

- `orderIndex` único dentro de Trip;
- límites válidos y ordenados;
- no double-count del mismo rango dentro del mismo Trip;
- no referencia a Capture inexistente.

### Merge/split

- no duplican raw;
- source Trips pasan a `SUPERSEDED`, no desaparecen silenciosamente;
- stats/processed outputs quedan invalidados hasta recalcular;
- lineage debe identificar inputs/outputs.

### Delete

- `TRASHED` oculta el Trip del uso normal;
- purga física depende de política F0.11;
- una captura aún referenciada por otro Trip no puede purgarse.

### Derived data

- todo derivado dependiente de algoritmo declara `processingVersion`;
- si source cambia, el derivado se invalida/recalcula;
- null permanece null cuando no existe evidencia suficiente.

### Historial

- renombrar Route/Motorcycle/Trip no cambia IDs;
- archivar Motorcycle/Route no destruye Trips históricos;
- notas/favoritos no modifican métricas.

---

## 16. Regla de retención raw

Baseline F0.7:

> Mientras un Trip visible, superseded recuperable o lineage válido dependa de una TripCapture, sus Raw TrackPoints no deben purgarse automáticamente.

La política temporal/espacial definitiva (almacenamiento, compresión, purge tras trash, backups) se define en F0.8/F0.11.

Esta decisión protege la capacidad de:

- reprocesar algoritmos futuros;
- corregir GPS filters;
- rehacer distancia/elevación;
- deshacer merge/split;
- investigar fallos.

---

## 17. Requisitos F0.2 cubiertos por el modelo

| Requisito | Soporte conceptual |
|---|---|
| FR-REC-001/002/003 | TripCapture + RawTrackPoint. |
| FR-REC-005/006/007 | Raw source separado de ProcessedTrack + PointAssessment. |
| FR-MET-001–012 | TripStatistics versionado y regenerable. |
| FR-HIS-001–005 | Trip persistente + status/soft delete. |
| FR-FAV-001/002 | `Trip.isFavorite`; Route preparado para favorito. |
| FR-EDT-001–004 | TripPart + TripEditOperation + Lineage. |
| FR-EDT-005/006 | Boundary edit + superseded/undo provenance. |
| FR-MAP-001–006 | ProcessedTrack separado de raw y cache visual. |
| FR-RTE-001–003 | Route + Trip.routeId opcional. |
| FR-CTX-001–003 | notas, Tag/TripTag y Marker. |
| FR-MOTO-001–003 | Motorcycle + asociación opcional. |
| FR-DIA-001–004 | CaptureEvent + detector/profile versions + raw evidence. |
| NFR-REL-004 | Derivados reproducibles desde fuente. |
| NFR-PERF-001/002 | Modelo permite queries/streaming por captura; física se decide F0.8. |
| NFR-PRV-001 | Ninguna identidad requiere servidor. |

---

## 18. Decisiones explícitas de NO modelado por ahora

F0.7 no añade al Core:

- Account/Login/User cloud;
- Subscription/Payment;
- Social Feed/Followers/Messages;
- turn-by-turn navigation instructions;
- tráfico en tiempo real;
- mantenimiento/combustible completos;
- WeatherObservation como fuente Core;
- cloud sync/conflict model;
- leaderboard/ranking de velocidad;
- mapa tiles/cache como fuente de Trip;
- un objeto “Favorite” universal;
- geometría Route canónica definitiva;
- tabla genérica Attribute/Value.

---

## 19. Decisiones que F0.8 debe cerrar

F0.8 deberá decidir, entre otras:

1. Room/SQLite u otra persistencia principal.
2. Representación física de IDs.
3. Tablas/índices/foreign keys.
4. Si RawTrackPoint vive íntegramente en DB o requiere estrategia híbrida.
5. Política de transacciones y escritura por lotes.
6. Persistencia de ProcessedTrack vs regeneración/caché.
7. Estrategia para grandes cantidades de TrackPoints.
8. Serialización/exportación y schemaVersion.
9. Estrategia de migraciones.
10. Módulos/repositorios/use cases alrededor del modelo.
11. Qué datos del detector pertenecen a Room vs logs diagnósticos exportables.
12. Cómo versionar processing/detector/location profiles en código y datos.

---

## 20. Criterio de cierre

F0.7 se considera cerrado cuando:

- [x] TripCapture y Trip están separados conceptualmente.
- [x] RawTrackPoint y eventos son fuente de verdad de captura.
- [x] TripPart permite merge/split sin duplicar raw.
- [x] Merge/split/boundary edit tienen provenance/lineage.
- [x] Source, derived y cache están separados.
- [x] Route, Motorcycle, Favorite, Tag y Marker están delimitados.
- [x] Pausa manual y Stop no se confunden.
- [x] Timestamps, ordering y unidades canónicas están definidos.
- [x] Reglas de integridad cubren el flujo crítico.
- [x] El modelo cubre F0.2/F0.3 sin fijar aún SQL/Room.
- [x] Las decisiones físicas pendientes se transfieren explícitamente a F0.8.

### Decisión v0.7

**F0.7 queda CERRADO como baseline conceptual.**

La decisión estructural más importante es preservar una capa de captura inmutable (`TripCapture`) y construir el historial visible como composición lógica (`Trip` + `TripPart`). Esto permite corregir, fusionar, dividir y reprocesar sin sacrificar evidencia raw.

---

## 21. Próximo workstream

**F0.8 — System Architecture**

Debe transformar este modelo conceptual en decisiones técnicas ejecutables:

- stack Android;
- persistencia;
- foreground/background components;
- repositories;
- processing pipeline;
- concurrency;
- estructura de paquetes/módulos;
- versionado/migraciones;
- baseline de testing arquitectónico.
