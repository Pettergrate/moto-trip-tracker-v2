# F0.4 — Android Platform Research
## Moto Trip Tracker V2

**Estado:** CERRADO baseline  
**Versión:** 0.1  
**Fecha de corte:** 2026-09-15  
**Proyecto:** Moto Trip Tracker V2  
**Depende de:** F0.1, F0.2, F0.3

---

## 1. Objetivo

Confirmar qué permite Android moderno para una aplicación que debe detectar automáticamente un posible viaje, comenzar un registro de ubicación aunque la interfaz no esté abierta, mantener un viaje activo durante períodos prolongados y recuperar su capacidad de detección después de reinicios o actualizaciones.

F0.4 no decide todavía la frecuencia GPS, filtros de posición, cálculo de distancia, velocidad o altitud. Esos temas pertenecen a F0.5.

---

## 2. Conclusión ejecutiva

El concepto de Moto Trip Tracker V2 es técnicamente viable en Android, pero el modo **100 % automático** tiene requisitos importantes de permisos y ejecución en segundo plano.

La estrategia base aceptada es:

```text
IDLE / PASSIVE
    ↓
Activity Recognition Transition API
    ↓
IN_VEHICLE / transición relevante
    ↓
CANDIDATE_START
    ↓
validación breve con ubicación
    ↓
LOCATION FOREGROUND SERVICE
    ↓
TRACKING ACTIVO
```

Conclusiones principales:

1. **Activity Recognition Transition API** es el candidato principal para la detección pasiva de bajo consumo.
2. La API reconoce `IN_VEHICLE`, pero **no existe una clase MOTORCYCLE**. Por tanto, nunca se usará como prueba de que el usuario va específicamente en moto.
3. Android permite que un evento de **activity recognition transition** sea una excepción válida para iniciar un foreground service desde background.
4. Para crear de forma fiable un **location foreground service desde background** en Android moderno, el diseño de Auto Tracking debe contemplar `ACCESS_BACKGROUND_LOCATION`.
5. El viaje activo deberá ejecutarse como **foreground service de tipo location**, acompañado por una notificación visible/perceptible según las reglas de Android.
6. Si el usuario no concede background location, la app debe degradarse de forma limpia a un modo **Auto-assisted** o **Manual**, en lugar de quedar rota.
7. El registro pasivo no utilizará GPS preciso y frecuente de manera permanente.
8. Los permisos se solicitarán progresivamente y en contexto, no todos en el primer arranque.
9. Activity Recognition debe volver a registrarse después de reboot o actualización de la app.
10. Publicar en Google Play añade declaraciones/revisión para background location, foreground service y ubicación precisa.

---

## 3. Baseline de plataforma

### 3.1 Target API

A fecha de corte, Google Play exige que nuevas aplicaciones y actualizaciones para Android móvil tengan como objetivo **Android 16 / API 36 o superior** desde el 31 de agosto de 2026.

**Decisión F0.4:**

- V2 se diseñará desde el inicio pensando en las restricciones de `targetSdk 36`.
- F0.8 fijará `compileSdk`, `targetSdk`, `minSdk` y toolchain exactos.
- No se diseñará una arquitectura que dependa de comportamientos permisivos de Android antiguos.

### 3.2 minSdk

No se fija todavía.

La Activity Recognition API tiene comportamiento compatible con versiones anteriores mediante Google Play services, pero el valor final de `minSdk` se decidirá en F0.8 considerando dispositivos objetivo y coste de compatibilidad.

---

## 4. Activity Recognition

### 4.1 API preferida

Google recomienda **Activity Recognition Transition API** sobre el muestreo bruto cuando sea posible porque aplica filtrado de transiciones, mejora precisión práctica y reduce consumo.

La Transition API permite recibir entradas/salidas de actividades como:

- `IN_VEHICLE`
- `ON_FOOT`
- `WALKING`
- `RUNNING`
- `ON_BICYCLE`
- `STILL`

### 4.2 Ventaja directa para Moto Trip Tracker

La documentación indica expresamente que el filtrado de transición evita tratar estados transitorios —por ejemplo quedar `STILL` en un semáforo— como una salida real de `IN_VEHICLE`.

Esto encaja con F0.3:

```text
TRACKING
   ↓
semáforo / presa / alto
   ↓
NO finalizar por una señal STILL transitoria
```

### 4.3 Límite crítico: no existe MOTORCYCLE

Android/Google Play services no ofrece una actividad `MOTORCYCLE`.

`IN_VEHICLE` significa que el dispositivo está dentro/en un vehículo y puede corresponder a:

- motocicleta;
- automóvil;
- taxi;
- bus;
- otro vehículo motorizado.

**Regla aceptada:**

> Activity Recognition es un trigger de candidato de viaje, no una prueba de motocicleta.

No se mostrará una confianza falsa de “moto detectada” basada únicamente en Activity Recognition.

### 4.4 Permiso

En Android 10 / API 29 o superior se requiere el permiso runtime:

`android.permission.ACTIVITY_RECOGNITION`

La app deberá seguir funcionando manualmente si el usuario lo rechaza.

### 4.5 Re-registro

Google recomienda volver a registrar solicitudes de Activity Recognition después de:

- `BOOT_COMPLETED`;
- `MY_PACKAGE_REPLACED` / actualización de la app.

Por tanto, Auto Tracking no dependerá de que un registro realizado una sola vez viva indefinidamente.

---

## 5. Inicio automático desde background

### 5.1 Restricción general Android 12+

Desde Android 12 / API 31, una app en background no puede iniciar libremente un foreground service.

Sin embargo, Android incluye entre las excepciones explícitas:

> recibir un evento relacionado con **activity recognition transition**.

Esto hace viable conceptualmente:

```text
Activity Transition callback
        ↓
Candidate Start
        ↓
Foreground Service
```

### 5.2 Restricción adicional Android 14+

Los servicios que requieren permisos `while-in-use`, incluido `location`, tienen restricciones adicionales.

Android documenta que un foreground service de tipo `location` no puede crearse desde background utilizando únicamente permisos de ubicación “while in use”.

La documentación del tipo de servicio `location` indica que, para crearlo desde background, debe existir `ACCESS_BACKGROUND_LOCATION`.

### 5.3 Implicación de arquitectura

Para el comportamiento completo:

> “La app está cerrada/no visible → detecta IN_VEHICLE → valida → comienza a registrar GPS detallado sin intervención”

el baseline de V2 debe contemplar **background location**.

Esto se considera una conclusión de plataforma, no todavía una decisión de UX/permisos final.

---

## 6. Foreground Service para Trip activo

Un viaje activo que necesita ubicación frecuente durante minutos u horas no debe depender de una app simplemente “en background”.

El candidato de arquitectura es un foreground service de tipo:

`location`

Para Android 14+ el manifiesto debe declarar:

- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_LOCATION`
- `android:foregroundServiceType="location"`

Además, el usuario debe haber concedido ubicación (`COARSE` o `FINE`) y tener servicios de ubicación habilitados.

### Regla de producto

El foreground service existe **solo durante trabajo perceptible relacionado con un Trip activo**.

No se mantendrá un foreground service de ubicación ejecutándose 24/7 solo para esperar un viaje.

---

## 7. Permisos de ubicación

### 7.1 Foreground location

Android permite:

- `ACCESS_COARSE_LOCATION`
- `ACCESS_FINE_LOCATION`

El usuario puede optar por ubicación aproximada incluso si la app pide precisa.

F0.5 deberá medir si la ubicación aproximada es insuficiente para:

- dibujo fiel de rutas;
- velocidad;
- distancia;
- detección de inicio/final.

La hipótesis actual es que **FINE será necesaria para la función principal**, pero F0.5 debe justificarlo técnicamente.

### 7.2 Background location

En Android 11+ no existe un botón directo “Allow all the time” en el diálogo inicial habitual. El usuario debe concederlo desde una pantalla de configuración del sistema, después de una explicación educativa.

Android recomienda que la aplicación:

- explique por qué lo necesita;
- muestre la etiqueta correspondiente del sistema;
- permita rechazarlo;
- continúe ofreciendo funcionalidad si se rechaza.

### 7.3 Precisión heredada al background

Si el usuario concede background location pero solo ubicación aproximada en foreground, el acceso en background también permanece aproximado.

---

## 8. Modelo de permisos propuesto

Los permisos se solicitarán por etapas.

### Nivel A — Uso manual básico

Necesario para registrar cuando el usuario inicia manualmente:

- ubicación foreground;
- foreground service location durante Trip.

### Nivel B — Detección automática

Añade:

- `ACTIVITY_RECOGNITION`.

### Nivel C — Auto Tracking completo

Añade:

- `ACCESS_BACKGROUND_LOCATION`.

### Nivel D — Experiencia completa de notificación

En Android 13+:

- `POST_NOTIFICATIONS`.

### Permisos de manifiesto previstos

| Permiso | Tipo | Motivo |
|---|---|---|
| ACTIVITY_RECOGNITION | Runtime API 29+ | Detección pasiva de actividad |
| ACCESS_FINE_LOCATION | Runtime | Ruta precisa / métricas, sujeto a F0.5 |
| ACCESS_COARSE_LOCATION | Runtime | Base de permisos de ubicación |
| ACCESS_BACKGROUND_LOCATION | Runtime especial | Auto-start/ubicación desde background |
| FOREGROUND_SERVICE | Normal | Mantener Trip activo |
| FOREGROUND_SERVICE_LOCATION | Normal | FGS de tipo location |
| POST_NOTIFICATIONS | Runtime API 33+ | Mostrar controles/notificaciones de viaje |
| RECEIVE_BOOT_COMPLETED | Normal | Re-registrar detección tras reboot |

La lista final se cierra en F0.11 y F0.8.

---

## 9. Modos de funcionamiento según permisos

La aplicación no debe tener una sola configuración “todo o nada”.

### MODE 1 — FULL AUTO

Permisos suficientes:

```text
Activity Recognition
+ Fine Location
+ Background Location
+ Location FGS
        ↓
Auto detect
Auto validate
Auto start
Auto stop
```

Este es el objetivo de experiencia principal.

### MODE 2 — ASSISTED AUTO

Activity Recognition disponible, pero background location no concedido.

```text
Posible viaje detectado
        ↓
Notificación
“Parece que comenzaste un viaje”
        ↓
Usuario toca Iniciar
        ↓
Trip foreground
```

Permite conservar gran parte del valor sin forzar el permiso más sensible.

### MODE 3 — MANUAL

Sin Activity Recognition o con Auto Tracking desactivado:

```text
Usuario toca Start
        ↓
Trip foreground
```

Historial, mapas, métricas y gestión siguen funcionando.

### MODE 4 — LOCATION DEGRADED

Solo ubicación aproximada o ubicación deshabilitada.

La app deberá indicar claramente que el registro de ruta/estadísticas puede no ser suficientemente fiable, en vez de presentar datos de precisión falsa.

---

## 10. Notificaciones

### 10.1 Foreground service

Un foreground service debe mantener una notificación asociada.

La notificación de Trip activo será parte del diseño funcional y ofrecerá acciones como:

- Pause;
- Resume;
- Finish.

### 10.2 Android 13+

Desde Android 13 existe el permiso runtime `POST_NOTIFICATIONS`.

Si el usuario lo deniega:

- la app todavía puede iniciar un foreground service si cumple los demás requisitos;
- el aviso del servicio sigue apareciendo en el Task Manager;
- pero la notificación del foreground service puede no aparecer normalmente en el notification drawer.

**Implicación:** solicitar notificaciones aporta transparencia y los controles rápidos previstos por F0.1/F0.2, pero no debe confundirse con el permiso que habilita el FGS.

---

## 11. Background location normal vs Trip activo

Android 8+ limita las actualizaciones de ubicación de una app que simplemente está ejecutándose en background a unas pocas veces por hora.

Por tanto:

```text
BACKGROUND NORMAL
≠
TRACKING ACTIVO DE ALTA FRECUENCIA
```

La aplicación no intentará implementar un Trip activo mediante simples callbacks de background location sin foreground service.

El modo pasivo debe apoyarse en APIs de bajo consumo; el modo activo utiliza un servicio perceptible para el usuario.

---

## 12. Reboot, actualización y recuperación

### 12.1 Detección pasiva

Después de reboot o reemplazo/actualización de paquete:

1. recibir evento permitido del sistema;
2. comprobar si Auto Tracking sigue habilitado y tiene permisos;
3. volver a registrar Activity Recognition Transition API.

### 12.2 Trip activo durante reboot/process death

F0.4 no promete que un foreground service sobreviva de forma mágica a cualquier interrupción.

La regla aceptada es:

> El estado de Trip nunca dependerá exclusivamente de que un Service siga vivo.

F0.10 definirá:

- persistencia de estado;
- recuperación tras process death;
- reboot durante Trip;
- reconciliación de puntos faltantes;
- cuándo reanudar automáticamente y cuándo pedir confirmación.

### 12.3 BOOT_COMPLETED

No se diseñará el reinicio del tracking como una dependencia ciega de “arrancar inmediatamente un location FGS desde BOOT_COMPLETED”. Android moderno aplica restricciones específicas por tipo y versión.

El receiver se usa inicialmente para restaurar la **capacidad de detección** y reconciliar estado persistido.

---

## 13. Batería y ejecución pasiva

La Transition API es preferida como trigger porque está diseñada para detección de actividad con bajo consumo y evita mantener un servicio propio permanentemente.

**Regla aceptada:**

> No continuous high-frequency GPS while IDLE.

La ubicación precisa se activa cuando existe evidencia suficiente de un posible Trip o cuando el usuario inicia manualmente.

F0.5 determinará:

- burst de validación;
- frecuencia activa;
- batching;
- prioridades FLP;
- distancia mínima entre updates;
- estrategia de batería.

---

## 14. Dependencia de Google Play services

Activity Recognition Transition API y Fused Location Provider pertenecen a Google Play services.

Esto introduce una decisión que F0.8 debe cerrar:

### Opción inicial recomendada

V2 puede asumir dispositivos Android con Google Play services para su primera versión, porque:

- el proyecto es inicialmente personal;
- reduce significativamente la complejidad;
- las APIs seleccionadas son las candidatas principales para detección y ubicación.

No se implementará un fallback AOSP paralelo “por si acaso” sin necesidad demostrada.

**Estado:** recomendación, no ADR final.

---

## 15. Google Play y política de permisos

### 15.1 Background location

Google Play permite background location únicamente cuando es esencial para la función principal y aporta un beneficio claro al usuario.

Moto Trip Tracker deberá presentar el caso como una única función principal coherente:

> **Automatic trip detection and recording while the phone is not actively being used.**

Si se publica, se necesitarán los materiales de declaración que correspondan, incluyendo disclosure prominente y demostración/revisión según Play Console.

### 15.2 Foreground Service

Para apps target Android 14+ se deben declarar los tipos de foreground service utilizados en Play Console y justificar su función perceptible para el usuario.

Un Trip activo cumple conceptualmente el patrón de trabajo de larga duración que el usuario puede percibir y detener mediante una notificación clara.

### 15.3 Fine Location — política 2026/2027

Google Play anunció una política de alcance mínimo para ubicación precisa.

Fechas relevantes al corte de F0.4:

- noviembre 2026: disponibilidad de declaración para apps que usan `ACCESS_FINE_LOCATION`;
- 27 enero 2027: cumplimiento obligatorio previsto.

Por tanto F0.5 debe producir una justificación técnica explícita de por qué una ruta de motocicleta necesita precisión fina y por qué ubicación aproximada no entrega la función principal con calidad suficiente.

---

## 16. Restricciones OEM y ahorro de batería

Android estándar no es el único comportamiento que deberá probarse.

Fabricantes pueden aplicar políticas adicionales de batería/proceso.

F0.4 no adoptará como baseline pedir al usuario que desactive optimización de batería globalmente.

La estrategia será:

1. diseñar correctamente con APIs oficiales;
2. medir en hardware real;
3. registrar fallos por fabricante/versión;
4. introducir instrucciones específicas solo si la evidencia demuestra una necesidad.

Los escenarios pasan a F0.6.

---

## 17. Decisiones aceptadas de F0.4

### AND-001 — Activity Recognition Transition API como trigger pasivo principal

**Estado:** ACEPTADO baseline.

Motivo: bajo consumo, filtrado de transiciones y soporte explícito para entrada/salida de vehículo.

---

### AND-002 — IN_VEHICLE no equivale a motocicleta

**Estado:** ACEPTADO.

La clasificación se utilizará como señal de candidato, nunca como identificación específica del vehículo.

---

### AND-003 — Trip activo usa location foreground service

**Estado:** ACEPTADO baseline.

Un viaje activo necesita continuidad y ubicación frecuente incompatible con background normal.

---

### AND-004 — Full Auto contempla ACCESS_BACKGROUND_LOCATION

**Estado:** ACEPTADO baseline, sujeto a validación práctica F0.6.

Es necesario para el flujo completamente automático desde background en Android moderno.

---

### AND-005 — Permisos progresivos

**Estado:** ACEPTADO.

No solicitar todos los permisos al instalar/primer arranque.

---

### AND-006 — Degradación funcional obligatoria

**Estado:** ACEPTADO.

Si un permiso se rechaza, la app baja de Full Auto → Assisted Auto → Manual en lugar de bloquear todo el producto.

---

### AND-007 — Re-register Activity Recognition tras reboot/update

**Estado:** ACEPTADO.

---

### AND-008 — No GPS preciso permanente en IDLE

**Estado:** ACEPTADO.

---

### AND-009 — Target moderno

**Estado:** ACEPTADO.

La arquitectura debe funcionar con las restricciones de target API 36; no se apoyará en excepciones antiguas.

---

## 18. Matriz de compatibilidad conceptual

| Caso | Activity Recognition | Foreground Location | Background Location | Resultado |
|---|---:|---:|---:|---|
| Full Auto | Sí | Sí | Sí | Auto detect/start/track |
| Assisted Auto | Sí | Sí | No | Detecta; usuario confirma inicio |
| Manual | No/Off | Sí | No necesario para inicio visible | Usuario inicia/finaliza |
| Sin location | Puede existir | No | No | No se registra ruta válida |
| Approximate only | Sí/No | Limitado | Limitado | Modo degradado; validar en F0.5 |

---

## 19. Preguntas transferidas a F0.5

F0.5 debe responder con evidencia y/o pruebas:

1. ¿Qué prioridad FLP se usará durante validación y tracking?
2. ¿Qué intervalos son apropiados para motocicleta?
3. ¿Qué `minUpdateDistanceMeters` tiene sentido?
4. ¿Cómo balancear precisión y batería?
5. ¿Qué accuracy convierte un punto en dudoso/inválido?
6. ¿Usar `Location.getSpeed()` o velocidad derivada, o combinación?
7. ¿Cómo filtrar speed spikes?
8. ¿Cómo calcular distancia con GPS ruidoso?
9. ¿Cómo tratar puntos out-of-order/batched?
10. ¿Qué calidad real aporta approximate vs precise location?
11. ¿Qué fuente de altitud utilizar y cómo suavizarla?
12. ¿Cómo calcular ascent/descent sin acumular ruido vertical?
13. ¿Cuántos puntos necesita el Start Validator?
14. ¿Cuánto GPS debe usarse durante Candidate Start/Stop?

---

## 20. Preguntas transferidas a fases posteriores

### F0.6 — Field Experiments

- comportamiento real de IN_VEHICLE sobre motocicleta;
- latencia de transición;
- falsos positivos en carro/bus/bicicleta;
- comportamiento con pantalla apagada;
- OEM battery management;
- background auto-start real en API objetivo;
- re-registro después de reboot/update.

### F0.8 — Arquitectura

- minSdk definitivo;
- dependencia obligatoria de Google Play services;
- componentes concretos Receiver/Service/Repository;
- lifecycle del foreground service;
- estrategia de DI y persistencia.

### F0.10 — Fiabilidad

- process death;
- reboot durante Trip;
- service restart;
- pérdida/recuperación de GPS;
- consistencia transaccional.

### F0.11 — Privacidad y permisos

- texto exacto de rationale/disclosure;
- flujo de background location;
- política de privacidad;
- Play declarations;
- zonas privadas y sharing.

---

## 21. Riesgos identificados

| Riesgo | Impacto | Tratamiento |
|---|---|---|
| IN_VEHICLE detecta cualquier vehículo | Alto | No identificar moto; validar con otras señales/contexto |
| Usuario niega background location | Alto para Full Auto | Assisted Auto / Manual |
| Usuario niega Activity Recognition | Medio | Manual tracking completo |
| Usuario concede approximate location | Alto para métricas | Medir en F0.5 y advertir/degradar |
| OEM mata procesos agresivamente | Medio/alto | Field tests + persistencia + diagnóstico |
| Política Play cambia | Medio | Mantener permisos centrales y justificables; revisar antes de release |
| Google Play services ausente | Medio | Scope inicial GMS; reevaluar solo si surge necesidad |
| Reboot durante viaje | Alto | F0.10 recovery design; no confiar solo en Service |

---

## 22. Criterio de cierre F0.4

F0.4 queda cerrado como baseline cuando:

- [x] Activity Recognition y sus límites están documentados.
- [x] Se identifica el permiso `ACTIVITY_RECOGNITION`.
- [x] Se documentan restricciones de foreground service Android 12+ y 14+.
- [x] Se documenta la necesidad conceptual de background location para Full Auto.
- [x] Existe una estrategia de degradación si faltan permisos.
- [x] Se documentan notificaciones Android 13+.
- [x] Se documenta re-registro tras reboot/app update.
- [x] Se documentan obligaciones principales de Google Play.
- [x] Las preguntas de GPS/algoritmos se transfieren a F0.5.
- [x] No se fijan todavía intervalos GPS ni algoritmos sin investigación.

**Decisión:** F0.4 CERRADO baseline v0.1.

---

## 23. Fuentes oficiales consultadas

Fecha de consulta: 2026-09-15.

1. Google Play services — `ActivityRecognitionClient`  
   https://developers.google.com/android/reference/com/google/android/gms/location/ActivityRecognitionClient

2. Google Play services — `DetectedActivity`  
   https://developers.google.com/android/reference/com/google/android/gms/location/DetectedActivity

3. Android Developers — Restrictions on starting a foreground service from the background  
   https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

4. Android Developers — Foreground service types  
   https://developer.android.com/develop/background-work/services/fgs/service-types

5. Android Developers — Request background location  
   https://developer.android.com/develop/sensors-and-location/location/permissions/background

6. Android Developers — Access location in the background  
   https://developer.android.com/develop/sensors-and-location/location/background

7. Android Developers — Background location limits  
   https://developer.android.com/about/versions/oreo/background-location-limits

8. Android Developers — Optimize location for battery  
   https://developer.android.com/develop/sensors-and-location/location/battery/optimize

9. Android Developers — Android 10 privacy changes / physical activity recognition  
   https://developer.android.com/about/versions/10/privacy/changes

10. Android Developers — Android 13 notification behavior  
    https://developer.android.com/about/versions/13/behavior-changes-13

11. Google Play Console Help — Background location permissions  
    https://support.google.com/googleplay/android-developer/answer/9799150

12. Google Play Console Help — Foreground service requirements  
    https://support.google.com/googleplay/android-developer/answer/13392821

13. Google Play Console Help — Target API level requirements  
    https://support.google.com/googleplay/android-developer/answer/11926878

14. Google Play Console Help — Minimum scope / precise location policy  
    https://support.google.com/googleplay/android-developer/answer/17033915

---

## 24. Próximo workstream

**F0.5 — GPS & Location Research**

F0.5 utilizará F0.3 como contrato de comportamiento y F0.4 como contrato de plataforma para decidir cómo obtener, validar y procesar las señales geográficas reales.
