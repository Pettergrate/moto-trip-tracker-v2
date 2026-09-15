# F0.13 — Observabilidad y diagnóstico
## Moto Trip Tracker V2

**Estado:** CERRADO — baseline de observabilidad v0.1  
**Versión:** 0.1  
**Fecha:** 2026-09-15  
**Depende de:** F0.3, F0.7, F0.8, F0.10, F0.11 y F0.12

---

## 1. Objetivo

Definir qué evidencia técnica debe producir Moto Trip Tracker V2 para que los fallos de detección, GPS, tracking, persistencia, procesamiento, recuperación y edición puedan investigarse de forma reproducible.

La observabilidad de V2 no se diseña como telemetría remota ni como analytics de producto. Su propósito principal es:

- entender por qué el detector inició o no inició un viaje;
- entender por qué un viaje terminó, se fragmentó o quedó activo;
- verificar qué calidad de ubicación recibió el sistema;
- reconstruir la secuencia de estados del foreground service y del proceso;
- investigar errores de Room, WorkManager y post-procesamiento;
- comprobar qué versión de detector/configuración produjo un resultado;
- facilitar field tests y replay regression;
- generar un paquete diagnóstico sanitizado cuando sea necesario.

Principio rector:

> **Evidence, not telemetry. Local-first, structured, correlated and privacy-safe.**

---

## 2. No objetivos

F0.13 NO introduce:

- analytics comerciales;
- seguimiento remoto del usuario;
- envío automático de rutas o eventos;
- crash reporting de terceros en Core;
- métricas de engagement;
- un sistema de logs que replique cada TrackPoint;
- dependencia de Internet para diagnosticar la app;
- restauración de estado basada en logs.

Los datos persistentes de dominio siguen siendo la fuente de verdad. Los diagnósticos explican qué ocurrió; no sustituyen Room ni Raw Track.

---

## 3. Capas de evidencia

| Capa | Fuente | Propósito | Retención conceptual |
|---|---|---|---|
| Datos fuente | `RawTrackPoint`, `TripCapture`, `TripPart`, `Trip` | Evidencia geográfica y de dominio | Según ciclo de vida del usuario |
| Timeline de dominio | `CaptureEvent` / `TripEvent` | Decisiones y cambios relevantes del viaje | Con la captura/viaje |
| Diagnóstico técnico | `DiagnosticEvent` | Evidencia de detector, FGS, permisos, DB, workers y sistema | Acotada |
| Estado actual | `DiagnosticSnapshot` | Foto resumida del sistema en un momento | Reemplazable |
| Salida del proceso | `ApplicationExitInfo` + resumen propio | Por qué murió el proceso anterior | Historial disponible del sistema + copia resumida local si aporta valor |
| Performance trace | Perfetto / AndroidX Tracing | Latencia, hilos, I/O y secuencia temporal detallada | Solo bajo demanda/debug/profile |
| Field test | Session/experiment result | Comparar detector con ground truth | Dataset de investigación |

### 3.1 Regla de no duplicación

No se crea un `DiagnosticEvent` por cada `RawTrackPoint` aceptado. El Raw Track ya contiene esa evidencia.

Los eventos diagnósticos de ubicación se reservan para cambios de calidad, gaps, rechazos, errores, recuperación y resúmenes.

---

## 4. Contrato conceptual de `DiagnosticEvent`

F0.13 confirma una extensión conceptual al modelo de F0.7. La representación física se cerrará en Fase 1.

Campos candidatos:

| Campo | Regla |
|---|---|
| `eventId` | ID estable local |
| `occurredAt` | Timestamp civil interoperable |
| `elapsedRealtimeNanos?` | Orden/duración monotónica dentro del mismo boot cuando exista |
| `category` | Familia de diagnóstico |
| `eventType` | Código estable, no texto libre como identidad lógica |
| `severity` | TRACE / INFO / WARN / ERROR |
| `source` | detector, service, repository, worker, system, UI, etc. |
| `captureId?` | Correlación con captura activa |
| `tripId?` | Correlación con Trip lógico cuando corresponda |
| `correlationId?` | Une comando → service → persistencia → processing |
| `stateBefore?` | Estado relevante antes del cambio |
| `stateAfter?` | Estado relevante después del cambio |
| `reasonCode?` | Motivo estable/consultable |
| `metadata` | Mapa pequeño y allowlisted; sin datos sensibles arbitrarios |
| `appVersion` | Versión de app |
| `schemaVersion` | Versión de DB/contrato cuando aporte diagnóstico |
| `detectorVersion` | Versión del algoritmo/configuración de detector |
| `locationProfileVersion` | Perfil de sampling/filtrado |
| `processingVersion` | Versión de pipeline de procesamiento |

### 4.1 Reglas de metadata

- No guardar notas libres del usuario.
- No guardar nombre del Trip o Motorcycle como parte del diagnóstico técnico.
- No guardar latitud/longitud en `DiagnosticEvent` por defecto.
- Preferir códigos y contadores: `accuracyBucket=POOR`, `rejectedPoints=4`, `reason=STALE_FIX`.
- Valores inesperados se sanitizan antes de persistir o imprimir.

---

## 5. Taxonomía de eventos

### 5.1 DETECTOR

Ejemplos:

- `DETECTOR_STATE_CHANGED`
- `CANDIDATE_START_ENTERED`
- `CANDIDATE_START_CONFIRMED`
- `CANDIDATE_START_REJECTED`
- `CANDIDATE_STOP_ENTERED`
- `CANDIDATE_STOP_CANCELLED`
- `CANDIDATE_STOP_CONFIRMED`
- `AUTO_HOLD_ENTERED`
- `AUTO_HOLD_EXITED`
- `MANUAL_PAUSE_ENTERED`
- `MANUAL_RESUME`
- `AUTO_START_SUPPRESSED`

Cada transición importante debe poder explicar **qué señal disparó la evaluación y qué razones principales permitieron o bloquearon el cambio**.

### 5.2 ACTIVITY_RECOGNITION

- transición recibida;
- tipo de actividad;
- ENTER/EXIT;
- age del evento cuando se procesa;
- evento descartado por stale/duplicado;
- registro perdido/recreado;
- error de Play services.

No se interpreta `IN_VEHICLE` como “motocicleta confirmada”.

### 5.3 LOCATION

- provider/updates iniciados o detenidos;
- precise/approximate capability change;
- first fix;
- prolonged no-fix;
- `LOCATION_GAP_STARTED` / `LOCATION_GAP_ENDED`;
- salto imposible rechazado;
- batch persistido;
- accuracy degradada/restaurada;
- Location Services desactivado/reactivado.

No duplicar cada punto válido como evento.

### 5.4 TRACKING_SERVICE

- start solicitado;
- origen del start: MANUAL / DETECTOR / RECOVERY;
- `onCreate`;
- `onStartCommand`;
- sticky recreation;
- `Intent == null` en recuperación;
- foreground promotion success/failure;
- stop solicitado y motivo;
- service finalizado;
- inconsistencias detectadas al rehidratar.

### 5.5 CAPABILITY / PERMISSIONS

- permiso concedido/revocado;
- notificaciones activadas/desactivadas;
- background location disponible/no disponible;
- Activity Recognition disponible/no disponible;
- Location Services off/on;
- capacidad resultante: FULL_AUTO / ASSISTED / MANUAL / LOCATION_DEGRADED.

No repetir eventos si el estado efectivo no cambió.

### 5.6 PERSISTENCE

- batch write success resumido;
- write failure;
- retry programado;
- buffer iniciado;
- buffer high-water mark;
- buffer overflow;
- transaction rollback;
- constraint violation;
- storage low/full cuando sea detectable;
- migration result.

### 5.7 PROCESSING / WORKER

- work enqueued;
- unique work policy aplicada;
- attempt number;
- processing started/completed/retried/failed;
- processing version;
- source capture/trip;
- derived data invalidated/rebuilt.

### 5.8 EDITING / LINEAGE

- merge requested/completed/rolled back;
- split requested/completed/rolled back;
- boundary edit;
- delete → trash;
- restore;
- purge.

La información de lineage vive en el modelo de dominio; el diagnóstico solo registra la operación y su resultado.

### 5.9 RECOVERY / SYSTEM

- proceso anterior detectado como terminado;
- exit reason;
- recovery scan iniciado;
- active capture encontrada/no encontrada;
- same-boot continuity possible/not possible;
- reboot detectado;
- capture marcada partial/interrupted;
- worker pendiente recuperado;
- estado inconsistente reparado o escalado.

### 5.10 USER_COMMAND

Registrar solamente acciones que cambian estado de negocio:

- START;
- PAUSE;
- RESUME;
- FINISH;
- CANCEL;
- MERGE;
- SPLIT;
- RESTORE;
- toggle Auto Tracking.

No registrar navegación ordinaria por pantallas como analytics.

---

## 6. Severidad

| Nivel | Uso |
|---|---|
| TRACE | Evidencia muy detallada para debug/profile y experimentación; desactivada o reducida en release |
| INFO | Cambio normal pero relevante de estado |
| WARN | Degradación recuperable o condición inesperada que merece investigación |
| ERROR | Operación fallida, pérdida potencial de continuidad o estado que requiere recuperación |

La severidad no determina por sí sola qué se muestra al usuario.

Ejemplo: perder GPS durante un túnel puede ser `WARN` diagnóstico, mientras la UX solo muestra un estado discreto si la interrupción supera la política definida.

---

## 7. Explicabilidad del detector

La pregunta principal debe ser respondible:

> “¿Por qué el detector decidió iniciar, no iniciar, mantener o terminar este Trip?”

En cada transición decisiva se guarda un **decision summary** estructurado:

- estado anterior;
- estado nuevo;
- trigger principal;
- profile/version;
- señales disponibles;
- reglas relevantes aprobadas/fallidas;
- reason codes;
- quality flags;
- tiempo acumulado en estado candidato;
- ausencia de señal crítica si aplica.

F0.13 no obliga a un sistema de scoring específico. Si Fase 1 adopta score/confidence, el evento puede añadir `score` y `thresholdVersion` sin cambiar la semántica base.

### 7.1 Reason codes candidatos

Ejemplos:

- `IN_VEHICLE_ENTER`
- `MOTION_CONFIRMED`
- `DISPLACEMENT_CONFIRMED`
- `SPEED_EVIDENCE_PRESENT`
- `INSUFFICIENT_VALID_FIXES`
- `LOCATION_TOO_INACCURATE`
- `WALKING_CONFLICT`
- `SHORT_STOP_ONLY`
- `STOP_GRACE_NOT_MET`
- `MANUAL_PAUSE_OWNS_STATE`
- `AUTO_TRACKING_DISABLED`
- `FULL_AUTO_CAPABILITY_MISSING`

Los códigos se consideran contrato técnico versionado; el texto humano puede cambiar.

---

## 8. Diagnóstico de GPS y calidad de ruta

### 8.1 Raw Track sigue siendo la evidencia principal

Para investigar una ruta se puede reconstruir:

- timestamp;
- elapsed time;
- accuracy;
- speed y speed accuracy cuando existan;
- bearing;
- altitude;
- provider/source metadata definida por F0.5.

### 8.2 Resúmenes diagnósticos

Para evitar consultas costosas y duplicación se pueden mantener resúmenes derivados:

- accepted/rejected point count;
- median / percentile de accuracy;
- longest gap;
- first-fix latency;
- número de quality degradations;
- número de impossible-jump rejects;
- persistence batches;
- effective sample interval distribution.

Estos datos son derivados y regenerables.

---

## 9. Diagnóstico del proceso y recuperación

En Android 11+ se utilizará `ActivityManager.getHistoricalProcessExitReasons()` / `ApplicationExitInfo` para clasificar salidas recientes del proceso, incluyendo crash, ANR, low memory, permission change, user requested y otros motivos reportados por el sistema.

### 9.1 `setProcessStateSummary`

En API 30+ se podrá escribir un resumen compacto del estado técnico del proceso usando `ActivityManager.setProcessStateSummary()`.

Reglas:

- máximo 128 bytes según la API;
- no contiene PII ni ubicación;
- no se utiliza para restaurar estado;
- se actualiza solo en transiciones significativas, no por cada punto GPS;
- puede incluir versión/estado en formato compacto.

Ejemplo conceptual:

```text
trk=1;cap=Y;det=TRACKING;svc=FG;db=OK;dv=3
```

La restauración real continúa basada en Room.

---

## 10. Debug Screen

Debe existir una pantalla de diagnóstico disponible en builds debug/internal. En una app personal puede existir además un acceso avanzado en release, siempre que no exponga datos sensibles accidentalmente.

### 10.1 Secciones mínimas

**App / build**
- appVersion/versionCode;
- Android API;
- device/model resumido;
- schemaVersion;
- detectorVersion;
- locationProfileVersion;
- processingVersion.

**Capabilities**
- Fine Location;
- Background Location;
- Activity Recognition;
- Notifications;
- Location Services;
- Battery Saver;
- Auto Tracking toggle;
- capability mode efectivo.

**Detector**
- estado actual;
- tiempo en estado;
- último trigger;
- últimas razones;
- última transición Activity Recognition.

**Location**
- age del último fix;
- accuracy;
- speed disponible/no disponible;
- intervalo efectivo reciente;
- gap activo sí/no;
- accepted/rejected counters.

No mostrar coordenadas exactas por defecto en la pantalla general.

**Tracking**
- capture activa sí/no;
- ID abreviado/no reversible para lectura humana;
- foreground service esperado/observado;
- última persistencia exitosa;
- buffer size;
- health state de F0.10.

**Processing**
- pending/running work;
- attempt count;
- último resultado;
- processing version.

**Recovery**
- último process exit reason;
- último recovery action;
- inconsistencias abiertas.

**Events**
- timeline filtrable por categoría/severidad;
- búsqueda por reason code;
- acción “Exportar diagnóstico”.

---

## 11. Paquete diagnóstico exportable

La app debe poder generar un ZIP diagnóstico local mediante acción explícita del usuario.

### 11.1 Contenido por defecto

```text
diagnostic-YYYYMMDD-HHMMSS.zip
├── manifest.json
├── health-snapshot.json
├── diagnostic-events.jsonl
├── process-exits.json
├── capabilities.json
├── versions.json
├── worker-state.json
└── README.txt
```

### 11.2 Exclusiones por defecto

El paquete estándar NO contiene:

- coordenadas GPS;
- Raw Track;
- nombres personalizados de Trips;
- notas;
- fotos;
- dirección de casa/trabajo;
- IDs publicitarios/hardware;
- tokens/credenciales;
- dumps completos de la base de datos.

### 11.3 Adjuntar evidencia de ruta

Cuando investigar un problema GPS requiera datos geográficos, deberá existir una opción separada y explícita, por ejemplo:

> “Incluir datos de ruta en este diagnóstico”.

Esa opción debe advertir que el archivo contendrá ubicaciones precisas.

Para field tests internos se puede usar un export especializado con Raw Track completo, claramente diferenciado del paquete diagnóstico sanitizado.

### 11.4 Sin upload automático

Generar el paquete no lo transmite. El usuario decide dónde guardarlo o con quién compartirlo mediante APIs estándar de Android.

---

## 12. Retención y volumen

Se separan dos políticas:

### 12.1 Eventos de dominio

`CaptureEvent`/Trip lineage necesario para interpretar una captura puede conservarse junto a los datos del Trip mientras exista su historial.

### 12.2 Diagnóstico técnico efímero

Baseline inicial para `DiagnosticEvent`:

- conservar hasta **14 días**;
- máximo objetivo inicial de **20,000 eventos**;
- purgar por antigüedad y luego por capacidad;
- valores sujetos a medición durante Fase 1;
- nunca purgar datos de dominio/raw por esta política.

El objetivo es que un problema reciente sea investigable sin convertir la app en un sistema de logging ilimitado.

### 12.3 Traces de performance

No se capturan continuamente ni se almacenan indefinidamente. Se generan bajo demanda en debug/profile o durante una sesión de investigación.

---

## 13. Logcat y privacidad

Android recomienda evitar datos sensibles en logs y sanitizar los logs de producción.

Política V2:

- `Logcat` detallado solo en debug/internal;
- release no imprime coordenadas, nombres, notas, IDs completos ni dumps de objetos;
- warnings/errors de release usan códigos sanitizados;
- no usar `toString()` de entidades de dominio sensibles como log genérico;
- los eventos diagnósticos persistentes viven en almacenamiento privado de la app;
- R8/log stripping puede usarse como defensa adicional, no como sustituto de diseño seguro.

---

## 14. Performance tracing y herramientas de desarrollo

### 14.1 Perfetto

Perfetto será la herramienta principal para investigar:

- ANRs;
- jank;
- scheduling;
- I/O;
- CPU;
- duración de operaciones complejas;
- interacción entre detector, service, Room y workers.

### 14.2 Custom trace sections

Candidatos:

- `Detector.evaluate`
- `Detector.transition`
- `Tracking.handleLocationBatch`
- `Tracking.persistBatch`
- `Trip.finishTransaction`
- `Processing.buildProcessedTrack`
- `Processing.computeStatistics`
- `Edit.mergeTrips`
- `Edit.splitTrip`
- `Map.simplifyPolyline`

AndroidX Tracing permite etiquetar secciones y correlacionarlas con traces del sistema.

### 14.3 StrictMode

En builds debug se habilitará `StrictMode` para detectar accidentalmente:

- disk read/write en main thread;
- network access inesperado;
- recursos/cursors no cerrados cuando corresponda.

Una violación de StrictMode es evidencia de desarrollo; no es un mecanismo de seguridad ni una métrica de producción.

---

## 15. Observabilidad de WorkManager

Para cada trabajo persistente relevante se debe poder responder:

- cuál es su unique work name;
- qué Trip/Capture procesa;
- qué versión de processing usa;
- cuándo fue enqueued;
- attempt count;
- constraints relevantes;
- startedAt/completedAt;
- result: SUCCESS / RETRY / FAILURE;
- reason code de retry/failure.

No se depende únicamente de `adb dumpsys`; la app conserva evidencia mínima propia para poder investigar después del hecho.

---

## 16. Métricas diagnósticas locales

No son analytics remotos. Son contadores y duraciones útiles para validar calidad.

Ejemplos:

### Detector
- candidates started;
- auto starts confirmed;
- candidates rejected;
- stop candidates;
- cancelled stop candidates;
- confirmed stops;
- manual overrides.

### GPS
- accepted/rejected points;
- first fix latency;
- gaps;
- longest gap;
- effective interval;
- poor-accuracy duration.

### Reliability
- process recoveries;
- duplicate-start attempts suppressed;
- persistence retries;
- buffer high-water mark;
- processing retries;
- unresolved recovery states.

### Field experiment
- start latency;
- stop latency;
- false start;
- false stop;
- missed trip;
- fragmentation;
- battery delta.

Los criterios de aceptación cuantitativos se fijan donde corresponda en F0.6/Fase 1; F0.13 define cómo medirlos.

---

## 17. Flujo de investigación de un incidente

Ejemplo: “La app no registró mi salida de esta mañana.”

1. Verificar capability snapshot.
2. Revisar timeline de Activity Recognition.
3. Revisar si existió `CANDIDATE_START`.
4. Revisar reason codes del rechazo/no transición.
5. Revisar disponibilidad/calidad de ubicación.
6. Revisar estado del foreground service.
7. Revisar process exit history.
8. Revisar persistence/recovery events.
9. Si sigue sin explicación, exportar paquete diagnóstico.
10. Si el problema depende de geometría/GPS, adjuntar explícitamente datos de ruta y convertir el caso en fixture/replay regression cuando sea apropiado.

Resultado deseado:

> El equipo puede identificar la categoría de fallo y reproducirla o acotarla sin depender de una explicación subjetiva del momento.

---

## 18. Integración con Testing y Field Experiments

F0.13 alimenta F0.12 y F0.6:

- synthetic/replay tests deben poder afirmar eventos emitidos;
- un cambio de detector debe producir timelines comparables;
- field sessions capturan las mismas versiones y reason codes;
- bugs reales corregidos deberían transformarse en fixture/test cuando sea reproducible;
- el export diagnóstico debe tener pruebas de schema, sanitización y tamaño.

### 18.1 Tests mínimos de observabilidad

- transición del detector emite un solo evento correcto;
- no se duplica un evento por retry idempotente;
- correlationId conecta comando y resultado;
- un RawTrackPoint normal no genera spam de DiagnosticEvent;
- persist failure genera WARN/ERROR y no filtra coordenadas a logcat;
- process recovery registra exit reason cuando está disponible;
- export estándar excluye route data;
- export con route data exige opción explícita;
- purge técnico no elimina Trip/Capture/RawTrack;
- debug screen puede reconstruirse desde repositories sin convertirse en fuente de verdad.

---

## 19. Fuentes oficiales de plataforma

Fuentes principales consultadas al cerrar este baseline:

- Android `ApplicationExitInfo`: https://developer.android.com/reference/android/app/ApplicationExitInfo
- Android 11 process exit reasons: https://developer.android.com/about/versions/11/features
- `ActivityManager.setProcessStateSummary`: https://developer.android.com/reference/android/app/ActivityManager
- Log information disclosure: https://developer.android.com/privacy-and-security/risks/log-info-disclosure
- Security checklist: https://developer.android.com/privacy-and-security/security-tips
- Perfetto: https://developer.android.com/tools/perfetto
- System tracing overview: https://developer.android.com/topic/performance/tracing
- Custom trace events: https://developer.android.com/topic/performance/tracing/custom-events
- In-process tracing: https://developer.android.com/topic/performance/tracing/in-process-tracing
- StrictMode: https://developer.android.com/reference/android/os/StrictMode

Estas fuentes deben revalidarse cuando se implemente la capa física de diagnóstico si han cambiado las APIs relevantes.

---

## 20. Criterio de cierre

F0.13 se considera cerrado cuando:

- existe separación entre domain evidence y diagnostic evidence;
- existe schema conceptual de eventos estructurados;
- detector/GPS/FGS/Room/Worker/Recovery tienen categorías de eventos;
- las versiones de algoritmo/configuración son parte de la evidencia;
- existe diseño de Debug Screen;
- existe paquete diagnóstico sanitizado y opt-in separado para route data;
- retención técnica está acotada;
- Logcat no es fuente de verdad y no expone ubicación en release;
- ApplicationExitInfo/state summary tienen uso definido;
- Perfetto/Tracing/StrictMode tienen rol definido;
- F0.12 puede probar la observabilidad de forma determinista.

**Decisión v0.1:** F0.13 queda CERRADO como baseline de observabilidad y diagnóstico.

---

## 21. Siguiente workstream

**F0.14 — ADR Baseline**

Debe consolidar las decisiones estructurales ya tomadas en registros breves, versionables y con triggers explícitos de reapertura.
