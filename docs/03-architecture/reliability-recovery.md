# F0.10 — Fiabilidad y Recuperación
## Moto Trip Tracker V2

**Estado:** CERRADO — baseline de fiabilidad v0.1  
**Versión:** 0.1  
**Fecha de corte:** 2026-09-15  
**Depende de:** F0.3, F0.4, F0.5, F0.7, F0.8 y F0.9

---

## 1. Objetivo

F0.10 define qué debe ocurrir cuando la ejecución normal de Moto Trip Tracker se interrumpe mientras existe un viaje activo o trabajo de procesamiento pendiente.

El objetivo no es prometer que Android jamás finalizará un proceso o un servicio. El objetivo es que una interrupción normal o recuperable **no convierta el estado del viaje en una adivinanza**, no genere duplicados y no haga desaparecer silenciosamente datos ya persistidos.

F0.10 cubre:

- recreación de Activity/UI;
- process death iniciado por el sistema;
- crash/ANR;
- reinicio de un foreground service;
- detención explícita por el usuario;
- force stop;
- reboot / pérdida total de energía;
- actualización de la APK;
- cambio o revocación de permisos;
- pérdida temporal de GPS/location;
- fallos de persistencia o almacenamiento;
- finalización parcialmente ejecutada;
- post-processing interrumpido;
- merge/split/delete bajo fallos;
- acciones duplicadas o concurrentes;
- cambios del reloj civil;
- recuperación visible en UX.

---

## 2. Decisión ejecutiva

> **Room y la evidencia persistida son la fuente de verdad. La UI, el proceso Android y el foreground service son ejecutores reemplazables.**

De esta decisión se derivan cinco reglas:

1. Un `TripCapture` activo no depende de que `MainActivity`, un `ViewModel` o una instancia concreta de `Service` continúen vivos.
2. Un service reiniciado **rehidrata** estado desde persistencia; no reconstruye el viaje únicamente a partir del `Intent` que lo inició.
3. Una recuperación nunca inventa puntos GPS durante el intervalo perdido.
4. Una acción explícita del usuario para detener/forzar la app se trata como intención relevante y no debe ser ignorada mediante autorestart agresivo.
5. Todo dato derivado puede regenerarse; la prioridad de recuperación es proteger la evidencia raw y la composición lógica del Trip.

---

## 3. Modelo de fallos

No todas las interrupciones significan lo mismo.

| Clase | Ejemplo | Tratamiento general |
|---|---|---|
| UI recreation | rotación, resize, Activity recreada | Reconsultar source of truth; ninguna transición de negocio. |
| Process death del sistema | LMK, freezer, kill por recursos | Recuperar captura activa si sigue siendo coherente y estamos en el mismo boot. |
| Crash / ANR | excepción no controlada, ANR | Persistido sobrevive; próxima ejecución reconcilia y diagnostica. |
| Service restart | proceso del FGS finalizado por sistema | Service rehidrata desde DB; nunca confía solo en intent antiguo. |
| User stop | Android 13+ Task Manager “Stop” | Respetar intención; no auto-reanudar tracking a ciegas. |
| Force stop | Settings → Force stop | El paquete no puede autoarrancarse hasta acción explícita; reconciliar al próximo inicio. |
| Reboot / power loss | reinicio, batería agotada | Existe discontinuidad monotónica; no fingir continuidad. |
| Permission change | ubicación/activity recognition revocados | Verificar capacidades antes de recuperar; degradar o interrumpir. |
| Location outage | GPS off, túnel, fix perdido | Mantener captura si procede, registrar gap, no sintetizar ruta. |
| Persistence failure | disco lleno, SQLite error | Estado DEGRADED explícito; buffer acotado; nunca descartar silenciosamente. |
| Processing failure | Worker cancelado/crash | Raw y Trip permanecen; processing es reintentable/idempotente. |
| Concurrent command | doble Finish, Pause repetido | Validación por estado + operación idempotente. |

---

## 4. Invariantes de fiabilidad

Estas reglas son contractuales para Core V2.

### REL-INV-001 — Un solo dueño lógico activo

En Core existe como máximo una `TripCapture` con `CaptureStatus.ACTIVE`.

Un restart de service o una acción duplicada no puede crear una segunda captura silenciosa.

### REL-INV-002 — Persistencia antes de confirmación

La UI/notification no debe afirmar que un cambio crítico quedó aplicado hasta que el cambio mínimo requerido haya sido aceptado por la capa persistente.

Aplica al menos a:

- Start;
- Pause;
- Resume;
- Finish;
- Merge;
- Split;
- Delete/Trash.

### REL-INV-003 — Service no es source of truth

Si una instancia de `TrackingForegroundService` desaparece, la captura no desaparece con ella.

### REL-INV-004 — Pérdida potencial acotada

Los puntos aún no persistidos pueden existir en un buffer corto/acotado.

Ante process death abrupto, el máximo dato en riesgo debe limitarse al contenido de ese buffer y nunca a minutos de ruta mantenidos únicamente en RAM.

El tamaño/tiempo exacto del batch se valida en W0/EXP y F0.12.

### REL-INV-005 — Sin pérdida silenciosa

Si ocurre overflow, error de escritura o descarte inevitable de datos no persistidos, el sistema debe generar una condición diagnóstica/quality degradation cuando vuelva a ser capaz de persistir o informar al usuario.

### REL-INV-006 — Raw antes que derivados

Una falla al producir `ProcessedTrack`, estadísticas, stops o polyline no puede borrar ni invalidar el Raw Track válido.

### REL-INV-007 — Comandos idempotentes

Repetir el mismo comando o recibirlo tarde no debe causar efectos duplicados.

Ejemplos:

- `Finish` sobre una captura ya cerrada = no crea otro Trip;
- `Pause` estando ya pausado = no abre dos pausas;
- `Resume` estando TRACKING = no crea intervalos inconsistentes;
- un `PendingIntent` viejo no revive una captura `COMPLETED`.

### REL-INV-008 — Operaciones compuestas atómicas

Merge, Split, boundary edit y cierre de captura deben aplicar sus mutaciones estructurales dentro de transacciones de base de datos apropiadas.

Room ofrece transacciones donde el bloque se confirma solo si termina correctamente; una excepción/cancelación provoca rollback.

### REL-INV-009 — No continuidad ficticia

Un intervalo sin evidencia de ubicación se representa como gap/interrupción; no se interpola como si el recorrido real fuese conocido.

### REL-INV-010 — Reloj monotónico para duración

Dentro del mismo boot, duración/orden temporal crítico utiliza `elapsedRealtimeNanos` o equivalente monotónico.

`System.currentTimeMillis()`/hora civil se conserva para presentación/auditoría, no como única base de duración.

---

## 5. Qué debe persistirse para poder recuperar

La recuperación mínima debe poder deducirse de Room sin depender de un singleton o memoria estática.

### 5.1 Fuente existente

F0.7 ya proporciona:

- `TripCapture.status`;
- `TripCapture.startedAt` / `startElapsedRealtimeNanos`;
- `RawTrackPoint.sequenceNumber` y tiempos;
- `CaptureEvent`;
- `ManualPauseInterval`;
- `Trip` / `TripPart`;
- versiones de detector/location/processing.

### 5.2 Checkpoint lógico

No se exige todavía una tabla llamada literalmente `TrackingCheckpoint`, pero la persistencia física debe permitir reconstruir al menos:

```text
activeCaptureId
lastPersistedSequenceNumber
lastPersistedElapsedRealtimeNanos
lastDetectorState
manualPauseOpen / pauseStart
lastMeaningfulMotionTime
candidateStartSince? / candidateStopSince? cuando sea necesario
lastKnownCapabilityMode
```

Preferencia de diseño: utilizar datos/eventos persistidos y un checkpoint mínimo, evitando duplicar la misma verdad en múltiples lugares.

### 5.3 Estado de processing

El estado de procesamiento derivado debe ser persistente o reconstruible:

```text
PENDING
PROCESSING
READY
FAILED_RETRYABLE
FAILED_TERMINAL
```

El nombre físico final se define al implementar Room. Esto es una refinación aditiva compatible con F0.7.

---

## 6. UI recreation y navegación

Una recreación de Activity, cambio de configuración o reconstrucción del back stack **no es un evento de negocio**.

Reglas:

- no cambia `TripCapture.status`;
- no pausa ni finaliza;
- no reinicia location requests;
- no crea nueva captura;
- `ViewModel` reconsulta repositories;
- `SavedStateHandle`/saved state solo guarda estado UI pequeño, por ejemplo filtros o IDs.

Android documenta que `ViewModel` no sobrevive a process death y que la persistencia local es la opción correcta para datos complejos que deben sobrevivir a cierre/reinicio.

---

## 7. Process death iniciado por el sistema

### 7.1 Mismo boot

Si el proceso muere y luego vuelve dentro del mismo boot:

1. consultar si existe `TripCapture.ACTIVE`;
2. comprobar que `elapsedRealtime` actual es compatible con los valores persistidos;
3. verificar permisos/capabilities actuales;
4. recuperar el estado lógico mínimo;
5. reanudar location tracking si el contexto Android lo permite;
6. registrar `PROCESS_RECOVERED`/evento equivalente;
7. crear un `LocationGap` si hubo intervalo sin fixes válidos;
8. continuar usando **la misma captura**, no crear otra por defecto.

### 7.2 Sticky foreground service

El `TrackingForegroundService` puede devolver `START_STICKY` como baseline candidato.

Android documenta que, si un started service que devuelve `START_STICKY` es finalizado por el sistema, el sistema intentará recrearlo y volverá a llamar `onStartCommand`, posiblemente con `Intent == null`. Desde Android 12, la restricción general de iniciar FGS desde background no impide el restart de un sticky FGS.

Consecuencia:

> El service debe poder arrancar correctamente con `Intent == null` leyendo Room.

Un restart sticky es una ayuda, no la única estrategia de recovery.

### 7.3 Validación al recrear el service

Al recrearse:

```text
¿Existe ACTIVE capture?
  no -> stopSelf()
  sí
   ↓
¿capabilities/permisos válidos?
  no -> persistir/declarar degraded; no fingir tracking sano
  sí
   ↓
rehidratar estado + location updates + notification
```

---

## 8. Crash y ANR

El comportamiento de negocio tras crash/ANR se basa en lo ya persistido.

En el próximo proceso:

- consultar `ApplicationExitInfo` cuando la API esté disponible;
- registrar localmente el reason técnico si aún no se procesó;
- reconciliar `TripCapture.ACTIVE` igual que cualquier process death recuperable;
- no atribuir automáticamente todos los gaps a “GPS malo” cuando el proceso estuvo muerto.

Android `ApplicationExitInfo` diferencia causas como `REASON_CRASH`, `REASON_ANR`, `REASON_LOW_MEMORY`, `REASON_PERMISSION_CHANGE` y `REASON_USER_REQUESTED`.

F0.13 definirá cuánto de esta información se conserva y cómo se exporta en diagnóstico.

---

## 9. User Stop y Force Stop

Este caso se trata distinto de un kill accidental.

### 9.1 Task Manager de Android 13+

Cuando el usuario pulsa **Stop** para una app con foreground service:

- Android elimina el proceso completo;
- elimina el back stack;
- quita la notification del FGS;
- no envía callback a la app.

Por tanto, no existe un `onStopByUser()` confiable donde guardar un último estado.

### 9.2 Política de Moto Trip Tracker

Cuando el proceso vuelva a iniciarse y la última salida sea compatible con `REASON_USER_REQUESTED`:

- no auto-reanudar ciegamente una captura previa;
- reconciliar la captura como **interrumpida por usuario/sistema**;
- presentar una opción clara para conservar el parcial y, si sigue conduciendo, iniciar/continuar de forma explícita;
- Auto Tracking puede volver a operar posteriormente, pero no debe usar el evento de recuperación para revivir de inmediato el FGS que el usuario acaba de detener.

Se debe guardar un identificador/timestamp del último exit reason procesado para no aplicar la misma decisión repetidamente.

### 9.3 Force Stop desde Settings

Cuando una app entra en stopped state por force stop, Android impide que el paquete se autoarranque hasta que exista una solicitud explícita para iniciar uno de sus componentes.

Moto Trip Tracker **no intenta eludir esta decisión**.

Al próximo inicio explícito:

- detectar la captura ACTIVE abandonada;
- ofrecer/sellar el parcial;
- verificar Auto Tracking y permisos antes de volver a registrar comportamiento normal.

---

## 10. Reboot, batería agotada y pérdida total de energía

Un reboot rompe la continuidad de `elapsedRealtime`.

### 10.1 Regla de discontinuidad

Si el valor monotónico actual es menor/incompatible con el último `elapsedRealtimeNanos` persistido, se considera que ocurrió un reboot o cambio de dominio monotónico.

No se intenta calcular una duración continua usando ambos valores monotónicos.

### 10.2 Captura ACTIVE encontrada después de reboot

Baseline seguro:

1. preservar todos los RawTrackPoints existentes;
2. registrar el motivo de recuperación cuando sea posible;
3. **no asumir que el viaje continuó durante el reboot**;
4. cerrar/reconciliar la captura previa como interrumpida (`ABORTED` o política física equivalente) usando la última evidencia persistida, no una hora final inventada;
5. hacer visible que el registro quedó parcial/interrumpido;
6. el siguiente movimiento válido crea una nueva captura;
7. el usuario puede fusionar ambos Trips posteriormente; una sugerencia automática de merge puede ser P1.

Esta política prioriza honestidad del dato sobre continuidad estética.

### 10.3 BOOT_COMPLETED

El receiver de boot se utiliza para:

- re-registrar Activity Recognition cuando corresponda;
- reconciliar capturas huérfanas;
- restaurar capacidad pasiva.

No se usa como regla universal para arrancar inmediatamente un location FGS y afirmar que el Trip continúa.

---

## 11. Actualización de la aplicación

`ACTION_MY_PACKAGE_REPLACED` / package update puede matar el proceso y obliga a re-registrar capacidades pasivas según F0.4.

Regla:

- conservar DB mediante migraciones;
- nunca usar destructive migration en release para “arreglar” un esquema;
- reconciliar una captura ACTIVE;
- si la captura puede continuar dentro del mismo boot y los permisos siguen válidos, recovery puede intentar rehidratarla;
- si existe incertidumbre significativa, marcar gap/interrupción en vez de inventar continuidad.

Las migraciones se validarán en F0.12.

---

## 12. Cambios de permisos y capacidades durante un Trip

Android puede finalizar el proceso cuando cambian permisos runtime; `ApplicationExitInfo` incluye `REASON_PERMISSION_CHANGE`.

### 12.1 Ubicación precisa degradada/revocada

Si el tracking activo pierde el permiso necesario:

- no continuar mostrando estado “Healthy”;
- conservar la captura y datos ya guardados;
- detener/reconfigurar location requests según capacidad restante;
- crear evento de capability change;
- mostrar estado degradado al usuario cuando sea posible;
- no sintetizar coordenadas.

### 12.2 Activity Recognition revocado

Durante un Trip ya activo, la falta de Activity Recognition no elimina los puntos GPS ya existentes.

Puede degradar la lógica de auto-stop/auto-start futuro. El detector debe conocer la ausencia de señal y no tratarla como `STILL`.

### 12.3 Permiso restaurado

Restaurar permiso no debe crear una segunda captura.

Si la captura sigue válida y la política permite recovery, se continúa con gap explícito.

---

## 13. Pérdida de GPS / location

La ausencia de fixes no equivale a “el usuario está detenido”.

### 13.1 Gap

Cuando el sistema supera el criterio de “fix esperado pero no válido”, se registra/infiere:

```text
GPS_GAP_STARTED
...
GPS_GAP_ENDED
```

El `LocationGap` derivado conserva:

- inicio;
- fin;
- duración;
- motivo conocido/probable si existe;
- puntos válidos antes/después.

### 13.2 Durante el gap

La captura puede permanecer ACTIVE.

El detector puede usar otras señales disponibles, pero:

- no asume distancia cero;
- no suma distancia directa entre extremos del gap como ruta real si eso produciría una inferencia engañosa;
- no calcula velocidad máxima dentro del intervalo sin evidencia;
- no finaliza únicamente porque dejaron de llegar puntos.

### 13.3 Fin del viaje dentro de un gap

Si otras señales permiten concluir que el Trip terminó, puede finalizar con quality flag/diagnóstico que indique localización incompleta.

El endpoint mostrado será “última ubicación válida” o estado equivalente, no una coordenada inventada.

---

## 14. Fallos de almacenamiento / Room

### 14.1 Error transitorio de escritura

Si una inserción de TrackPoints falla:

1. mantener un buffer acotado;
2. reintentar según política corta;
3. señalar estado de persistencia degradado;
4. no afirmar que esos puntos están guardados;
5. al recuperarse, persistir en orden y registrar el intervalo afectado.

### 14.2 Buffer lleno

El sistema no puede almacenar infinitamente en RAM.

Si el buffer alcanza su límite:

- aplicar una política explícita de overflow definida/medida en Fase 1;
- elevar `DATA_LOSS_DETECTED`/evento equivalente cuando sea posible;
- crear gap/quality degradation correspondiente;
- mostrar advertencia al usuario si el estado persiste.

No habrá descarte silencioso.

### 14.3 Disco lleno

Si SQLite/storage falla por falta de espacio:

- el Trip no se marca como sano/completo;
- la app entra en estado crítico de persistencia;
- se mantiene únicamente la cantidad de datos que el buffer acotado permita;
- se notifica cuando sea posible;
- el sistema debe evitar ciclos infinitos de escritura que consuman CPU/batería.

### 14.4 Base no abrible/corrupta

Core V2 no “soluciona” corrupción borrando automáticamente la base.

En release:

- no destructive reset automático;
- entrar a safe/error state;
- preservar archivo cuando sea posible para diagnóstico/recuperación;
- ofrecer acciones de recuperación solo cuando estén diseñadas y probadas.

La estrategia de backup/restauración se cierra en F0.11.

---

## 15. Finalización de un Trip

Finish es una transición crítica y debe ser idempotente.

### 15.1 Orden lógico

```text
1. serializar comando Finish para activeCaptureId
2. detener/adquirir último input según política
3. flush del buffer pendiente
4. transacción Room:
   - cerrar pausa abierta si aplica
   - CaptureEvent final
   - TripCapture COMPLETED
   - endedAt/endElapsedRealtime
   - Trip/TripPart inicial si aún no existe
   - marcar processing PENDING
5. confirmar estado terminado a UI/notification
6. encolar processing único
7. detener foreground service
```

### 15.2 Si falla antes de commit

La captura **no** se considera completada.

Recovery encontrará una captura ACTIVE y podrá reconciliarla/reintentar Finish.

### 15.3 Si falla después del commit

La captura ya está completa. El error se limita a processing/UI/service cleanup.

No debe existir una segunda finalización.

---

## 16. Post-processing resiliente

El procesamiento de un Trip completado es diferible y se ejecuta fuera del live tracking.

### 16.1 WorkManager

WorkManager es apropiado porque el trabajo programado persiste en su almacenamiento administrado y se reprograma a través de reboots; además ofrece retry/backoff y unique work.

### 16.2 Clave de idempotencia

El processing debe identificarse conceptualmente por:

```text
tripId/captureId + processingVersion
```

Ejecutar dos veces el mismo processing no debe duplicar:

- processed points;
- statistics;
- gaps;
- stops;
- summaries.

### 16.3 Publicación atómica de derivados

Regla recomendada:

1. leer Raw consistente;
2. calcular fuera de la transacción DB cuando sea CPU-heavy;
3. abrir transacción corta;
4. sustituir/publicar el conjunto de derivados de la `processingVersion`;
5. marcar estado `READY`.

No mostrar un conjunto parcialmente actualizado como versión terminada.

### 16.4 Failure states

- `FAILED_RETRYABLE`: error temporal, Worker puede retry.
- `FAILED_TERMINAL`: datos/algoritmo no permiten procesar; Trip sigue disponible con Raw/basic metadata.

La app nunca elimina el Trip porque falló una estadística.

---

## 17. Merge, Split, Boundary Edit y Delete

### 17.1 Transacción estructural primero

Operaciones de biblioteca cambian composición lógica, no Raw TrackPoints.

Merge/Split deben:

- crear nuevos Trips/TripParts/lineage;
- cambiar status de origen;
- invalidar derivados;
- commit atómico;
- luego encolar procesamiento.

### 17.2 Fallo antes del commit

No debe haber cambio visible parcial.

### 17.3 Fallo después del commit pero antes de processing

El nuevo Trip existe y aparece como “Procesando”/estado equivalente. Los Trips origen quedan en el status acordado y lineage permite reconstruir la operación.

### 17.4 Delete/Trash

Trash es preferido a borrado físico inmediato.

Una interrupción no puede dejar Raw TrackPoints huérfanos si aún son necesarios por lineage/recovery.

La purga física se diseña como operación separada y segura.

---

## 18. Concurrencia y acciones duplicadas

### 18.1 Serialización por captura

Los comandos que modifican el lifecycle de una captura deben serializarse mediante un owner único/mutex/actor o mecanismo equivalente en la implementación.

No se permitirá que `Pause` y `Finish` muten simultáneamente sin orden definido.

### 18.2 Revalidación dentro de transacción

No basta con validar estado solo en memoria.

Antes de aplicar una mutación crítica, repository/DB vuelve a comprobar la condición relevante.

Ejemplo conceptual:

```text
Finish(captureId)
  ↓
DB: ¿capture.status == ACTIVE?
  no -> NO-OP / resultado ya finalizado
  sí -> commit de cierre
```

### 18.3 Notification PendingIntents

Las acciones de notification deben incluir la identidad de captura esperada.

Un intent tardío para `captureId=A` no afecta `captureId=B` aunque B sea actualmente activo.

---

## 19. Reloj, timezone y cambio manual de hora

### 19.1 Duraciones

Usar tiempo monotónico para:

- duración activa;
- pausa;
- candidate timers;
- orden temporal dentro de un boot.

### 19.2 Hora civil

Conservar timestamps civiles para:

- mostrar fecha/hora;
- exportación;
- agrupación por día;
- auditoría.

### 19.3 Cambio de timezone/hora

Un cambio de timezone o ajuste manual del reloj durante el viaje:

- no altera duración monotónica;
- puede cambiar la representación civil posterior;
- debe conservarse suficiente contexto para evitar duraciones negativas.

### 19.4 Reboot

No comparar monotonic timestamps de boots diferentes como si compartieran origen.

---

## 20. Recovery Matrix

| Escenario | Raw persistido | ¿Misma captura? | Acción automática | Estado visible esperado |
|---|---:|---:|---|---|
| Activity recreada | Sí | Sí | Reconsultar DB | Trip activo normal |
| App UI cerrada | Sí | Sí | FGS continúa | Trip activo normal |
| Process death sistema, mismo boot | Sí | Sí | Sticky/reconcile + gap | Recuperado / normal tras validar |
| Crash, mismo boot | Sí | Preferentemente sí | Reconcile al próximo start/restart | Recovery diagnosticado |
| Android Task Manager Stop | Sí hasta último commit | No auto-resume ciego | Respetar user stop; reconciliar al volver | Trip interrumpido/recovery |
| Force Stop Settings | Sí hasta último commit | No hasta acción explícita | Ningún autoarranque | Trip parcial al reabrir |
| Reboot / power loss | Sí hasta último commit | No por baseline | Cerrar/sellar previo como interrumpido; rearmar detección | Parcial/interrumpido |
| GPS temporalmente perdido | Sí | Sí | Mantener capture + gap | Tracking degradado |
| Precise location revocada | Sí | Depende capabilities | Parar/reconfigurar location | Degraded / intervención |
| Activity Recognition revocado | Sí | Sí si GPS activo | Continuar con menos señal | Auto features degradadas |
| DB write error transitorio | Parcial | Sí mientras buffer aguante | Retry acotado | Persistence degraded |
| Storage lleno | Parcial | Riesgo alto | Buffer acotado + warning | Error crítico |
| Processing Worker muere | Sí | N/A | WorkManager reintenta/reanuda | Trip visible, “Procesando” |
| Merge/Split crash antes commit | Sí | N/A | Rollback | Estado previo intacto |
| Merge/Split crash después commit | Sí | N/A | Reprocessing pendiente | Nuevo estado estructural válido |

---

## 21. Estados UX de recuperación

F0.9 evita convertir mensajes técnicos en la interfaz. F0.10 define cuatro estados conceptuales que la UX puede traducir:

### HEALTHY

Tracking y persistencia funcionan dentro del baseline esperado.

### DEGRADED

El Trip continúa, pero falta una señal o existen gaps/accuracy baja.

Ejemplos:

- GPS temporalmente no disponible;
- Activity Recognition ausente durante capture activa;
- approximate location.

### RECOVERY_REQUIRED

Existe una captura previa que no puede continuarse de forma segura sin reconciliación.

Ejemplos:

- user stop;
- force stop;
- reboot;
- permiso crítico removido.

### PERSISTENCE_CRITICAL

La app no puede garantizar que nuevos datos se estén guardando.

Debe comunicarse con prioridad alta cuando sea posible.

---

## 22. Política de “partial trip”

Un registro incompleto es preferible a un registro inventado o desaparecido.

Por tanto:

- una captura `ABORTED` puede producir un Trip visible parcial;
- el Trip puede mostrar quality/integrity warning derivado;
- merge permite reconstruir una experiencia lógica si posteriormente existe otra captura;
- estadísticas que requieren continuidad no deben presentarse con falsa precisión;
- el usuario puede conservar, fusionar o eliminar el parcial.

No se añade un nuevo `TripStatus` solo para esto en F0.10; la integridad parcial puede representarse mediante capture status + eventos + quality summary derivado. F0.7 no necesita reabrirse estructuralmente.

---

## 23. Durabilidad de SQLite/Room

Room ofrece transacciones para mutaciones múltiples. Una transacción se marca exitosa únicamente si el bloque termina correctamente; excepción/cancelación causa rollback.

Android recomienda WAL para rendimiento SQLite general. La política física exacta de `synchronous` no se optimizará agresivamente en Fase 0: para un tracker, la durabilidad de puntos recientes es más importante que ganar throughput marginal sin medir.

Regla:

> No cambiar PRAGMA/journal/synchronous desde defaults/Room sin benchmark de Fase 1 y ADR explícito.

---

## 24. ApplicationExitInfo y diagnósticos

En API 30+ puede consultarse historial de terminaciones mediante `ActivityManager.getHistoricalProcessExitReasons()` / `ApplicationExitInfo`.

Usos permitidos:

- distinguir crash/ANR/low-memory/user-request/permission-change;
- explicar gaps o recovery;
- medir confiabilidad en F0.12/F0.13;
- evitar auto-resume tras user stop.

No es source of truth del Trip y no está garantizado que todos los OEM reporten cada causa con igual detalle.

`setProcessStateSummary()` puede evaluarse en F0.13 como ayuda diagnóstica pequeña, nunca como persistencia principal.

---

## 25. Recovery al arrancar el proceso

Toda entrada al proceso (Activity, receiver, sticky service, Worker) debe poder ejecutar una reconciliación liviana/idempotente.

Orden conceptual:

```text
1. abrir persistencia
2. inspeccionar última exit info nueva (si aplica)
3. buscar ACTIVE capture
4. verificar monotonic continuity
5. verificar capabilities/permisos
6. verificar open pause / last detector state
7. decidir:
   - NONE
   - RESUME_SAME_CAPTURE
   - MARK_DEGRADED
   - REQUIRE_USER_RECOVERY
   - SEAL_INTERRUPTED_CAPTURE
8. emitir evento diagnóstico una sola vez
9. continuar con el componente que motivó el arranque
```

La reconciliación debe ser idempotente: ejecutarla desde Activity y luego desde service no produce dos decisiones incompatibles.

---

## 26. Escenarios obligatorios para F0.12

F0.12 deberá convertir al menos estos casos en pruebas automatizadas/instrumentadas o procedimientos reproducibles:

1. Activity recreate durante tracking.
2. Swipe UI / app background con FGS activo.
3. `adb shell am kill <package>` o equivalente de process kill recuperable.
4. crash inducido con batch de TrackPoints pendiente.
5. sticky service restart con `Intent == null`.
6. Task Manager Stop (`adb shell cmd activity stop-app PACKAGE_NAME`).
7. Force stop y relaunch manual.
8. reboot con `TripCapture.ACTIVE`.
9. permiso de location revocado durante Trip.
10. GPS/location desactivado y restaurado.
11. buffer/DAO insert falla temporalmente.
12. storage exhaustion simulado cuando sea viable.
13. Finish duplicado desde UI + notification.
14. Pause y Finish simultáneos.
15. worker de processing cancelado y reintentado.
16. merge crash antes de commit.
17. merge committed con processing pendiente.
18. migration con captura histórica existente.
19. cambio manual de hora/timezone durante Trip.
20. abrir Trip parcialmente interrumpido sin mapa/processed track listo.

---

## 27. Métricas de fiabilidad

F0.12 fijará targets, pero F0.10 define qué medir:

| Métrica | Definición |
|---|---|
| `PersistedPointLoss` | Puntos aceptados por pipeline que no llegaron a persistencia. |
| `RecoverySuccessRate` | Active captures recuperables reanudadas/reconciliadas correctamente. |
| `DuplicateCaptureRate` | Capturas duplicadas generadas por restart/concurrencia. |
| `DuplicateFinishRate` | Más de una finalización lógica para la misma captura. |
| `OrphanRawPointCount` | Raw points sin capture válida. Debe tender a 0. |
| `PartialOperationCount` | Merge/Split/Finish visibles en estado estructural incompleto. Debe ser 0. |
| `ProcessingRecoveryRate` | Processing pendiente que alcanza READY tras interrupción. |
| `UnexplainedGapRate` | Gaps sin razón/diagnóstico suficiente. |
| `UserStopAutoRestartViolations` | Veces que tracking se revive contra intención de Stop/force stop. Debe ser 0. |

---

## 28. Decisiones aceptadas F0.10

### REL-001 — Persistencia es la verdad del Trip

**Estado:** ACEPTADO.

### REL-002 — TrackingForegroundService candidato a START_STICKY

**Estado:** ACEPTADO como baseline de implementación a validar.

El restart debe rehidratar desde DB y soportar `Intent == null`.

### REL-003 — Same-boot process death intenta recuperar la misma capture

**Estado:** ACEPTADO.

### REL-004 — Reboot no finge continuidad

**Estado:** ACEPTADO.

Captura previa se conserva como parcial/interrumpida; nueva conducción produce nueva captura.

### REL-005 — User stop/force stop se respeta

**Estado:** ACEPTADO.

No se diseña autorestart para derrotar una acción explícita del usuario.

### REL-006 — Finish/Merge/Split son atómicos e idempotentes

**Estado:** ACEPTADO.

### REL-007 — Processing usa trabajo único e idempotente

**Estado:** ACEPTADO.

### REL-008 — Gaps se representan; no se sintetizan

**Estado:** ACEPTADO.

### REL-009 — Fallo de persistencia nunca queda silencioso

**Estado:** ACEPTADO.

### REL-010 — No destructive DB recovery automático

**Estado:** ACEPTADO.

---

## 29. Decisiones diferidas

F0.10 no fija todavía:

- duración/tamaño exacto del buffer de RawTrackPoint;
- cantidad exacta de retries inmediatos de DB;
- política de overflow específica (drop oldest/newest/stop) antes de benchmark;
- texto visual exacto de “Trip interrumpido”;
- retención exacta de partial Trips;
- política de auto-sugerencia para merge tras reboot;
- backups y restauración física;
- export de diagnóstico;
- targets numéricos de recovery/point loss;
- comportamiento OEM-specific más allá de pruebas reales.

Estos puntos pertenecen a F0.11–F0.13 y W0/EXP.

---

## 30. Fuentes técnicas oficiales consultadas

Fecha de corte: 2026-09-15.

1. Android Developers — Save UI states / local persistence vs ViewModel and SavedState.  
   https://developer.android.com/topic/libraries/architecture/saving-states
2. Android Developers — `Service` API / `START_STICKY` semantics.  
   https://developer.android.com/reference/android/app/Service
3. Android Developers — Handle user-initiated stopping of apps running foreground services.  
   https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping
4. Android Developers — `ApplicationExitInfo`.  
   https://developer.android.com/reference/android/app/ApplicationExitInfo
5. Android Developers — Foreground service background-start restrictions.  
   https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
6. Android Developers — Persistent background work / WorkManager.  
   https://developer.android.com/develop/background-work/background-tasks/persistent
7. Android Developers — WorkManager API / unique work.  
   https://developer.android.com/reference/androidx/work/WorkManager
8. Android Developers — Room `withTransaction`.  
   https://developer.android.com/reference/androidx/room/RoomDatabaseKt
9. Android Developers — SQLite performance / WAL guidance.  
   https://developer.android.com/topic/performance/sqlite-performance-best-practices
10. Android Developers — `Intent.ACTION_PACKAGE_UNSTOPPED` / stopped package behavior.  
    https://developer.android.com/reference/android/content/Intent
11. Android Developers — Android 15 foreground service boot restrictions.  
    https://developer.android.com/about/versions/15/behavior-changes-15

---

## 31. Criterio de cierre

F0.10 queda cerrado cuando:

- [x] source of truth ante process death está definido;
- [x] restart/recovery del foreground service está definido;
- [x] user stop y force stop tienen política explícita;
- [x] reboot/power loss no produce continuidad ficticia;
- [x] permission changes y GPS gaps tienen comportamiento definido;
- [x] persistence failure y buffer overflow no quedan silenciosos;
- [x] Finish tiene orden/atomicidad definidos;
- [x] processing es persistente, único e idempotente;
- [x] Merge/Split/Delete tienen política bajo fallo;
- [x] concurrencia y intents tardíos están delimitados;
- [x] reloj civil vs monotónico está resuelto;
- [x] existe Recovery Matrix;
- [x] escenarios obligatorios para F0.12 están enumerados;
- [x] métricas de fiabilidad están definidas;
- [x] decisiones diferidas están explícitas.

### Decisión v0.10

> **F0.10 queda CERRADO como baseline de fiabilidad y recuperación.**

El siguiente workstream es **F0.11 — Privacidad y permisos**, que debe convertir ubicación sensible, background location, notificaciones, exportación, backup, privacy zones y publicación futura en una política coherente de datos/permisos.
