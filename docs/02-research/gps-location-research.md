# F0.5 — GPS & Location Research
## Moto Trip Tracker V2

**Estado:** CERRADO baseline  
**Versión:** 0.1  
**Fecha de corte:** 2026-09-15  
**Proyecto:** Moto Trip Tracker V2  
**Depende de:** F0.1–F0.4

---

## 1. Objetivo

Definir una política técnica defendible para adquirir, conservar y procesar ubicación durante un Trip de motocicleta sin fijar prematuramente thresholds que todavía necesitan pruebas de campo.

F0.5 cubre:

- Fused Location Provider (FLP);
- precisión horizontal y ubicación precisa/aproximada;
- frecuencia y distancia mínima de actualizaciones;
- timestamps y orden de puntos;
- velocidad;
- distancia;
- bearing;
- altitud/elevación;
- filtrado de puntos anómalos;
- gaps de GPS;
- raw track vs processed track;
- impacto en batería;
- perfiles de ubicación por estado del detector;
- variables que deberán medirse en F0.6.

F0.5 **no** cierra todavía valores definitivos de intervalos, accuracy cutoffs, filtros de velocidad o smoothing de elevación. Esos valores deben validarse con datos reales de motocicleta en F0.6.

---

## 2. Conclusión ejecutiva

La política base aceptada es:

```text
IDLE
Activity Recognition / señales pasivas
sin GPS high-accuracy continuo
        ↓
CANDIDATE_START
ráfaga breve de ubicación precisa
        ↓
TRACKING
stream FLP de alta precisión
        ↓
RAW TRACK persistido
        ↓
QUALITY / FILTER PIPELINE
        ↓
PROCESSED TRACK
        ↓
mapa + distancia + velocidad + elevación + stops
```

Decisiones principales:

1. **FusedLocationProviderClient** será la fuente primaria de ubicación para V2 en dispositivos con Google Play services.
2. La función principal de tracking necesita **ubicación precisa**. La ubicación aproximada de Android es demasiado gruesa para reconstruir fielmente una ruta de motocicleta o calcular métricas útiles.
3. La app conservará **Raw Track** y **Processed Track** como conceptos separados.
4. Cada punto debe conservar metadata suficiente para evaluar su calidad posteriormente; no se guardará solo `lat/lon`.
5. Para ordenar y calcular deltas entre puntos se preferirá el reloj monotónico `elapsedRealtime`, no el reloj civil del dispositivo.
6. Para velocidad instantánea se preferirá `Location.getSpeed()` cuando esté disponible y tenga calidad aceptable; Android documenta que puede ser más precisa que `distance/time` porque puede incorporar Doppler GNSS.
7. La velocidad derivada entre puntos se mantendrá como fallback y señal de diagnóstico.
8. La distancia se calculará desde el **Processed Track**, no desde una polilínea simplificada para dibujo.
9. Un salto GPS no debe aumentar silenciosamente distancia o velocidad máxima.
10. La altitud de `Location.getAltitude()` es sobre el elipsoide WGS84, no nivel medio del mar. En API 34+ se preferirá MSL altitude cuando esté disponible.
11. Ascenso/descenso acumulado no se obtendrá sumando todos los cambios crudos de altitud; necesita filtrado/smoothing y validación de campo.
12. No se adquirirá un wake lock manual continuo para location; FLP/LocationManager ya gestionan los wakeups asociados a sus callbacks.
13. El tracking activo usará una sola fuente de location compartida por recorder, detector y UI; no múltiples streams de alta precisión competidores.
14. Intervalos, `minUpdateDistance`, batching y thresholds de calidad se cerrarán después de pruebas A/B de campo.

---

## 3. Proveedor de ubicación

### 3.1 Baseline: Fused Location Provider

Google describe el fused location provider como una API que administra las tecnologías de ubicación subyacentes y permite pedir requisitos de alto nivel de precisión/potencia.

Para un Trip activo, el baseline es:

- `FusedLocationProviderClient.requestLocationUpdates()`;
- `LocationRequest` de `PRIORITY_HIGH_ACCURACY`;
- lifecycle controlado por el foreground service de Trip definido en F0.4.

`getLastLocation()` puede devolver información vieja. `getCurrentLocation()` es preferible para obtener un fix fresco puntual, pero un Trip necesita actualizaciones continuas, por lo que el recorder usará `requestLocationUpdates()` durante el estado activo.

### 3.2 Google Play services como dependencia

V2 baseline acepta Google Play services para:

- Activity Recognition;
- Fused Location Provider.

Un backend alternativo basado únicamente en framework `LocationManager` queda como posible compatibilidad futura, no como requisito inicial.

---

## 4. Precisa vs aproximada

Android documenta dos niveles de permiso:

- **Approximate:** una estimación que puede cubrir aproximadamente varios kilómetros cuadrados.
- **Precise:** usualmente dentro de decenas de metros y en buenas condiciones puede llegar a pocos metros.

### Decisión F0.5

Para la experiencia central de Moto Trip Tracker:

> **Precise location es funcionalmente necesaria para tracking de ruta y métricas.**

Approximate location puede permitir una experiencia degradada o ciertos triggers contextuales, pero **no se considerará suficiente** para:

- dibujar la ruta real;
- distinguir calles cercanas;
- medir curvas y trayectos urbanos;
- calcular distancia con precisión razonable;
- detectar velocidad útil;
- identificar paradas o límites del Trip con granularidad suficiente.

La app debe explicar esta relación al solicitar FINE location. Si el usuario concede solo approximate, el estado de capacidad debe ser visible y el tracking detallado no debe fingir precisión inexistente.

---

## 5. Contrato conceptual de TrackPoint raw

El Raw Track debe conservar suficientes datos para que un algoritmo futuro pueda reprocesar el viaje.

Campos candidatos:

| Campo | Motivo |
|---|---|
| `latitude` / `longitude` | Posición fuente |
| `accuracyM` | Radio estimado de precisión horizontal |
| `wallTimeEpochMs` | Fecha/hora histórica y exportación |
| `elapsedRealtimeNanos` | Orden y delta temporal monotónico |
| `speedMps?` | Velocidad reportada por Location |
| `speedAccuracyMps?` | Incertidumbre de velocidad |
| `bearingDeg?` | Dirección horizontal de movimiento |
| `bearingAccuracyDeg?` | Calidad del bearing |
| `altitudeWgs84M?` | Altitud elipsoidal raw |
| `verticalAccuracyM?` | Calidad de altitud raw |
| `mslAltitudeM?` | Altitud sobre nivel medio del mar, si API/dispositivo la provee |
| `mslAltitudeAccuracyM?` | Calidad MSL si disponible |
| `provider?` | Diagnóstico |
| `isMock` | Diagnóstico/test |
| `receivedAtElapsed?` | Medir latencia de entrega si se necesita |
| `tripStateAtCapture` | Contexto para depuración |
| `requestProfileId/version` | Reproducibilidad del experimento |

No todos los campos estarán siempre disponibles. **Ausencia no equivale a cero.**

La representación física se definirá en F0.7/F0.8.

---

## 6. Tiempo y orden de puntos

### 6.1 No usar solo `Location.getTime()` para deltas

Android advierte que el tiempo Unix del dispositivo no es monotónico y puede saltar hacia delante o atrás.

`getElapsedRealtimeNanos()` sí es monotónico dentro del mismo boot.

### Decisión

- `wallTimeEpochMs` se conserva para fecha/hora humana.
- `elapsedRealtimeNanos` será la referencia principal para ordenar puntos y calcular `Δt` durante un mismo boot.
- Un reboot durante Trip crea una discontinuidad explícita; F0.10 definirá cómo reconciliarla.
- Puntos out-of-order o con tiempo repetido deben marcarse y no usarse ciegamente en cálculos derivados.

---

## 7. Location accuracy y calidad del punto

`Location.getAccuracy()` representa un radio horizontal de confianza aproximada al 68 %.

Esto significa que `accuracy=20 m` **no** quiere decir “error exacto de 20 m”; es una estimación probabilística de incertidumbre horizontal.

### 7.1 Política aceptada

No existirá inicialmente una regla global como:

```text
si accuracy > X → borrar punto
```

porque un punto mediocre puede ser útil durante un túnel, zona montañosa o cañón urbano, mientras que una secuencia de puntos aparentemente precisos puede seguir generando jitter.

El pipeline evaluará múltiples señales:

- horizontal accuracy;
- edad/frescura del fix;
- orden temporal;
- distancia al punto anterior;
- velocidad reportada;
- speed accuracy;
- velocidad implícita;
- aceleración/cambio imposible;
- bearing y su continuidad;
- contexto de puntos vecinos;
- gap temporal;
- estado del Trip.

### 7.2 Quality flags

Cada Raw TrackPoint debe poder recibir después un resultado como:

```text
ACCEPTED
ACCEPTED_LOW_CONFIDENCE
REJECTED_OUT_OF_ORDER
REJECTED_STALE
REJECTED_ACCURACY
REJECTED_SPEED_SPIKE
REJECTED_TELEPORT
REJECTED_DUPLICATE
GAP_BOUNDARY
```

Los nombres exactos se cierran en F0.7.

---

## 8. Sampling y LocationRequest

Google Play services permite configurar, entre otras variables:

- desired interval;
- minimum update interval;
- minimum update distance;
- maximum update delay/batching;
- priority;
- wait for accurate location.

Los intervalos son **preferencias/restricciones de request**, no garantía de que Android entregará exactamente un punto cada N segundos.

### 8.1 Principio de diseño

Un solo perfil no es adecuado para todos los estados.

Se definen perfiles conceptuales:

| Estado | Política conceptual |
|---|---|
| IDLE | No high-accuracy continuous GPS |
| CANDIDATE_START | High-accuracy burst limitado |
| TRACKING | High-accuracy continuo con frecuencia suficiente para curvas/velocidad |
| CANDIDATE_STOP | Mantener evidencia suficiente para no cortar en presa/semáforo |
| MANUAL_PAUSED | Detener recording detallado; opcional señal de bajo consumo para recordar Resume |

### 8.2 Variables que F0.6 debe comparar

No se fijan todavía como producción, pero el experimento debe comparar al menos:

- intervalos de tracking rápidos vs moderados;
- `minUpdateDistance=0` vs una distancia pequeña;
- batching desactivado vs delay limitado;
- `waitForAccurateLocation` en el arranque;
- duración/coste de la ráfaga Candidate Start;
- efecto de cambiar perfil cuando la moto está detenida.

### 8.3 Hipótesis de ingeniería

A velocidades de motocicleta, intervalos muy largos pueden recortar curvas y degradar distancia. Por ejemplo, a 50 km/h el vehículo recorre ~13.9 m por segundo; a 100 km/h, ~27.8 m por segundo. Un intervalo nominal de 5 s puede separar muestras por decenas o más de cien metros.

Por ello, F0.6 debe evaluar como candidatos de alta fidelidad **1–2 s** durante TRACKING, pero esos valores son una hipótesis de prueba, no un contrato final.

---

## 9. Minimum update distance

`LocationRequest.Builder.setMinUpdateDistanceMeters()` evita entregar un nuevo fix si el desplazamiento derivado no alcanza la distancia configurada y puede ofrecer ahorro de energía en algunos casos.

### Ventaja potencial

- menos callbacks/persistencia cuando el vehículo está prácticamente quieto;
- posible ahorro de batería.

### Riesgo para este producto

Un `minUpdateDistance` demasiado alto puede:

- perder curvas cortas;
- degradar rotondas;
- perder movimiento lento en presa;
- retrasar confirmaciones de inicio/final;
- reducir resolución urbana.

### Decisión

No fijar valor global todavía. F0.6 comparará `0 m` contra valores pequeños y medirá fidelidad/batería.

---

## 10. Batching

`setMaxUpdateDelayMillis()` permite que el dispositivo entregue ubicaciones en lotes y potencialmente ahorre energía cuando el cliente no necesita datos inmediatamente.

Moto Trip Tracker sí necesita cierta inmediatez durante:

- Candidate Start;
- cálculo visible del Trip;
- Candidate Stop;
- Pause/Finish controls.

### Baseline

- **No usar batching agresivo** durante un Trip activo en la primera implementación.
- F0.6 puede evaluar batching pequeño si conserva orden y latencia aceptable.
- La persistencia debe procesar todos los puntos de un batch usando sus timestamps, no el instante de callback como si todos hubieran ocurrido simultáneamente.

---

## 11. Distancia

Android ofrece `Location.distanceTo()`/`distanceBetween()` con distancia aproximada sobre el elipsoide WGS84.

### 11.1 Distancia fuente vs derivada

V2 distinguirá conceptualmente:

- `rawDistanceDiagnostic`: suma/diagnóstico sobre puntos raw cuando sea útil;
- `processedDistance`: distancia oficial mostrada al usuario;
- `displayPolylineLength`: nunca será fuente de verdad si la polilínea fue simplificada visualmente.

### 11.2 Pipeline base

```text
Raw TrackPoints
    ↓
orden temporal
    ↓
quality validation
    ↓
segment/gap detection
    ↓
Processed TrackPoints
    ↓
suma WGS84 de edges válidos
    ↓
Trip distance
```

### 11.3 Gaps

Si existen varios minutos sin GPS, unir dos fixes distantes con una línea recta y contarla como distancia exacta introduce falsa confianza.

Por tanto, un gap deberá quedar explícito.

F0.6/F0.7 decidirán si un gap produce:

- distancia excluida;
- distancia aproximada separada;
- segmento de baja confianza;
- reconstrucción posterior opcional mediante map matching.

**Map matching no forma parte del Core baseline.**

---

## 12. Velocidad

### 12.1 Fuente preferida

Android indica que `Location.getSpeed()` puede ser más precisa que calcular `distance/time` entre posiciones consecutivas, por ejemplo si el proveedor incorpora mediciones Doppler GNSS.

### Decisión

Cuando estén disponibles y tengan calidad suficiente:

```text
speedMps + speedAccuracyMps
```

son la fuente preferida para velocidad instantánea.

### 12.2 Velocidad derivada

También se calculará una señal secundaria:

```text
segmentSpeed = distance(pointA, pointB) / elapsedTime
```

Usos:

- fallback cuando `hasSpeed=false`;
- detectar inconsistencias;
- tests/replay;
- procesamiento histórico.

### 12.3 Velocidad máxima

La máxima del Trip **no** será simplemente `max(speedMps)`.

Debe superar validaciones de:

- speed accuracy;
- accuracy horizontal;
- continuidad con puntos vecinos;
- aceleración/deceleración plausible;
- ausencia de teleport/gap;
- persistencia mínima o confirmación temporal.

El algoritmo y thresholds se fijan tras F0.6.

### 12.4 Promedios

No se promediarán muestras instantáneas sin ponderación.

Métricas candidatas:

- average trip speed = distancia / tiempo lógico del Trip según definición de producto;
- moving average speed = distancia en movimiento / moving time.

La semántica exacta de manual pause y stopped time se cierra en F0.7/F0.9.

---

## 13. Bearing

`Location.getBearing()` representa dirección horizontal de viaje, no orientación física del teléfono.

Se conservará cuando exista, junto con `bearingAccuracy`.

Usos potenciales:

- validar continuidad de movimiento;
- detectar cambios bruscos sospechosos;
- mejorar visualización/diagnóstico;
- auxiliar a filtros.

No será condición obligatoria para aceptar un punto porque puede faltar o ser impreciso a baja velocidad.

---

## 14. Altitud y elevación

### 14.1 WGS84 vs nivel medio del mar

`Location.getAltitude()` devuelve metros sobre el **elipsoide WGS84**.

No equivale necesariamente a la “altura sobre nivel del mar” que un usuario espera ver.

Android API 34+ añade:

- `getMslAltitudeMeters()`;
- `getMslAltitudeAccuracyMeters()`.

### Decisión

Raw Track conserva cuando existan:

- WGS84 altitude;
- vertical accuracy;
- MSL altitude;
- MSL accuracy.

Para presentación al usuario:

1. preferir MSL altitude cuando esté disponible y tenga metadata de calidad;
2. mantener WGS84 como fuente raw/diagnóstico;
3. no depender de una API de red para poder cerrar un Trip.

### 14.2 Dispositivos/Android anteriores

Si MSL no está disponible:

- conservar raw altitude WGS84;
- no etiquetarla incorrectamente como elevación MSL exacta;
- permitir procesamiento/enriquecimiento posterior si se aprueba.

Google Elevation API podría enriquecer perfiles posteriormente, pero requiere Internet, API key/billing y no puede ser requisito del Core offline-first.

### 14.3 Ascenso y descenso acumulados

Sumar cada pequeño `Δaltitude` genera sobreconteo por ruido vertical.

Por tanto:

> cumulative ascent/descent es una **métrica procesada**, no una suma raw.

F0.6 debe comparar métodos como:

- smoothing temporal;
- smoothing por distancia;
- resampling;
- descarte por vertical accuracy;
- hysteresis/minimum elevation change;
- MSL vs WGS84 disponible.

Hasta validar el método, FR-MET-010 continúa como research-gated.

---

## 15. Raw Track y Processed Track

### Raw Track

Principios:

- append-only durante recording normal;
- conservar puntos aunque luego resulten sospechosos, salvo razones técnicas extraordinarias;
- no “corregir” coordenadas originales in-place;
- suficiente metadata para reprocessing.

### Processed Track

Puede:

- ignorar duplicados;
- excluir puntos stale/out-of-order;
- excluir teleports;
- marcar gaps;
- suavizar datos para métricas específicas;
- generar geometría destinada al mapa.

### Regla crítica

> Mejorar el algoritmo en V2.1 debe permitir recalcular viajes antiguos sin haber perdido la evidencia original.

---

## 16. Pipeline conceptual de procesamiento

```text
RAW LOCATION
    ↓
VALIDATE COMPLETENESS / TIME
    ↓
ORDER / DEDUPE
    ↓
HORIZONTAL QUALITY CHECK
    ↓
GAP DETECTION
    ↓
KINEMATIC CONSISTENCY
(speed / implied speed / acceleration / bearing)
    ↓
QUALITY FLAGS
    ↓
PROCESSED TRACK
    ├── distance
    ├── moving/stopped timeline
    ├── speed metrics
    ├── elevation profile
    └── map geometry
```

Cada filtro deberá ser versionable. Un Trip debería poder registrar qué versión de processing generó sus métricas.

---

## 17. GPS loss y señal degradada

La pérdida temporal de ubicación es esperable en:

- túneles;
- parqueos techados;
- edificios;
- montaña;
- cañones urbanos;
- ahorro de batería/OEM;
- permisos/settings modificados.

### Regla

`GPS_LOST` no finaliza el Trip por sí solo.

La app debe distinguir:

```text
NO FIX
LOW QUALITY FIX
STALE FIX
VALID FIX
```

Cuando vuelve una ubicación válida:

- se crea un evento `GPS_RECOVERED`;
- el gap queda marcado;
- no se inventan puntos intermedios como si hubieran sido observados.

---

## 18. Manual Pause

Durante `MANUAL_PAUSED`:

- se detiene el stream high-accuracy destinado a dibujar el recorrido, salvo necesidad técnica específica;
- se conserva el tiempo de pausa;
- puede permanecer una señal pasiva/low-power para detectar que el usuario volvió a moverse y recordar Resume;
- ningún movimiento durante Manual Pause se añade silenciosamente a la ruta detallada.

Esto reduce consumo durante restaurantes o descansos largos.

---

## 19. Battery strategy

### 19.1 Principios

- GPS high-accuracy continuo solo durante validación corta o Trip activo.
- Activity Recognition es el trigger idle principal.
- No crear un wake lock manual continuo para location.
- Usar un solo stream de ubicación para todas las capas del Trip.
- Evitar procesamiento pesado por cada fix si puede hacerse incremental o post-Trip.
- No hacer requests de red por cada punto.
- Permitir batching únicamente si no perjudica la lógica temporal.
- Detener location updates al pasar a Manual Pause/Completed.

Android indica que FLP/LocationManager ya usan wake locks para adquirir/entregar ubicación, por lo que añadir otro wake lock continuo sería redundante.

### 19.2 Qué se medirá

F0.6 deberá medir por perfil:

- % batería/hora;
- número de fixes/hora;
- porcentaje de puntos accepted/rejected;
- CPU/process wakeups si las herramientas permiten medirlo;
- temperatura/dispositivo cuando sea útil;
- fidelidad de ruta frente a perfiles de mayor frecuencia.

---

## 20. Perfiles candidatos para F0.6

Estos perfiles son **experimentos**, no configuración final.

### Profile A — High Fidelity

- high accuracy;
- desired interval rápido;
- min distance 0;
- batching mínimo/desactivado.

Objetivo: obtener baseline de máxima fidelidad razonable.

### Profile B — Balanced

- high accuracy;
- intervalo moderado;
- min distance pequeño;
- batching limitado.

Objetivo: medir cuánto ahorro se logra sin degradación visible.

### Profile C — Adaptive candidate

- high fidelity durante movimiento;
- perfil relajado durante detención sostenida;
- retorno inmediato al perfil de movimiento.

Objetivo: evaluar si la complejidad adaptativa aporta ahorro real sin falsos stops/gaps.

F0.6 definirá valores concretos y condiciones de comparación.

---

## 21. Métricas de calidad de recording

Por Trip de prueba se deberán poder calcular:

- raw point count;
- accepted point count;
- rejected point count por razón;
- median/p95 horizontal accuracy;
- median/p95 speed accuracy cuando exista;
- gap count;
- max gap duration;
- distance raw vs processed;
- route deviation visual;
- max speed raw vs filtered;
- average speed;
- altitude availability rate;
- vertical accuracy distribution;
- MSL availability rate;
- ascent/descent por algoritmo candidato;
- battery use/hour;
- start latency;
- stop latency.

---

## 22. Casos que F0.6 debe incluir obligatoriamente

Además de SCN-001..028 de F0.3:

1. carretera abierta con buena señal;
2. ciudad con edificios y múltiples giros;
3. presa prolongada;
4. salida lenta de parqueo;
5. rotondas/curvas cerradas;
6. túnel o zona de pérdida temporal de GPS;
7. montaña con cambios importantes de elevación;
8. teléfono en bolsillo/chaqueta/baúl si son escenarios reales;
9. pantalla apagada durante la mayor parte del Trip;
10. Battery Saver;
11. approximate-only permission como prueba de degradación;
12. dos perfiles de sampling en la misma ruta o rutas comparables;
13. viaje corto;
14. viaje largo;
15. Manual Pause de larga duración;
16. reinicio del stream después de perder precisión.

---

## 23. Decisiones aceptadas F0.5

| ID | Decisión | Estado |
|---|---|---|
| GPS-001 | FusedLocationProviderClient es fuente primaria | ACEPTADO |
| GPS-002 | Precise location es necesaria para tracking Core | ACEPTADO |
| GPS-003 | Raw y Processed Track permanecen separados | ACEPTADO |
| GPS-004 | elapsedRealtime es reloj principal para deltas | ACEPTADO |
| GPS-005 | `Location.getSpeed()` es velocidad instantánea preferida cuando tiene calidad | ACEPTADO |
| GPS-006 | Velocidad derivada se conserva como fallback/diagnóstico | ACEPTADO |
| GPS-007 | Max speed requiere filtrado, no `max(raw)` | ACEPTADO |
| GPS-008 | Distancia oficial deriva del Processed Track | ACEPTADO |
| GPS-009 | Gaps se marcan explícitamente; no inventar fixes | ACEPTADO |
| GPS-010 | MSL altitude se prefiere para UI cuando esté disponible | ACEPTADO |
| GPS-011 | Ascent/descent requiere smoothing validado | ACEPTADO research-gated |
| GPS-012 | No aggressive batching como baseline activo | ACEPTADO |
| GPS-013 | No wake lock manual continuo para location | ACEPTADO |
| GPS-014 | Sampling final se decide con field tests | ACEPTADO |
| GPS-015 | Display polyline simplificada no es fuente de métricas | ACEPTADO |

---

## 24. Preguntas transferidas a F0.6

F0.6 debe responder con evidencia de campo:

- ¿1 s, 2 s u otro intervalo ofrece la mejor relación fidelidad/batería?
- ¿Un `minUpdateDistance` pequeño ayuda sin cortar curvas o movimiento lento?
- ¿Cuánto mejora realmente `waitForAccurateLocation` en el inicio?
- ¿Qué horizontal accuracy es razonable aceptar en carretera/ciudad/montaña?
- ¿Qué combinación detecta teleports sin eliminar fixes útiles?
- ¿Qué filtro evita falsos top speed sin eliminar aceleraciones reales?
- ¿Cómo se comporta `Location.getSpeed()` en la moto y a baja velocidad?
- ¿Qué tan disponible es `speedAccuracy` en el dispositivo objetivo?
- ¿Qué tan estable es altitude/MSL altitude?
- ¿Qué smoothing produce ascenso/descenso creíble?
- ¿Cuánto consume High Fidelity vs Balanced?
- ¿Conviene un perfil adaptativo o añade complejidad sin ahorro significativo?
- ¿Cómo se degradan los datos con approximate location?
- ¿Qué ocurre cuando el teléfono está en bolsillo, soporte o baúl?

---

## 25. Impacto sobre F0.2 / F0.3 / F0.4

### F0.2

Se confirma técnicamente:

- FR-REC-003 metadata de calidad;
- FR-REC-005 Raw Track;
- FR-REC-006 Processed Track;
- FR-REC-007 invalid point handling;
- FR-MET-006 filtered maximum speed;
- FR-MET-010 elevation gain/loss continúa research-gated.

### F0.3

No requiere cambiar la máquina de estados.

Se refuerza que:

- GPS es evidencia, no detector único;
- GPS loss no significa Trip end;
- Manual Pause debe poder apagar recording detallado;
- Candidate Start usa un burst de validación, no GPS permanente.

### F0.4

Se cierra la pregunta principal de FINE location:

> Precise location es necesaria para la funcionalidad central de tracking detallado y métricas de Moto Trip Tracker V2.

---

## 26. Criterio de cierre F0.5

☑ Fuente primaria de location definida.  
☑ Justificación de precise location documentada.  
☑ Contrato conceptual de Raw TrackPoint definido.  
☑ Tiempo monotónico definido para cálculos.  
☑ Política de quality/processing definida sin thresholds inventados.  
☑ Estrategia de distancia definida.  
☑ Estrategia de velocidad definida.  
☑ Estrategia de altitud/elevación definida.  
☑ Gaps y GPS loss definidos.  
☑ Battery principles definidos.  
☑ Sampling/batching/min-distance transferidos a field tests.  
☑ Métricas de experimento definidas.  
☑ Fuentes oficiales registradas.

**Decisión v0.5:** F0.5 queda CERRADO como baseline técnico. Los thresholds y parámetros numéricos finales se bloquean hasta completar F0.6 con recorridos reales.

---

## 27. Fuentes oficiales principales

1. Android Developers — Request location updates  
   https://developer.android.com/develop/sensors-and-location/location/request-updates

2. Android Developers — Change location settings / LocationRequest priorities  
   https://developer.android.com/develop/sensors-and-location/location/change-location-settings

3. Android Developers — Request location permissions  
   https://developer.android.com/develop/sensors-and-location/location/permissions

4. Android Developers — Retrieve current / last known location  
   https://developer.android.com/develop/sensors-and-location/location/retrieve-current

5. Android API Reference — `android.location.Location`  
   https://developer.android.com/reference/android/location/Location

6. Google Play services API Reference — `LocationRequest.Builder`  
   https://developers.google.com/android/reference/com/google/android/gms/location/LocationRequest.Builder

7. Android Developers — Identify and optimize wake lock use cases  
   https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/identify-wls

8. Google Maps Platform — Elevation API (future optional enrichment only)  
   https://developers.google.com/maps/documentation/elevation/overview

---

## 28. Próximo workstream

**F0.6 — Field Experiment Design**

Debe convertir las preguntas anteriores en un protocolo ejecutable: perfiles A/B, rutas y escenarios, esquema de logging, ground truth disponible, criterios de comparación, plantilla de resultados y gate para cerrar parámetros de producción.
