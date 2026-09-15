# F0.8 — Arquitectura del Sistema
## Moto Trip Tracker V2

**Estado:** CERRADO baseline técnico  
**Versión:** 0.1  
**Fecha:** 2026-09-15  
**Proyecto:** Moto Trip Tracker V2  
**Depende de:** F0.1–F0.7  
**Fecha de corte técnica:** 2026-09-15

---

## 1. Objetivo

Convertir las decisiones de producto, detección, Android, GPS, experimentos y modelo de datos de F0.1–F0.7 en una arquitectura Android implementable sin que Codex o Claude Code deban inventar durante el desarrollo:

- stack tecnológico;
- ownership del tracking activo;
- persistencia;
- procesamiento;
- concurrencia;
- versionado;
- límites entre UI, plataforma, datos y dominio;
- estrategia de background/foreground;
- estructura inicial del repositorio.

F0.8 sigue siendo documentación. No crea todavía el proyecto Android.

### Decisión ejecutiva

> Moto Trip Tracker V2 será una aplicación Android nativa, Kotlin + Jetpack Compose, local-first, con Room como fuente de verdad, DataStore para preferencias, Navigation 3 para navegación Compose-first, Hilt para inyección, Coroutines/Flow para asincronía y un foreground service de tipo `location` como owner Android del tracking activo.

La máquina de estados del detector y el procesamiento de ruta permanecerán desacoplados de Android tanto como sea razonable para poder probarlos de forma determinista.

---

## 2. Principios arquitectónicos

1. **Local-first / offline-first.** Detectar, registrar, finalizar y consultar Trips no depende de backend.
2. **Single source of truth.** Room es la fuente autoritativa para estado persistente de Trips/Captures; UI y Services no mantienen una copia oficial paralela.
3. **Tracking independiente de UI.** Cerrar o recrear `MainActivity` no finaliza un Trip.
4. **Raw antes que derivado.** La evidencia de captura se persiste antes de depender de cálculos posteriores.
5. **Android en los bordes.** La lógica de estado, merge/split, filtrado y métricas debe poder existir sin `Activity`, `Service`, Compose, Room Entity ni `android.location.Location`.
6. **UDF en UI.** Estado baja; eventos/intenciones suben. Composables no escriben DB ni controlan directamente servicios.
7. **Arquitectura pragmática.** No se crea un `UseCase` por cada CRUD ni múltiples módulos por ceremonia.
8. **Concurrencia estructurada.** No `GlobalScope`; operaciones críticas se serializan y transaccionan.
9. **Algoritmos versionados.** `schemaVersion`, `detectorVersion`, `locationProfileVersion` y `processingVersion` son conceptos distintos.
10. **Dependencias por necesidad.** Backend, analytics, Maps SDK, charts, sync y otras capas no entran al bootstrap sin requerimiento aprobado.

---

## 3. Baseline de plataforma y toolchain

| Componente | Baseline F0.8 | Política |
|---|---|---|
| Plataforma | Android nativo | No Flutter/React Native/KMP para Core V2. |
| Lenguaje | Kotlin | Usar soporte Kotlin integrado de AGP 9.x cuando corresponda; no duplicar plugins sin necesidad. |
| UI | Jetpack Compose + Material 3 | Single-activity; UI declarativa. |
| Build | AGP 9.4.0 | Release estable. |
| Gradle | 9.6.x | Wrapper versionado. |
| JDK | 17 | Baseline requerido por AGP/Hilt actuales. |
| SDK | compileSdk 36 / targetSdk 36 / minSdk 26 | Target 36 cumple requisito Play vigente desde 31-08-2026; no preview por defecto. |
| Compose | BOM 2026.08.00 | Usar canal estable salvo ADR explícito. |
| Material 3 | Stable mediante BOM / 1.4.x actual | No alpha en Core sin justificación. |
| Navegación | Navigation 3 estable 1.1.7 | Greenfield Compose-first. |
| Persistencia | Room 2.8.5 | Room 3 no se adopta por defecto; reevaluar por beneficio real/madurez. |
| Settings | DataStore 1.2.1 | Preferences DataStore inicialmente. |
| Trabajo diferible | WorkManager 2.11.2 | Reprocesamiento/rebuild/backups futuros; nunca live tracking. |
| Location / Activity | play-services-location 21.4.0 | FLP + Activity Recognition. |
| DI | Hilt / Dagger 2.57.1 | KSP; constructor injection por defecto. |
| AndroidX Hilt | 1.4.0 cuando sea necesario | Integración con WorkManager u otros Jetpack components. |
| Async | Kotlin Coroutines + Flow / StateFlow | Sin Rx salvo necesidad futura concreta. |
| Codegen | KSP | Fijar versión compatible con toolchain en bootstrap. |

### 3.1 Motivo de Android nativo

El producto es Android-first y depende intensamente de APIs de plataforma: Activity Recognition, foreground services, permisos de ubicación, lifecycle/background execution, notifications y Fused Location Provider. Una capa multiplataforma no aporta valor suficiente en V2 Core para compensar complejidad adicional.

### 3.2 Política de versiones

F0.8 fija **familias y baseline**, no obliga a conservar para siempre un parche concreto. Al crear Fase 1/W0:

- revalidar releases estables;
- fijar versiones en `libs.versions.toml`;
- evitar `+`, `latest` u otras dependencias dinámicas;
- cambios mayores de stack requieren ADR/revisión explícita.

---

## 4. Arquitectura lógica

Moto Trip Tracker V2 se divide en cuatro responsabilidades principales:

```text
Android / Platform
(Activity Recognition, FLP, Service, Notifications, Receivers)
                 │
                 ▼
Domain
(TripStateMachine, processing, merge/split, metrics)
                 │
                 ▼
Data
(Room, DataStore, repositories, mappers)
                 │
                 ▼
UI
(Compose, Navigation 3, ViewModels, UiState)
```

No es una regla de dependencias literal en todos los casos. El objetivo es que la lógica reusable y testeable no dependa innecesariamente del framework Android.

### 4.1 UI Layer

- Compose + Material 3.
- ViewModel por pantalla/flujo con estado no trivial.
- `UiState` inmutable.
- `StateFlow` como mecanismo principal de estado observable.
- La UI emite acciones/eventos a ViewModel.
- La UI no llama DAO, DataStore, FLP, receiver o service directamente.
- Navegación pasa IDs/keys, no objetos grandes serializados.

### 4.2 Data Layer

Repositories exponen la fuente de verdad y abstraen las fuentes de datos.

Baseline:

- `TripCaptureRepository`
- `TripRepository`
- `RouteRepository`
- `MotorcycleRepository`
- `SettingsRepository`

Los repositories exponen:

- `Flow` para lecturas observables;
- `suspend` para mutaciones;
- transacciones coordinadas cuando una operación toca varias entidades.

### 4.3 Domain Layer

Se utiliza donde existe lógica suficientemente compleja o reutilizable.

Ejemplos que **sí** justifican dominio/use case:

- `TripStateMachine`;
- merge/split;
- boundary correction;
- GPS point assessment;
- processing pipeline;
- trip statistics;
- stop/gap detection;
- route similarity futuro.

CRUD simple no necesita una clase `UseCase` ceremonial.

### 4.4 Platform Layer

Aísla dependencias directas de Android/Google Play services:

- Location/FLP;
- Activity Recognition;
- Foreground Service;
- Broadcast Receivers;
- notifications;
- boot/package replacement handling;
- permissions/system state.

La plataforma convierte objetos Android a modelos/inputs propios antes de entregar datos al dominio.

---

## 5. Ownership del tracking activo

### 5.1 Regla

`TrackingForegroundService` será el **owner Android de una captura activa**.

Durante `TRACKING`, el service:

- mantiene location updates;
- mantiene la notificación persistente requerida;
- hospeda/coordina `TrackingSessionCoordinator`;
- continúa aunque `MainActivity` desaparezca;
- recibe acciones Pause / Resume / Finish;
- no utiliza la UI como fuente de verdad.

### 5.2 Flujo automático de alto nivel

```text
Activity Recognition transition
          │
          ▼
ActivityTransitionReceiver
          │ normaliza señal
          ▼
Trip detection / Candidate Start
          │ evidencia suficiente
          ▼
TrackingForegroundService
          │
          ├── LocationGateway → FLP updates
          ├── TripStateMachine
          ├── TripCaptureRepository
          └── TrackingNotificationController
```

Las reglas exactas de cuándo Android permite iniciar el FGS desde background permanecen sometidas al contrato de F0.4 y a permisos vigentes.

### 5.3 Inicio manual

Un inicio manual desde UI puede solicitar directamente el inicio del flujo de tracking siempre que los requisitos de permisos/servicios del sistema estén satisfechos.

### 5.4 Pausa manual

Manual Pause es un estado de negocio persistente, no simplemente “detener el coroutine”. El sistema debe:

- registrar el inicio de la pausa;
- hacer flush de puntos pendientes;
- dejar de capturar detalle según política aprobada;
- conservar TripCapture/Trip activos;
- mantener notificación con Resume/Finish;
- reanudar solo por intención explícita del usuario.

---

## 6. Componentes principales

| Componente | Responsabilidad |
|---|---|
| `ActivityTransitionReceiver` | Recibe transiciones y las normaliza como señales del detector. |
| `BootReceiver` | Re-registra passive monitoring tras reboot/update; no deja GPS continuo. |
| `TrackingForegroundService` | Owner Android del Trip activo y notification actions. |
| `TrackingSessionCoordinator` | Coordina detector, location, persistencia y lifecycle de captura. |
| `TripStateMachine` | Máquina de estados pura definida en F0.3. |
| `LocationGateway` | Abstrae Fused Location Provider y perfiles F0.5/F0.6. |
| `ActivityRecognitionGateway` | Registro/desregistro de Activity Transition y normalización. |
| `TripCaptureRepository` | TripCapture, RawTrackPoints, CaptureEvents y pausas. |
| `TripRepository` | Trips lógicos, TripParts, lineage, favorites, trash, merge/split. |
| `ProcessingEngine` | Raw → assessment → processed → statistics/stops/gaps. |
| `TripProcessingWorker` | Post-processing diferible/reintentable. |
| `TrackingNotificationController` | Canal, contenido y acciones de la notificación. |
| `Permission/SystemStateGateway` | Estado de permisos, location services y capacidades relevantes. |

---

## 7. Persistencia física

### 7.1 Fuente de verdad

Room 2.8.5 sobre SQLite será la persistencia principal para:

- TripCapture;
- RawTrackPoint;
- CaptureEvent;
- ManualPauseInterval;
- Trip / TripPart / lineage;
- ProcessedTrackPoint cache;
- PointAssessment cuando sea necesario;
- TripStatistics;
- TripStop / LocationGap;
- Route;
- Motorcycle;
- Tag / TripTag;
- Marker.

DataStore queda reservado para preferencias pequeñas:

- Auto Tracking enabled;
- unidades;
- opciones de notificación;
- flags simples de UI;
- settings de usuario no relacionales.

**Nunca** se guardará un Trip activo como JSON en DataStore.

### 7.2 Estrategia para RawTrackPoint

Baseline: RawTrackPoints permanecen en SQLite/Room.

No se introduce desde el inicio:

- archivo binario por Trip;
- sharding por mes/año;
- base secundaria;
- almacenamiento custom.

Primero se medirán volumen, latencia, storage y exportación con datasets del gate experimental. Solo se reabre si aparece un problema real.

### 7.3 Journal mode

Se mantiene `RoomDatabase.JournalMode.AUTOMATIC` como baseline. No se fuerza una política distinta sin benchmark/razón concreta.

### 7.4 Índices

Indexar por consultas reales, no preventivamente todo.

Candidatos iniciales:

- `RawTrackPoint(captureId, sequenceNumber)` unique;
- `RawTrackPoint(captureId, elapsedRealtimeNanos)` si las queries lo necesitan;
- foreign keys de TripPart/Trip/Capture;
- status + timestamps en Trip/TripCapture para historial/active lookup;
- Route/Motorcycle foreign keys cuando se materialicen.

---

## 8. Representación física inicial

### 8.1 IDs

Entidades de dominio que sobreviven export/import:

- UUID estable local persistido como `TEXT` o representación Room equivalente.

Excepción deliberada:

- `RawTrackPoint` puede usar PK local `INTEGER` autoincremental/row surrogate y una clave lógica `captureId + sequenceNumber`.

No se necesita un UUID de 36 caracteres por cada punto GPS.

### 8.2 Coordenadas

- latitude/longitude: `Double`/SQLite REAL;
- `accuracy`, `speed`, `speedAccuracy`, `bearing`, `bearingAccuracy`, `altitude`, `verticalAccuracy`: valores numéricos nullable según disponibilidad;
- no convertir ausencia a cero.

### 8.3 Tiempo

- instantes históricos: epoch milliseconds (`Long`) + `localTimeZoneId` donde aporte contexto;
- secuencia física: `elapsedRealtimeNanos` (`Long`);
- `sequenceNumber`: entero creciente dentro de TripCapture.

### 8.4 Distancia y métricas derivadas

- unidades canónicas definidas en F0.7;
- persistir valores derivados en unidad canónica con versión de procesamiento;
- presentación convierte a km/millas o km/h según configuración futura.

---

## 9. Escritura de TrackPoints

El tracking no puede depender de conservar minutos de ruta solo en RAM.

### Pipeline

```text
FLP LocationResult
      ↓
LocationGateway
      ↓
RawLocationSample
      ↓
normalización + sequenceNumber
      ↓
buffer acotado / batch natural
      ↓
TripCaptureRepository
      ↓
Room transaction / insert batch
```

Reglas:

- buffer siempre acotado;
- I/O fuera del main thread;
- preservar orden por `sequenceNumber`/monotonic time;
- aprovechar batches naturales de FLP;
- batching adicional se decide mediante benchmark;
- flush al pausar, finalizar o antes de una transición crítica;
- un error persistente de DB debe elevar estado de fallo/diagnóstico y no quedar invisible mientras la UI afirma que el Trip está sano.

No se exige commit por cada segundo individual si un batch corto reduce I/O sin aumentar de forma inaceptable el riesgo de pérdida.

---

## 10. Raw, Processed y processing

### 10.1 Raw Track

`RawTrackPoint` es fuente de verdad y no se modifica por mejorar algoritmos.

### 10.2 Processed Track

`ProcessedTrackPoint` se mantiene como **cache reproducible y versionada**.

Motivo:

- evita reprocesar miles de puntos cada vez que se abre Trip Detail;
- facilita mapas, estadísticas y debugging;
- puede invalidarse y reconstruirse desde Raw.

### 10.3 Polyline de visualización

Una polyline simplificada puede generarse para render rápido, pero:

> Nunca será la fuente de verdad para distancia, velocidad, elevación o edición de límites.

### 10.4 Processing pipeline

```text
RawTrackPoints
      ↓
Point Assessment
      ↓
Accepted / Suspect / Rejected
      ↓
Processed Track
      ↓
Distance / Speed / Elevation
      ↓
Stops / Gaps
      ↓
TripStatistics
```

Cada resultado importante guarda o conoce `processingVersion`.

### 10.5 WorkManager

WorkManager se utiliza para trabajo **diferible y reintentable**, por ejemplo:

- processing posterior al cierre;
- rebuild de processed cache;
- recalcular estadísticas tras cambio de processingVersion;
- export/backup futuro si cumple el modelo de trabajo diferible.

No se utiliza para:

- pedir location updates en vivo;
- mantener un Trip activo;
- temporización de semáforos/stop detection;
- acciones Pause/Resume/Finish inmediatas.

---

## 11. Concurrencia

### 11.1 TrackingSessionCoordinator

Debe existir un único coordinador lógico de la sesión activa dentro del proceso/service.

Responsabilidades:

- serializar commands críticos;
- evitar doble start/finish;
- validar transiciones contra `TripStateMachine`;
- coordinar flush + state change;
- mantener idempotencia ante intents/notificaciones repetidas.

### 11.2 Structured concurrency

- `CoroutineScope` ligado al lifecycle del componente correspondiente;
- no `GlobalScope`;
- dispatcher CPU/I/O inyectable cuando ayude a pruebas;
- cancelar collectors/tasks al cerrar ownership;
- Flow para observación, no como cola ilimitada de eventos irrecuperables.

### 11.3 Commands críticos

`Start`, `Pause`, `Resume`, `Finish`, `Recover` deben ser idempotentes o validados por estado.

Ejemplo:

- dos taps rápidos de Finish no crean dos Trips;
- Resume recibido cuando ya TRACKING no duplica capture/session;
- un intent viejo de notificación no puede revivir una captura COMPLETED.

---

## 12. Transacciones e invariantes

### 12.1 Finalizar captura

La finalización debe evitar estados parciales.

Transacción lógica:

1. persistir últimos puntos;
2. cerrar pausa abierta si corresponde;
3. persistir CaptureEvent final;
4. marcar TripCapture `COMPLETED`;
5. crear/confirmar Trip lógico inicial y TripPart;
6. dejar trabajo derivado preparado para processing.

Si el procesamiento falla después, la evidencia raw y el Trip no deben desaparecer.

### 12.2 Merge

Una transacción de merge:

- crea nuevo Trip;
- crea TripParts ordenados;
- crea lineage/provenance;
- marca Trips origen `SUPERSEDED`;
- invalida/recalcula derivados;
- nunca duplica ni modifica RawTrackPoints.

### 12.3 Split / Boundary Edit

Misma filosofía:

- composición lógica cambia;
- raw original permanece;
- operaciones dejan lineage;
- derivados se recalculan.

---

## 13. Repositories, domain y UI

### Repositories iniciales

```text
TripCaptureRepository
TripRepository
RouteRepository
MotorcycleRepository
SettingsRepository
```

Podrán aparecer repositories específicos solo cuando el dominio lo justifique.

### Reglas

- ViewModel no importa DAO.
- Composable no importa repository.
- Service no escribe DAOs dispersos; usa coordinator/repository.
- Room Entities no son modelos de UI.
- Android `Location` no atraviesa el dominio central.
- DataStore no se lee directamente desde Composables.

---

## 14. Navigation y UI shell

F0.8 elige **Navigation 3 estable** como baseline por ser la opción Compose-first actual para un proyecto greenfield.

F0.9 definirá:

- destinos;
- back stack;
- bottom navigation o estructura equivalente;
- deep links si existen;
- comportamiento Active Trip;
- Home/History/Favorites/Trip Detail/Settings.

F0.8 solo establece:

- una `MainActivity`;
- navegación Compose;
- pasar IDs/keys entre destinos;
- pantalla reconstruye estado consultando repositories;
- back stack no es fuente de verdad de un Trip activo.

---

## 15. Estructura inicial del repositorio

Se inicia con **un solo módulo `:app`** para reducir configuración y fricción durante W0.

Estructura conceptual:

```text
app/src/main/java/<applicationId>/
├── app/
│   ├── MotoTripApplication.kt
│   ├── MainActivity.kt
│   └── navigation/
│
├── core/
│   ├── model/
│   ├── common/
│   ├── database/
│   ├── datastore/
│   ├── location/
│   ├── activityrecognition/
│   └── notification/
│
├── data/
│   ├── repository/
│   └── mapper/
│
├── domain/
│   ├── detection/
│   ├── processing/
│   └── trip/
│
├── tracking/
│   ├── service/
│   ├── coordinator/
│   └── receiver/
│
├── worker/
│
└── feature/
    ├── home/
    ├── active/
    ├── history/
    ├── tripdetail/
    ├── favorites/
    ├── routes/
    └── settings/

app/schemas/                 # Room exported schemas
```

No es obligatorio conservar exactamente estos nombres; sí las responsabilidades.

### Trigger de multimódulo

Reevaluar solo si existe evidencia como:

- tiempos de build problemáticos;
- boundary de ownership real;
- reusable library real;
- testing/compilation significativamente mejor;
- crecimiento que vuelve `:app` inmanejable.

---

## 16. Versionado

### 16.1 `schemaVersion`

Versiona la estructura Room/SQLite.

### 16.2 `detectorVersion`

Versiona comportamiento/reglas del state machine y scoring/thresholds.

### 16.3 `locationProfileVersion`

Versiona parámetros de LocationRequest / sampling usados para una captura.

### 16.4 `processingVersion`

Versiona filtros GPS y cálculo de distancia/velocidad/elevación/stops.

### 16.5 `backupSchemaVersion`

Se definirá en F0.11/roadmap de exportación; no debe confundirse con Room schemaVersion.

### Regla

Un cambio de algoritmo **no** necesita migrar ni reescribir RawTrackPoints.

Los caches derivados antiguos pueden marcarse stale y reconstruirse.

---

## 17. Migraciones Room

- exportar schemas Room al repositorio;
- cada cambio físico aumenta schemaVersion;
- migration tests obligatorios cuando haya datos reales soportados;
- no usar `fallbackToDestructiveMigration()` en release con historial del usuario;
- backfills deben ser deterministas;
- si una migración no puede garantizar seguridad, debe existir estrategia explícita de backup/restore antes de release.

---

## 18. Maps

F0.8 **no fija todavía proveedor de mapas**.

Razón:

- Maps afecta UX, licenciamiento, API keys, costes, offline tiles y políticas de caching;
- tracking y persistencia no deben depender de que un mapa cargue.

Contrato arquitectónico:

```text
Trip / Processed Track
        ↓
Map presentation adapter
        ↓
Concrete map SDK (decisión posterior)
```

La pérdida de Internet o tiles jamás debe detener un Trip activo.

---

## 19. Diagnostics y builds

Baseline de build:

- `debug`
- `release`

F0.13 podrá añadir herramientas internas o un build type/flavor diagnóstico si aporta valor.

El Core no incluye por defecto:

- Firebase Analytics;
- Crashlytics;
- Sentry;
- telemetría remota.

Los eventos internos requeridos por F0.3/F0.6 se almacenan localmente y se diseñarán formalmente en F0.13.

---

## 20. Dependencias excluidas del bootstrap

No agregar inicialmente sin issue/requisito explícito:

- Retrofit / OkHttp / Ktor client;
- Firebase / Supabase / Auth;
- analytics remoto;
- Maps SDK concreto;
- librería de charts;
- Paging;
- SQLCipher;
- framework MVI externo;
- cloud sync;
- múltiples módulos Gradle;
- WebView;
- librerías “helper” de GPS que oculten FLP/Location internamente;
- service managers globales/singletons mutables fuera de DI.

---

## 21. Testing arquitectónico mínimo

F0.12 definirá la estrategia completa, pero F0.8 exige que la arquitectura permita al menos:

| Nivel | Qué debe poder probarse |
|---|---|
| JVM unit | TripStateMachine, point filters, metrics, merge/split, mappers. |
| Repository | Persistencia y operaciones con DB de prueba/fakes. |
| Room instrumentation | Constraints, queries, transacciones, migrations. |
| Worker | Processing/retry/idempotencia. |
| Service/integration | Start/Pause/Resume/Finish y recovery básico. |
| Compose UI | Flujos de navegación/estado sin lógica de tracking embebida. |
| Field | Diagnostic Harness de F0.6 con perfiles versionados. |

---

## 22. Triggers para reabrir decisiones

| Decisión | Reabrir si… |
|---|---|
| Single `:app` module | Build/ownership muestran problema medible. |
| Room 2.8.x | Room 3 aporta beneficio concreto/madurez o cambia soporte. |
| RawTrackPoint en Room | Benchmark muestra I/O/tamaño/latencia inaceptable. |
| Processed Track persistido | Storage supera claramente el beneficio de apertura/reprocessing. |
| Navigation 3 | Existe bloqueo estable/serio durante W0. |
| minSdk 26 | Distribución real o dependencia aprobada exige otro rango. |
| Sin backend | Sync multi-device se aprueba como requisito. |
| Hilt | Build/tooling demuestra costo desproporcionado real. |
| play-services-location | Requisito de dispositivos sin Google Play services entra al producto. |

---

## 23. ADRs candidatos derivados de F0.8

F0.14 formalizó estas decisiones como ADRs Accepted. La numeración final (ADR-001..ADR-020) y su mapeo autoritativo viven en `docs/adr/README.md`; esta sección no repite IDs específicos para evitar que una lista provisional quede desalineada con el baseline final.

**Nota de corrección (F0.17):** una versión anterior de esta sección listaba un mapeo candidato ADR-001..ADR-010 que no coincidía con la numeración final aceptada en F0.14 (por ejemplo, aquí se sugería ADR-002 para Room, mientras que el baseline final asigna ADR-002 a "Android nativo con Kotlin y Compose" y ADR-003 a Room). El contenido arquitectónico de F0.8 sigue vigente; solo se corrigió la referencia cruzada obsoleta. Ver `docs/adr/README.md` para el mapeo correcto.

---

## 24. Fuentes técnicas oficiales consultadas

Fecha de corte: 2026-09-15.

1. Android Developers — Guide to app architecture / Architecture recommendations.
2. Android Developers — UI layer / state holders / UDF.
3. Android Developers — Room release notes (estable 2.8.5 al corte).
4. Android Developers — DataStore release notes (estable 1.2.1 al corte).
5. Android Developers — WorkManager release notes (estable 2.11.2 al corte).
6. Android Developers — Hilt dependency injection guide (Dagger/Hilt 2.57.1 en ejemplo actual).
7. Android Developers — Navigation 3 release notes (estable 1.1.7 al corte).
8. Android Developers — Compose BOM guide (2026.08.00 estable documentada al corte).
9. Android Developers — AGP 9.4.0 release notes (Gradle 9.6, JDK 17).
10. Android Developers — Google Play target API requirement (target API 36 desde 31-08-2026 para nuevas apps/updates).
11. Android Developers — Foreground service types / location restrictions.
12. Google Play services — setup/release notes (`play-services-location:21.4.0`).

---

## 25. Criterio de cierre

F0.8 se considera cerrado cuando:

- [x] stack y baseline de toolchain están definidos;
- [x] source of truth y persistencia física inicial están definidos;
- [x] ownership del tracking activo está definido;
- [x] Receiver → detector → service → persistence está definido;
- [x] Raw / Processed / cache y processing están delimitados;
- [x] WorkManager está delimitado y excluido de live tracking;
- [x] estrategia de escritura de TrackPoints y límites de memoria están definidos conceptualmente;
- [x] boundaries de repositories/domain/UI/platform están definidos;
- [x] concurrencia y transacciones críticas están documentadas;
- [x] schema/detector/location/processing versioning están separados;
- [x] estructura inicial del repositorio está definida;
- [x] dependencias prematuras y triggers de reapertura están documentados;
- [x] V2 puede bootstrapearse sin inventar arquitectura esencial.

### Decisión v0.8

> **F0.8 queda CERRADO como baseline técnico.**

El siguiente workstream es **F0.9 — UX & Navigation Architecture**.
