# F0.9 — UX y Arquitectura de Navegación
## Moto Trip Tracker V2

**Estado:** CERRADO baseline UX  
**Versión:** 0.1  
**Fecha:** 2026-09-15  
**Proyecto:** Moto Trip Tracker V2  
**Depende de:** F0.1–F0.8  

---

## 1. Objetivo

Convertir los requisitos, la máquina de estados, el modelo de datos y la arquitectura técnica en un flujo de uso coherente antes de implementar UI.

F0.9 define:

- arquitectura de información;
- destinos principales;
- comportamiento de Home;
- experiencia de Trip activo;
- History, Trip Detail y Favorites;
- configuración de Auto Tracking;
- UX de Pause / Resume / Finish;
- merge, split, delete y corrección;
- onboarding y estados degradados;
- notificaciones operativas;
- reglas de Back, recuperación visual y navegación adaptable;
- wireframes de baja fidelidad;
- criterios medibles de aceptación UX.

No define todavía colores finales, branding, tipografía definitiva, proveedor de mapas ni animaciones de acabado.

### Decisión ejecutiva

> Moto Trip Tracker V2 será **ride-first y glanceable**: la aplicación debe ser útil sin exigir abrirla durante un viaje. Cuando exista un Trip activo, ese estado tiene prioridad visual sobre cualquier otra función. La navegación principal será simple: **Inicio, Historial y Favoritos**; Settings será secundaria y el Trip activo será un destino especial persistente, no una pestaña.

---

## 2. Principios UX

1. **No interacción obligatoria mientras se conduce.** El recorrido automático debe funcionar sin tocar la pantalla.
2. **Control manual siempre disponible.** Start, Pause, Resume y Finish deben ser fáciles de encontrar.
3. **El Trip activo manda.** Si existe un Trip activo, cualquier pantalla principal debe permitir volver a él en una acción.
4. **Estado visible, lógica interna oculta.** El usuario ve “Registrando”, “Pausado”, “Preparando” o “Problema de ubicación”; no necesita conocer todos los estados internos del detector.
5. **Automatización explicable.** Auto Tracking debe mostrar si está listo, limitado o desactivado, y por qué.
6. **Nada crítico depende solo del color.** Texto, icono, forma y/o etiqueta acompañan estados importantes.
7. **Errores recuperables.** Delete, Merge, Split y Finish no deben provocar pérdidas silenciosas.
8. **Offline-first también en UX.** La falta de Internet puede afectar tiles/enriquecimiento, nunca impedir registrar o consultar datos locales.
9. **Mapa es protagonista, no dependencia.** Si el mapa no carga, las estadísticas y el Trip siguen siendo utilizables.
10. **Pocos destinos, profundidad controlada.** No convertir el producto en un dashboard de múltiples pestañas desde V1.

---

## 3. Navegación principal

### 3.1 Destinos Core

| ID | Destino | Responsabilidad |
|---|---|---|
| HOME-01 | Inicio | Estado de tracking, Start manual, Trip activo, recientes y acceso rápido. |
| HIS-01 | Historial | Lista cronológica de Trips y selección para operaciones. |
| FAV-01 | Favoritos | Trips favoritos; Routes favoritas cuando F0.1/P1 se active. |
| SET-01 | Configuración | Auto Tracking, permisos/capacidades, unidades, notificaciones, datos y diagnóstico. |
| TRP-01 | Trip activo | Pantalla operativa especial; no forma parte del bottom nav. |
| HIS-02 | Detalle de Trip | Mapa, métricas, paradas, acciones y edición. |

### 3.2 Navegación compacta

En teléfonos compactos:

```text
[ Inicio ]   [ Historial ]   [ Favoritos ]
```

Settings se abre desde la app bar / menú superior. No se crea una pestaña “Más” solo para ocultar configuración.

### 3.3 Navegación adaptable

En ventanas más anchas el mismo conjunto de destinos puede representarse como rail/navigation suite. No se diseñan flujos distintos por dispositivo; cambia el contenedor, no la semántica.

### 3.4 Estadísticas agregadas

`STAT-01` queda Post-Core. Hasta entonces:

- métricas por Trip viven en HIS-02;
- Inicio puede mostrar un resumen pequeño (p. ej. km del mes) cuando F0.9/P1 lo apruebe;
- no se reserva una pestaña vacía de Estadísticas en Core.

---

## 4. Jerarquía de navegación

```text
App
├── Inicio (HOME-01)
│   ├── Trip activo (TRP-01)
│   ├── Inicio manual
│   ├── Trip reciente -> HIS-02
│   └── Configuración -> SET-01
│
├── Historial (HIS-01)
│   ├── Trip Detail (HIS-02)
│   ├── Merge (MRG-01 / MRG-02)
│   └── Split (SPL-01)
│
├── Favoritos (FAV-01)
│   └── Trip Detail (HIS-02)
│
└── Configuración (SET-01)
    ├── Auto Tracking / readiness (SET-02)
    ├── Notificaciones
    ├── Unidades
    ├── Datos / exportación futura
    └── Diagnóstico (dev / avanzado)
```

Post-Core:

```text
Routes -> RTE-01
Analytics -> STAT-01
Calendar -> CAL-01
```

---

## 5. HOME-01 — Inicio

Home debe responder en segundos a dos preguntas:

1. **¿Está la app lista para registrar automáticamente?**
2. **¿Hay un Trip activo?**

### 5.1 Estado IDLE

Orden de prioridad:

1. Estado de Auto Tracking.
2. CTA `Iniciar viaje`.
3. Resumen/últimos Trips.
4. Accesos secundarios.

Ejemplo de estado:

- `Auto Tracking listo`
- `Auto Tracking limitado — falta ubicación en segundo plano`
- `Auto Tracking desactivado`
- `Ubicación desactivada`

El texto debe incluir acción correctiva cuando aplique.

### 5.2 Trip activo

Cuando existe un Trip activo, Home sustituye la tarjeta de readiness como foco principal por:

- estado `Registrando` o `Pausado`;
- duración;
- distancia registrada;
- CTA `Ver viaje activo`;
- acción secundaria Pause/Resume cuando sea seguro y clara.

No se muestran dashboards decorativos por encima del Trip activo.

### 5.3 Candidate Start

El estado interno `CANDIDATE_START` no necesita una pantalla modal. Puede mostrarse discretamente como:

> “Preparando registro…”

si el usuario abre la app durante la validación. No debe obligarlo a confirmar cada viaje automático.

---

## 6. TRP-01 — Trip activo

### 6.1 Objetivo

Permitir una lectura rápida del estado y controles esenciales sin convertir la pantalla en un tablero de conducción.

### 6.2 Información Core visible sin scroll excesivo

- estado: `Registrando` / `Pausado`;
- duración;
- distancia;
- hora de inicio;
- mapa/trazado cuando esté disponible;
- calidad/problema de ubicación solo si requiere atención;
- Pause / Resume;
- Finish.

La velocidad actual **no será un elemento protagonista por defecto**. Velocidad máxima/promedio pertenecen principalmente al resumen y detalle del Trip. Esto reduce distracción y evita convertir la pantalla en velocímetro.

### 6.3 Acciones

Mientras TRACKING:

- `Pausar` — acción primaria secundaria de alto acceso;
- `Finalizar` — acción explícita;
- Back — solo abandona la pantalla visual, **nunca** pausa/finaliza.

Mientras MANUAL_PAUSED:

- `Reanudar` — CTA principal;
- `Finalizar` — disponible;
- mostrar duración de pausa.

### 6.4 Finish desde la app

Desde TRP-01, `Finalizar` abre una confirmación breve:

> “¿Finalizar este viaje?”

Acciones: `Volver` / `Finalizar`.

No se pregunta por nombre, favorito o notas antes de cerrar el registro; esos datos se editan después. Finalizar debe ser rápido.

### 6.5 Finish desde notificación

La notificación puede ejecutar Finish como acción directa para evitar una cadena de interacción. La app debe ofrecer inmediatamente un acceso al Trip resultante. Correcciones posteriores (merge/split) cubren un Finish accidental; F0.10 puede añadir un undo temporal si la fiabilidad lo justifica.

---

## 7. Notificación de Trip activo

El foreground service necesita una notificación visible. UX baseline:

### TRACKING

```text
Moto Trip Tracker
● Viaje en curso · 38.4 km · 47 min
[ Pausar ]   [ Finalizar ]
```

### MANUAL_PAUSED

```text
Moto Trip Tracker
Ⅱ Viaje pausado · 42 min
[ Reanudar ] [ Finalizar ]
```

Tocar el cuerpo de la notificación abre TRP-01.

Reglas:

- máximo de acciones operativas esenciales;
- no agregar Markers/Favorites/Share a la notificación Core;
- las acciones deben ser idempotentes respecto al estado real;
- labels claros y localizables;
- una notificación de foreground service no se usa como espacio promocional.

---

## 8. HIS-01 — Historial

### 8.1 Contenido

Lista cronológica descendente por defecto.

Cada fila/tarjeta debe mostrar como mínimo:

- nombre del Trip o nombre generado;
- fecha/hora;
- distancia;
- duración;
- indicador de favorito;
- opcionalmente miniatura estática/preview de ruta si no perjudica rendimiento.

### 8.2 Nombre generado

Si el usuario no renombra el Trip, la aplicación debe mostrar un nombre legible y estable. El algoritmo exacto queda pendiente de geocoding/map provider; nunca se bloquea el guardado por no disponer de nombre enriquecido.

Fallback permitido:

> `Viaje · 15 sep · 08:42`

### 8.3 Operaciones

Core:

- abrir detalle;
- favorite/unfavorite;
- delete;
- selección múltiple para merge;
- acceso a split desde detalle.

P1:

- búsqueda;
- filtros avanzados;
- tags;
- motorcycle;
- calendar.

### 8.4 Multi-select

Long press o acción `Seleccionar` entra a modo selección. No se requiere descubrir un gesto oculto para Merge.

---

## 9. HIS-02 — Detalle de Trip

### 9.1 Jerarquía

1. Nombre + fecha + favorito.
2. Mapa del recorrido.
3. Distancia + duración.
4. Métricas principales.
5. Elevación/velocidad/gráficas cuando existan.
6. Paradas / pausas.
7. Notas, tags, motorcycle y contexto futuro.
8. Acciones de edición.

### 9.2 Métricas Core

- distancia;
- duración total;
- moving time;
- stopped time;
- manual pause time;
- velocidad máxima filtrada;
- velocidad promedio;
- promedio en movimiento;
- altitud mínima/máxima cuando sea fiable;
- ascenso/descenso solo si el pipeline aprobado lo permite;
- calidad del registro cuando aporte valor.

### 9.3 Map failure / offline

Si el renderer/tiles no están disponibles:

- mostrar estado `Mapa no disponible`;
- mantener estadísticas, acciones y datos del Trip;
- no tratar el Trip como corrupto.

### 9.4 Acciones de detalle

Acciones frecuentes visibles:

- Favorite;
- Rename/Edit.

Overflow:

- Split;
- Delete;
- Export/Share cuando existan;
- Diagnostics en builds dev/avanzados.

Merge se inicia preferentemente desde Historial/multi-select porque requiere más de un Trip.

---

## 10. FAV-01 — Favoritos

Core contiene `Trips` favoritos.

Cuando `Route` entre en P1, Favoritos incorpora dos segmentos/pestañas internas:

```text
Trips | Routes
```

No se crean dos destinos principales distintos.

Estado vacío:

> “Aún no tienes favoritos. Marca la estrella en un viaje para encontrarlo aquí.”

---

## 11. Merge UX

### MRG-01 — Selección

Desde Historial:

1. Entrar a seleccionar.
2. Marcar 2 o más Trips.
3. Acción `Fusionar`.

Validaciones visibles antes de confirmar:

- orden cronológico;
- gaps grandes;
- Trips incompatibles/no válidos;
- si un Trip está en Trash/Superseded.

### MRG-02 — Confirmación

Mostrar:

- Trips origen;
- rango total de fecha/hora;
- distancia estimada resultante;
- advertencia si existe gap considerable;
- nombre editable opcional.

CTA:

`Cancelar` / `Fusionar`.

El usuario no necesita entender `TripPart` o lineage. La UX habla de “combinar viajes”.

---

## 12. Split UX

### SPL-01 — Dividir viaje

El usuario abre Split desde Trip Detail.

La pantalla presenta:

- mapa de ruta;
- timeline/distancia;
- un único punto de división seleccionable;
- preview de `Parte 1` y `Parte 2` con distancia/duración aproximadas.

Acciones:

`Cancelar` / `Dividir`.

No debe requerir editar coordenadas manualmente.

Un Split inicial crea exactamente dos Trips. Split múltiple puede lograrse repitiendo la operación; no hace falta una UX compleja de N cortes en Core.

---

## 13. Delete y Trash

Eliminar desde detalle requiere confirmación explícita:

> “Mover este viaje a eliminados?”

Baseline UX prefiere soft delete / Trash cuando el modelo lo soporte. F0.10/F0.11 definirán retención exacta.

No se ofrece delete irreversible como primer CTA.

---

## 14. SET-01 — Configuración

Secciones conceptuales:

### Tracking

- Auto Tracking ON/OFF;
- `Estado de Auto Tracking`;
- sensibilidad/thresholds avanzados **no** visibles en Core normal;
- enlace a `SET-02 Configurar Auto Tracking`.

### Unidades

- km / mi;
- km/h / mph;
- m / ft según política de unidades.

### Notificaciones

- completion summary;
- pause reminders;
- warnings no esenciales.

La notificación necesaria para un Trip activo se explica como requisito operativo cuando aplique.

### Datos

- export/backup cuando se implemente;
- Trash cuando exista;
- reset/borrado total en sección protegida.

### Acerca de / Diagnóstico

- versión;
- build;
- detector/location/processing version en modo avanzado/dev;
- acceso a diagnóstico según F0.13.

---

## 15. SET-02 — Auto Tracking readiness

Esta pantalla traduce permisos/capacidades técnicas a estados comprensibles.

### Estados

#### FULL AUTO READY

> “Auto Tracking está listo para detectar y registrar viajes automáticamente.”

#### ASSISTED AUTO

> “La detección puede funcionar, pero Android limita el inicio automático de ubicación. Puedes iniciar manualmente cuando sea necesario.”

#### MANUAL ONLY

> “Puedes registrar viajes manualmente. Activa los permisos opcionales si quieres detección automática.”

#### LOCATION DEGRADED

> “La ubicación precisa no está disponible. El registro de ruta puede ser incompleto.”

No se muestra una lista cruda de permisos como producto principal; se explica la **capacidad obtenida**.

---

## 16. Onboarding y permisos

### ONB-01 — Bienvenida

Explica en una pantalla:

- qué registra la app;
- que funciona manualmente aunque no habilites Auto Tracking;
- que los datos Core son locales/offline-first.

CTA: `Continuar`.

### ONB-02 — Configurar Auto Tracking

Explica el beneficio antes de pedir permisos:

> “Para iniciar viajes automáticamente, Moto Trip Tracker necesita reconocer cuándo viajas y acceder a ubicación según las reglas de Android.”

El flujo exacto de permisos se cerrará en F0.11, pero UX fija:

- permisos contextuales, no todos al primer frame;
- no ocultar por qué se solicitan;
- denegar no crea un dead end;
- ofrecer `Ahora no` y continuar en Manual cuando la plataforma lo permita;
- background location se explica como capacidad adicional, no como permiso misterioso.

---

## 17. Estados problemáticos

### GPS temporalmente débil

En Trip activo:

> `Señal de ubicación limitada · seguimos registrando`

No modal.

### Ubicación desactivada

Si impide continuar:

- aviso visible;
- CTA para resolver;
- el Trip no desaparece.

### Permiso revocado durante Trip

- conservar Trip y puntos previos;
- mostrar estado degradado;
- ofrecer resolución cuando el usuario abra la app.

### Sin Internet

No banner alarmista si el tracking funciona. Solo indicar que el mapa/enriquecimiento podría no cargar cuando realmente sea relevante.

### Processing pendiente

Después de Finish:

> `Procesando estadísticas…`

El Trip puede aparecer inmediatamente en History con estado de procesamiento; no bloquear la app con spinner de pantalla completa.

---

## 18. Back, deep links y restauración visual

1. Back desde TRP-01 **no** cambia el estado del Trip.
2. Back desde un diálogo de Finish/Merge/Split cancela la confirmación, no la operación subyacente ya confirmada.
3. El back stack no es fuente de verdad; reabrir HIS-02 vuelve a leer el Trip por ID.
4. Tocar la notificación del Trip activo navega a TRP-01 incluso si la app estaba cerrada/recreada.
5. Tocar una notificación de Trip completado abre HIS-02 del Trip correspondiente.
6. Predictive Back debe funcionar con el sistema; no interceptar Back en la Activity raíz por costumbre.
7. Si el proceso se recrea mientras hay Trip activo, Home/TRP-01 reflejan el estado persistido, no una copia UI vieja.

---

## 19. Mapas y gestos

El proveedor se mantiene sin decidir.

Contrato UX del componente mapa:

- pan/zoom estándar cuando el usuario interactúa;
- botón opcional `Ajustar ruta`;
- inicio/final distinguibles;
- no exigir precisión táctil para acciones destructivas;
- un marker seleccionado permanece seleccionado hasta cerrar/cambiar selección;
- mapa activo no debe bloquear scroll general accidentalmente en pantallas pequeñas;
- la ruta debe poder verse aunque tenga miles de puntos mediante representación optimizada.

Core no necesita navegación turn-by-turn ni mapa 3D.

---

## 20. Accesibilidad y ergonomía

- objetivos táctiles principales de al menos ~48 dp;
- labels/semantics para iconos importantes;
- decorativos sin lectura innecesaria;
- estados no dependientes únicamente del color;
- soportar escalado de fuente razonable;
- números críticos no deben recortarse con font scaling;
- contraste conforme a componentes Material cuando sea posible;
- acciones principales accesibles sin gestos ocultos;
- edge-to-edge respetando insets;
- uso con una mano cuando el usuario está detenido;
- no diseñar CTA pequeños cerca de bordes para Pause/Resume/Finish.

---

## 21. Wireframes de baja fidelidad

### HOME-01 — Idle

```text
+----------------------------------+
| Moto Trip Tracker        [⚙]     |
|----------------------------------|
| Auto Tracking                    |
| ● Listo                          |
|                                  |
| [      INICIAR VIAJE       ]     |
|----------------------------------|
| Recientes                        |
| Irazú          86.4 km · 1h47    |
| Trabajo        22.7 km · 39m     |
|----------------------------------|
| Inicio | Historial | Favoritos   |
+----------------------------------+
```

### HOME-01 — Trip activo

```text
+----------------------------------+
| Moto Trip Tracker        [⚙]     |
|----------------------------------|
| ● VIAJE EN CURSO                 |
| 38.4 km          00:47:12        |
| [ Ver viaje activo ]             |
|----------------------------------|
| Recientes                        |
| ...                              |
|----------------------------------|
| Inicio | Historial | Favoritos   |
+----------------------------------+
```

### TRP-01 — Tracking

```text
+----------------------------------+
| < Viaje en curso                 |
|----------------------------------|
| ● Registrando                    |
|                                  |
| 38.4 km          00:47:12        |
| Inicio 08:42                     |
|                                  |
| [          MAPA/RUTA          ]  |
|                                  |
| [ PAUSAR ]      [ FINALIZAR ]    |
+----------------------------------+
```

### TRP-01 — Pausado

```text
+----------------------------------+
| < Viaje pausado                  |
|----------------------------------|
| Ⅱ Pausado · 42 min               |
| 52.7 km                          |
|                                  |
| [          MAPA/RUTA          ]  |
|                                  |
| [ REANUDAR ]    [ FINALIZAR ]    |
+----------------------------------+
```

### HIS-01 — Historial

```text
+----------------------------------+
| Historial              [Buscar]  |
|----------------------------------|
| 15 SEP                           |
| Irazú                 ★          |
| 86.4 km · 1h47                   |
|----------------------------------|
| 13 SEP                           |
| Trabajo                          |
| 22.7 km · 39m                    |
|----------------------------------|
| Inicio | Historial | Favoritos   |
+----------------------------------+
```

### HIS-02 — Trip Detail

```text
+----------------------------------+
| < Irazú                  [★] [...]|
| 15 sep 2026 · 08:42              |
|----------------------------------|
| [            MAPA             ]  |
|----------------------------------|
| 86.4 km         1h 47 min        |
|                                  |
| Promedio       48 km/h           |
| Máxima        112 km/h           |
| Movimiento     1h 32             |
| Detenido       15 min            |
| Alt. máx.      2740 m            |
|----------------------------------|
| Paradas · Elevación · ...        |
+----------------------------------+
```

### FAV-01 — Favoritos

```text
+----------------------------------+
| Favoritos                        |
|----------------------------------|
| ★ Irazú                          |
|   86.4 km · 1h47                 |
| ★ Orosi                          |
|   74.2 km · 1h32                 |
|----------------------------------|
| Inicio | Historial | Favoritos   |
+----------------------------------+
```

### SPL-01 — Split

```text
+----------------------------------+
| < Dividir viaje                  |
|----------------------------------|
| [            MAPA             ]  |
|                                  |
| -----------●------------------   |
|            ↑ corte               |
|                                  |
| Parte 1          Parte 2         |
| 31.4 km          55.0 km         |
|                                  |
| [Cancelar]        [Dividir]      |
+----------------------------------+
```

---

## 22. Criterios medibles de aceptación UX

| ID | Criterio | Prioridad |
|---|---|---|
| UX-01 | Desde Home, iniciar manualmente un Trip requiere como máximo 1 CTA principal después de abrir la app. | Must |
| UX-02 | Si existe Trip activo, volver a TRP-01 desde un destino principal requiere una sola acción. | Must |
| UX-03 | Pause/Resume está disponible desde notificación y TRP-01 sin menú secundario. | Must |
| UX-04 | Finalizar desde TRP-01 requiere como máximo acción Finish + confirmación. | Must |
| UX-05 | Back desde TRP-01 no pausa, finaliza ni descarta el Trip. | Must |
| UX-06 | Denegar permisos opcionales no impide utilizar Start/Finish manual cuando técnicamente sea posible. | Must |
| UX-07 | El flujo Start manual → Trip activo → Finish → History funciona sin Internet. | Must |
| UX-08 | El usuario puede distinguir Full Auto / Assisted / Manual / Degraded sin leer nombres técnicos de permisos. | Must |
| UX-09 | Merge requiere selección explícita de ≥2 Trips y confirmación con preview/resumen. | Must |
| UX-10 | Split muestra el punto de corte y preview de las dos partes antes de confirmar. | Must |
| UX-11 | Delete no es irreversible con un único toque ambiguo. | Must |
| UX-12 | Controles principales cumplen ~48 dp y estados críticos no dependen solo del color. | Must |
| UX-13 | Estado, distancia y duración del Trip activo son legibles sin navegar por pestañas internas. | Must |
| UX-14 | Ninguna función Core exige interactuar con un mapa mientras se conduce. | Must |
| UX-15 | El Trip Detail continúa siendo útil aunque el mapa no pueda renderizar tiles. | Must |
| UX-16 | Navegación/Back es compatible con Predictive Back; no se secuestra Back en la Activity raíz. | Must |
| UX-17 | Reabrir la app durante un Trip activo muestra estado consistente con persistencia. | Must |
| UX-18 | Font scaling y TalkBack no ocultan Pause/Resume/Finish ni hacen ambiguos sus estados. | Must |

---

## 23. Fuentes/plataforma consultadas

F0.9 usa como referencias de plataforma:

- Android Accessibility / Compose: objetivo táctil mínimo recomendado de 48 dp.
- Material 3 Adaptive Navigation: `NavigationSuiteScaffold` permite barra en ventanas compactas y rail en ventanas expandidas manteniendo los mismos destinos.
- Predictive Back: integración con APIs modernas de navegación/Compose; evitar interceptar Back en la Activity raíz sin necesidad.
- Android Notifications: las notificaciones pueden exponer acciones rápidas mediante `PendingIntent`; una foreground service debe mantener una notificación visible.

Estas referencias condicionan ergonomía y navegación, no definen el diseño visual final.

---

## 24. Decisiones diferidas

F0.9 NO decide todavía:

- proveedor de mapas;
- geocoding/reverse geocoding;
- branding, paleta o iconografía final;
- librería de charts;
- animaciones avanzadas;
- diseño definitivo de Stats/Routes/Calendar P1;
- retención exacta de Trash;
- undo temporal de Finish/Merge/Split;
- copy legal final de permisos;
- privacy zones y share card final.

---

## 25. Criterio de cierre

F0.9 queda cerrado cuando:

- existe mapa de navegación Core;
- Home/Active Trip/History/Trip Detail/Favorites/Settings tienen responsabilidad clara;
- Pause/Resume/Finish están definidos en app y notificación;
- onboarding y estados degradados están definidos;
- merge/split/delete tienen flujo explícito;
- Back/recovery visual no altera estado de negocio;
- wireframes permiten recorrer el flujo crítico sin pantallas faltantes;
- criterios UX medibles están documentados;
- decisiones diferidas están explícitas.

### Decisión v0.9

**F0.9 queda CERRADO como baseline UX.** El diseño visual podrá evolucionar sin cambiar estas reglas funcionales. Cualquier cambio que altere navegación, ownership del Trip activo, comportamiento de Pause/Resume/Finish o seguridad de Merge/Split/Delete debe actualizar F0.9 antes de implementarse.

---

## 26. Próximo workstream

**F0.10 — Reliability & Recovery**

Debe definir las garantías de persistencia y recuperación ante process death, crash, reboot, GPS gaps, acciones concurrentes, fallos de DB y estados parcialmente procesados.
