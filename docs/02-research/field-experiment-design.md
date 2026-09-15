# F0.6 — Field Experiment Design
## Moto Trip Tracker V2

**Estado:** CERRADO baseline de diseño  
**Versión:** 0.1  
**Fecha:** 2026-09-15  
**Proyecto:** Moto Trip Tracker V2  
**Depende de:** F0.1–F0.5

---

## 1. Objetivo

Diseñar un protocolo de experimentación reproducible que permita validar, con recorridos reales, las hipótesis de F0.3–F0.5 antes de congelar thresholds y parámetros de producción.

F0.6 define **qué deberá medir el prototipo diagnóstico, cómo se ejecutarán las pruebas, qué escenarios son obligatorios, cómo se etiquetará el ground truth y cómo se compararán configuraciones**.

F0.6 **no ejecuta todavía los recorridos** porque Fase 0 permanece en documentación/investigación y aún no existe el V2 diagnostic harness. La ejecución real se convertirá en un gate temprano de Fase 1/W0 antes de construir el detector de producción.

### Decisión principal

> Los thresholds de Auto Start/Auto Stop, sampling GPS, min distance, batching y filtros de calidad no se elegirán por intuición. Se escogerán después de ejecutar este protocolo con la versión diagnóstica de V2.

---

## 2. Alcance

F0.6 cubre:

- metodología de pruebas en motocicleta;
- pruebas negativas sin motocicleta;
- instrumentación requerida;
- perfiles experimentales de location;
- ground truth y anotaciones;
- métricas de detección y tracking;
- pruebas de batería;
- pruebas de gaps y degradación;
- formato del dataset;
- criterios para escoger configuración;
- reglas de repetición y control de variables;
- salida esperada para cerrar thresholds posteriormente.

F0.6 no cubre:

- implementación del diagnostic harness;
- ejecución física de las rutas;
- thresholds definitivos de producción;
- arquitectura final de datos;
- UI de usuario final;
- release/publicación.

---

## 3. Principio experimental

Los experimentos deben cambiar **una variable importante a la vez** siempre que sea posible.

Ejemplo incorrecto:

```text
Perfil A = 1 s + 0 m + no batching
Perfil B = 5 s + 10 m + batching 15 s
```

Si B empeora, no sabremos qué variable causó el problema.

El protocolo preferirá campañas escalonadas:

```text
1. Intervalo
2. Min update distance
3. Batching
4. Candidate-start burst
5. Candidate-stop behavior
6. Battery comparison
7. Combined candidate profile
```

---

## 4. Preguntas que debe responder la ejecución

### Detección

1. ¿Qué latencia real tiene Activity Recognition para entrar/salir de `IN_VEHICLE` en motocicleta?
2. ¿Con qué frecuencia una moto se clasifica como `IN_VEHICLE`, `ON_BICYCLE`, `STILL`, `UNKNOWN` u otra actividad?
3. ¿El teléfono montado en manillar se clasifica diferente a llevarlo en bolsillo/chaqueta?
4. ¿Qué señales permiten confirmar un inicio sin depender de una sola muestra GPS?
5. ¿Cuánto tiempo debe tolerarse una detención sin cerrar el Trip?
6. ¿Qué comportamiento aparece en presas severas y movimiento stop-and-go?
7. ¿Qué falsos positivos aparecen caminando, en automóvil, bus o bicicleta?

### Tracking

8. ¿Qué intervalo conserva bien curvas, rotondas y calles urbanas sin gasto excesivo?
9. ¿Qué efecto real tiene `minUpdateDistance` sobre fidelidad y callbacks?
10. ¿Qué efecto tiene batching pequeño sobre geometría, latencia y batería?
11. ¿Qué accuracy/quality flags caracterizan los puntos que realmente deforman la ruta?
12. ¿Cuánto cambia la distancia calculada entre perfiles?
13. ¿Qué tan estable es la velocidad reportada y qué spikes deben filtrarse?
14. ¿La altitud raw es suficientemente estable para min/max? ¿Qué smoothing requiere ascent/descent?

### Fiabilidad

15. ¿Qué ocurre al apagar pantalla, cambiar de app o dejar la app horas en background?
16. ¿Qué ocurre con pérdida temporal de GPS, túneles, parqueos cubiertos o cañones urbanos?
17. ¿Qué gaps aparecen por OEM/batería?
18. ¿Se preserva el orden de puntos y eventos después de batching o retrasos?

### Energía

19. ¿Cuál es el coste relativo de cada perfil de tracking activo?
20. ¿Cuál es el coste del modo pasivo/Auto Tracking durante horas sin viaje?
21. ¿Existe una configuración más eficiente que preserve calidad equivalente a la de referencia?

---

## 5. Diagnostic Harness requerido para ejecutar F0.6

La primera implementación experimental de V2 no debe intentar ser la aplicación final.

Debe existir un **Diagnostic Harness** pequeño cuyo objetivo sea recolectar evidencia.

Funciones mínimas:

- iniciar/finalizar una sesión experimental manual;
- seleccionar `experimentProfileId`;
- mostrar estado actual del detector;
- registrar Activity Recognition;
- registrar Raw TrackPoints sin filtrar;
- registrar todas las transiciones del state machine experimental;
- registrar cambios de permisos/settings relevantes;
- crear markers de prueba cuando el usuario está detenido;
- exportar el dataset completo;
- nunca modificar silenciosamente los datos raw;
- permitir apagar la pantalla durante la prueba;
- mostrar claramente cuando location/Activity Recognition dejan de estar disponibles.

### Regla

El harness **no necesita** Home, historial bonito, mapas finales, favoritos, merge/split ni analytics de producto.

Su valor es producir datos verificables.

---

## 6. Contrato mínimo de logging

Cada sesión debe poder reconstruirse cronológicamente.

### 6.1 Session metadata

Como mínimo:

```text
sessionId
startedAt
endedAt
appVersion
diagnosticSchemaVersion
experimentProfileId
detectorVersion
phoneManufacturer
phoneModel
androidVersion
playServicesVersion (si disponible)
batterySaverState
locationSettingsState
preciseLocationGranted
backgroundLocationGranted
activityRecognitionGranted
notificationPermissionState
phonePlacement
screenStateAtStart
weatherNotes? (manual, opcional)
routeType
notes?
```

### 6.2 Location events

Cada punto debe conservar el contrato Raw TrackPoint de F0.5 más:

```text
sessionId
sequenceNumber
requestProfileId
callbackBatchId?
receivedAtElapsedRealtime
currentDetectorState
```

### 6.3 Activity events

```text
timestamp / elapsedRealtime
transitionType
activityType
confidence? (solo cuando la API usada lo ofrezca)
source
```

### 6.4 Detector events

```text
stateFrom
stateTo
reasonCode
signalSnapshot
algorithmVersion
```

### 6.5 System / lifecycle events

Ejemplos:

```text
SCREEN_OFF
SCREEN_ON
APP_FOREGROUND
APP_BACKGROUND
LOCATION_SETTINGS_CHANGED
PERMISSION_CHANGED
GPS_GAP_STARTED
GPS_GAP_ENDED
SERVICE_STARTED
SERVICE_STOPPED
PROCESS_RECREATED
BOOT_ID_CHANGED
```

Los nombres exactos se cerrarán en F0.7/F0.13.

---

## 7. Ground truth y anotaciones

No existe un sensor único que entregue “la verdad” de cuándo comenzó un viaje en motocicleta.

F0.6 utilizará un ground truth práctico y reproducible.

### Inicio real del movimiento

`GT_START` se definirá como el comienzo del primer desplazamiento motorizado sostenido de la sesión, revisado posteriormente en el Raw Track y las anotaciones de prueba.

No se define como:

- momento de encender la moto;
- momento de ponerse el casco;
- primer paso caminando;
- primer punto GPS aislado.

### Fin real del movimiento

`GT_END` será el final del último desplazamiento motorizado sostenido antes de estacionar/finalizar la sesión.

### Etiquetas manuales

Mientras está detenido y es seguro hacerlo, el tester podrá marcar eventos como:

```text
READY_TO_START
ARRIVED
MANUAL_PAUSE_BEGIN
MANUAL_PAUSE_END
KNOWN_TRAFFIC_STOP
KNOWN_TUNNEL
KNOWN_GPS_OBSTRUCTION
```

No se requiere interacción mientras la motocicleta está en movimiento.

---

## 8. Control de variables

Para que dos recorridos sean comparables se intentará mantener constantes:

- mismo teléfono;
- misma versión de Android;
- mismo placement del teléfono;
- misma configuración de batería;
- misma ruta o ruta comparable;
- pantalla apagada durante la mayor parte de la conducción, salvo test específico;
- misma configuración de red cuando sea relevante;
- mismo diagnostic build;
- temperatura del dispositivo razonablemente comparable;
- sin otras apps de navegación/tracking activas cuando se mida batería.

Toda desviación debe anotarse, no ocultarse.

---

## 9. Phone placement

La clasificación de actividad puede verse influida por vibración y forma de transportar el teléfono.

Se definen al menos dos condiciones:

### PLACEMENT-NORMAL

Ubicación habitual real del usuario durante la mayoría de viajes.

Esta condición tiene prioridad para decidir producción.

### PLACEMENT-MOUNTED

Teléfono fijado al manillar/soporte, si ese uso forma parte de la realidad del usuario.

Se usa para comprobar si las vibraciones o ausencia de movimiento corporal cambian Activity Recognition o la calidad GPS.

No se debe optimizar el detector para una posición artificial que el usuario no use normalmente.

---

## 10. Perfiles experimentales de tracking

Los siguientes valores son **perfiles de prueba**, no defaults aprobados de producción.

### Campaña S1 — intervalo

Mantener `minUpdateDistance = 0` y batching desactivado.

| ID | Desired interval | Objetivo |
|---|---:|---|
| S1-A | 1 s | Referencia de alta fidelidad |
| S1-B | 2 s | Perfil balanceado candidato |
| S1-C | 5 s | Control de menor frecuencia |

La API no garantiza exactamente esos intervalos; se medirán los deltas realmente recibidos.

### Campaña S2 — distancia mínima

Usar el mejor intervalo preliminar de S1.

| ID | Min update distance | Objetivo |
|---|---:|---|
| S2-A | 0 m | Referencia |
| S2-B | 3 m | Reducir ruido/callbacks sin perder urbano |
| S2-C | 5 m | Evaluar ahorro y pérdida de detalle |

### Campaña S3 — batching

Usar el perfil ganador provisional de S1/S2.

| ID | Max update delay | Objetivo |
|---|---:|---|
| S3-A | 0 | Inmediatez de referencia |
| S3-B | pequeño, aprox. 2× intervalo | Medir ahorro/latencia |

No se evaluará batching agresivo como default del tracking activo inicial.

### Campaña S4 — Candidate Start

Comparar al menos:

- fresh/current location inicial + burst corto;
- stream high-accuracy inmediato;
- `waitForAccurateLocation` cuando aplique;
- duración máxima de validación.

Los timeouts exactos se elegirán como parámetros experimentales del harness, no como requisitos de producto.

---

## 11. Matriz obligatoria de escenarios

La ejecución completa debe cubrir como mínimo estos grupos.

### A. Inicio

| ID | Escenario |
|---|---|
| FT-START-01 | Caminar hasta la moto, montarse y salir normalmente |
| FT-START-02 | Arrancar y salir lentamente de parqueo |
| FT-START-03 | Moto encendida pero permanecer estacionado varios minutos |
| FT-START-04 | Salida urbana con semáforo casi inmediato |
| FT-START-05 | Salida rápida hacia carretera abierta |
| FT-START-06 | Teléfono con pantalla apagada y app no visible |

### B. Continuidad

| ID | Escenario |
|---|---|
| FT-CONT-01 | Semáforo normal |
| FT-CONT-02 | Varios semáforos consecutivos |
| FT-CONT-03 | Presa stop-and-go |
| FT-CONT-04 | Detención larga en presa |
| FT-CONT-05 | Gasolinera/parada breve sin pausa manual |
| FT-CONT-06 | Rotonda y curvas cerradas |
| FT-CONT-07 | Carretera rápida |
| FT-CONT-08 | Carretera de montaña con curvas |

### C. Pausa manual

| ID | Escenario |
|---|---|
| FT-PAUSE-01 | Pausar al llegar a restaurante y reanudar después |
| FT-PAUSE-02 | Permanecer pausado >30 min |
| FT-PAUSE-03 | Caminar con el teléfono durante la pausa |
| FT-PAUSE-04 | Olvidar reanudar y comenzar a conducir |
| FT-PAUSE-05 | Finalizar Trip estando pausado |

### D. Final

| ID | Escenario |
|---|---|
| FT-END-01 | Llegar, estacionar y permanecer quieto |
| FT-END-02 | Llegar y caminar inmediatamente con el teléfono |
| FT-END-03 | Llegar a casa/parqueo con maniobras lentas |
| FT-END-04 | Parada larga que NO debe ser final |
| FT-END-05 | Final manual seguido de permanecer en el mismo lugar |
| FT-END-06 | Final manual seguido de volver a conducir poco después |

### E. GPS degradado

| ID | Escenario |
|---|---|
| FT-GPS-01 | Parqueo cubierto |
| FT-GPS-02 | Túnel si está disponible de forma natural en una ruta |
| FT-GPS-03 | Zona con edificios/obstrucción |
| FT-GPS-04 | Apagar/inhabilitar Location durante una sesión controlada y restaurarlo |
| FT-GPS-05 | Cambiar temporalmente a approximate location en prueba controlada |

### F. Negativos / falsos positivos

| ID | Escenario |
|---|---|
| FT-NEG-01 | Caminar con el teléfono |
| FT-NEG-02 | Permanecer sentado/quieto con vibraciones ambientales normales |
| FT-NEG-03 | Viaje en automóvil |
| FT-NEG-04 | Viaje en bus/transporte público, si es práctico probarlo |
| FT-NEG-05 | Bicicleta, si es práctico probarla |

### G. Lifecycle / sistema

| ID | Escenario |
|---|---|
| FT-SYS-01 | Pantalla apagada casi todo el Trip |
| FT-SYS-02 | Cambiar entre varias apps durante Trip estando detenido |
| FT-SYS-03 | Cerrar UI/retirarla de recientes sin forzar stop del sistema |
| FT-SYS-04 | Battery Saver activado |
| FT-SYS-05 | Reinicio del proceso inducido en entorno controlado |

Reboot completo durante un Trip se tratará como prueba especializada de F0.10, no requisito para la primera campaña.

---

## 12. Repeticiones

Una observación aislada no debe fijar un threshold.

Baseline de ejecución posterior:

- escenarios críticos de start/stop: al menos 3 repeticiones por configuración relevante;
- comparación de sampling: repetir la misma ruta o una ruta muy similar por perfil;
- batería: varias repeticiones o ventanas suficientemente largas para superar el ruido del medidor;
- falsos positivos: exposición real repetida a caminar/otros vehículos.

Si los resultados muestran alta variabilidad, se aumentan repeticiones antes de decidir.

---

## 13. Ruta de referencia para comparar sampling

Se recomienda una ruta de prueba reproducible que incluya en un solo recorrido:

1. salida lenta;
2. calles urbanas;
3. al menos una rotonda o curva cerrada;
4. varios semáforos;
5. tramo de velocidad media;
6. tramo de carretera más rápida;
7. curvas de montaña o equivalentes si son razonablemente accesibles;
8. parada breve;
9. llegada con maniobra lenta.

La ruta debe priorizar seguridad y repetibilidad, no buscar velocidad máxima.

---

## 14. Referencia de ruta y velocidad

No se asumirá que otro teléfono/app comercial representa ground truth perfecto.

Se podrán usar como **referencias auxiliares**:

- un segundo dispositivo grabando simultáneamente;
- una app GPS madura;
- geometría visible de carretera/mapa;
- odómetro de la motocicleta para distancia gruesa;
- segmentos de distancia conocidos;
- video o notas de la prueba cuando sea seguro y útil.

Las discrepancias deben reportarse como comparación entre fuentes, no como error absoluto si no existe una referencia calibrada.

---

## 15. Métricas de detección

### 15.1 Start latency

```text
startLatency = detectedTripStart - GT_START
```

Se reportará distribución, no solo promedio:

- mediana;
- P90/P95 cuando haya suficientes muestras;
- mínimo/máximo;
- fallos sin detección.

### 15.2 Stop latency

```text
stopLatency = detectedTripEnd - GT_END
```

### 15.3 False Start

Sesión negativa que produce un Trip automático que no corresponde.

### 15.4 False Stop / Trip Split

Un Trip real continuo es finalizado incorrectamente y posteriormente se inicia otro.

### 15.5 Missed Trip

Existe desplazamiento real y el detector nunca alcanza Tracking.

### 15.6 State churn

Número de transiciones innecesarias entre Candidate/Tracking/Stop durante un mismo Trip.

Un detector que “funciona” pero oscila constantemente es señal de hysteresis insuficiente.

---

## 16. Métricas de tracking

### 16.1 Delivered interval

Distribución real de `Δt` entre puntos aceptados/recibidos.

### 16.2 Delivered spacing

Distribución de metros entre puntos consecutivos.

### 16.3 Gap metrics

- cantidad de gaps;
- duración de cada gap;
- distancia aparente a través del gap;
- estado del sistema durante el gap.

### 16.4 Distance comparison

```text
relativeDifference = abs(distanceProfile - distanceReference) / distanceReference
```

Solo se llamará “error” cuando la referencia sea suficientemente confiable.

### 16.5 Track geometry

Comparar visualmente y, cuando el tooling exista, cuantitativamente:

- curvas recortadas;
- esquinas;
- rotondas;
- paralelismo con carretera;
- teleports;
- jitter detenido.

### 16.6 Speed

Medir:

- disponibilidad de provider speed;
- speed accuracy;
- max raw;
- max filtered;
- spikes rechazados;
- diferencias entre provider speed y velocidad derivada.

### 16.7 Altitude

Medir:

- vertical accuracy;
- variación mientras el vehículo está quieto;
- continuidad del perfil;
- diferencia raw vs MSL cuando exista;
- sensibilidad de ascent/descent a smoothing.

---

## 17. Métricas de batería

La medición de batería tiene mucho ruido y debe hacerse en hardware real.

### Métodos preferidos

1. **Android Studio Power Profiler / system tracing** cuando el dispositivo soporte métricas útiles.
2. **Macrobenchmark PowerMetric** para pruebas controladas en hardware compatible; las métricas de power/energy de alta precisión están limitadas principalmente a Pixel 6 o posteriores.
3. **Batterystats / `dumpsys batterystats`** como diagnóstico y comparación adicional.
4. Charge/battery delta durante ventanas largas cuando no exista hardware de medición más preciso.

Battery Historian puede usarse como apoyo legado, pero Android indica que ya no está activamente mantenido y recomienda Power Profiler, system tracing o PowerMetric cuando sea posible.

### Condiciones

Al comparar perfiles:

- brillo fijo o pantalla apagada de forma consistente;
- temperatura estable;
- sin carga USB durante el recorrido medido;
- minimizar procesos/apps competidoras;
- misma conectividad cuando sea posible;
- registrar batería inicial/final;
- pruebas suficientemente largas para superar resolución del medidor.

### Métricas

Según hardware disponible:

```text
batteryDeltaPercent/hour
batteryDeltaMah/hour
energyTotal
energyGps
callbackCount/hour
rawPoints/hour
```

El objetivo inicial será comparar perfiles **relativamente**, no prometer una cifra universal de consumo aplicable a todos los teléfonos.

---

## 18. Selección del perfil de producción

No se elegirá automáticamente el perfil con menor batería ni el de mayor densidad de puntos.

La selección seguirá un criterio Pareto:

> Elegir el perfil menos costoso que no produzca una pérdida material de calidad o confiabilidad respecto al perfil de referencia.

Orden de prioridad:

1. No perder Trips reales.
2. No dividir Trips por tráfico normal.
3. Preservar geometría útil del mapa.
4. Mantener distancia/velocidad razonables.
5. Reducir consumo y almacenamiento.

Si el perfil de menor consumo viola 1–3, se descarta aunque ahorre batería.

---

## 19. Gates funcionales cualitativos

Antes de fijar thresholds numéricos, la ejecución debe demostrar al menos:

- semáforos normales no finalizan Trips;
- stop-and-go no genera múltiples Trips;
- Manual Pause nunca se reanuda silenciosamente;
- conducir mientras continúa una Manual Pause produce diagnóstico/recordatorio según diseño;
- caminar antes/después no debe generar segmentos motorizados largos falsos;
- el Raw Track sobrevive a pantalla apagada/cambio de UI bajo condiciones soportadas;
- pérdida temporal de GPS produce gap explícito, no teleport silencioso;
- un outlier no debe convertirse en velocidad máxima válida;
- todos los perfiles pueden identificarse y reproducirse por versión.

Los objetivos cuantitativos de start/stop latency, batería y precisión se fijarán después del primer pilot dataset.

---

## 20. Formato del dataset

Estructura propuesta:

```text
field-tests/
├── manifest.json
├── profiles/
│   └── profiles-v1.json
├── sessions/
│   ├── FT-2026-001/
│   │   ├── session.json
│   │   ├── raw-track.csv
│   │   ├── activity-events.jsonl
│   │   ├── detector-events.jsonl
│   │   ├── system-events.jsonl
│   │   ├── annotations.json
│   │   ├── processed-track.geojson
│   │   └── battery/
│   └── ...
└── analysis/
    ├── sessions-summary.csv
    ├── detector-summary.csv
    ├── location-profile-summary.csv
    └── decisions.md
```

La representación final puede cambiar en F0.7, pero los conceptos no deben perderse.

---

## 21. Session result template

Cada sesión debe terminar con un registro resumido:

```text
Session ID:
Scenario ID:
Profile ID:
Pass/Fail/Needs review:
Ground-truth start:
Detected start:
Start latency:
Ground-truth end:
Detected end:
Stop latency:
False start?:
False stop?:
Raw points:
Accepted points:
Rejected points:
Longest GPS gap:
Distance raw:
Distance processed:
Reference distance (if available):
Max raw speed:
Max filtered speed:
Battery start/end:
Phone placement:
Screen behavior:
Unexpected events:
Notes:
```

---

## 22. Pilot vs validation campaign

### Pilot

Primera ejecución con pocos recorridos.

Objetivo:

- verificar que el harness guarda todos los campos;
- detectar errores de logging;
- observar rangos reales;
- ajustar profiles experimentales;
- proponer thresholds iniciales.

El pilot **no congela producción**.

### Validation

Después de ajustar algoritmo/thresholds:

- repetir escenarios críticos;
- utilizar rutas no usadas para “afinar” el detector;
- confirmar que la mejora no está sobreajustada al pilot.

Esto evita construir un detector que solo funciona en la ruta con la que fue calibrado.

---

## 23. Relación con Fase 1

Como Fase 0 es documental, F0.6 se considera cerrado al definir el protocolo.

Sin embargo, Fase 1 no debe avanzar directamente a un detector de producción.

El roadmap deberá incluir al inicio algo equivalente a:

```text
W0 / EXPERIMENTATION

EXP-001 Diagnostic Harness
EXP-002 Raw Location Logger
EXP-003 Activity Recognition Logger
EXP-004 Detector Event Logger
EXP-005 Export Diagnostic Dataset
EXP-006 Pilot Field Runs
EXP-007 Analyze Pilot
EXP-008 Freeze Detector/Location Profile v1
```

Solo después de `EXP-008` pueden convertirse los parámetros experimentales en defaults de producción.

---

## 24. Riesgos

| Riesgo | Consecuencia | Mitigación |
|---|---|---|
| Probar una sola ruta | Sobreajuste | Rutas y contextos distintos |
| Probar una sola vez | Decisiones por azar | Repeticiones |
| Cambiar varias variables | No se conoce la causa | Campañas por factor |
| Usar otro GPS como verdad absoluta | Conclusiones falsas | Tratarlo como referencia auxiliar |
| Medir batería en pruebas cortas | Ruido domina | Ventanas largas/repeticiones |
| Usar pantalla encendida en un perfil y apagada en otro | Comparación inválida | Control de variables |
| Optimizar solo para teléfono montado | Fallos en uso real | PLACEMENT-NORMAL prioritario |
| Optimizar solo para una moto/ruta | Generalización pobre | Escenarios variados |
| Ajustar al pilot y no revalidar | Overfitting | Campaña Validation separada |

---

## 25. Criterio de cierre F0.6

F0.6 queda cerrado como baseline de diseño cuando:

- [x] se define el Diagnostic Harness requerido;
- [x] existe contrato mínimo de logging;
- [x] se define ground truth operacional;
- [x] existen perfiles experimentales versionados;
- [x] existe matriz obligatoria de escenarios;
- [x] existen métricas de detector/location/batería;
- [x] existe estrategia Pilot → Validation;
- [x] se define formato de dataset y plantilla de resultados;
- [x] se separa claramente diseño documental de ejecución física;
- [x] se documenta que los thresholds se congelan después de ejecutar W0/EXP.

**Decisión:** F0.6 CERRADO baseline de diseño v0.1. Ejecución diferida al workstream experimental inicial de Fase 1.

---

## 26. Fuentes oficiales utilizadas

Fecha de consulta: 2026-09-15.

1. Android Developers — Request location updates  
   https://developer.android.com/develop/sensors-and-location/location/request-updates

2. Android Developers — Change location settings  
   https://developer.android.com/develop/sensors-and-location/location/change-location-settings

3. Android Developers — Power Profiler  
   https://developer.android.com/studio/profile/power-profiler

4. Android Developers — Macrobenchmark metrics / PowerMetric  
   https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics

5. Android Developers — PowerMetric API reference  
   https://developer.android.com/reference/androidx/benchmark/macro/PowerMetric

6. Android Developers — dumpsys / batterystats  
   https://developer.android.com/tools/dumpsys

7. Android Developers — Batterystats and Battery Historian  
   https://developer.android.com/topic/performance/power/setup-battery-historian

8. Android Developers — Identify and optimize wake lock use cases  
   https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/identify-wls

---

## 27. Próximo workstream

**F0.7 — Domain & Data Model**

F0.7 convertirá los conceptos ya definidos en entidades, relaciones, invariantes y contratos de datos estables, incluyendo cómo preservar Raw Track, Processed Track, TripEvent, Stop, Pause, Marker, Route, Motorcycle y metadatos de experimentación.
