# F0.11 — Privacidad y Permisos
## Moto Trip Tracker V2

**Estado:** CERRADO — baseline de privacidad/permisos v0.1  
**Versión:** 0.1  
**Fecha de corte:** 2026-09-15  
**Depende de:** F0.1–F0.10, especialmente F0.4, F0.5, F0.7, F0.9 y F0.10

---

## 1. Objetivo

F0.11 convierte el uso de ubicación, reconocimiento de actividad, notificaciones, almacenamiento, exportación y backups en una política coherente de privacidad y permisos para Moto Trip Tracker V2.

El objetivo no es redactar todavía el texto jurídico final de una política de privacidad. El objetivo es fijar **qué datos usa el producto, para qué los usa, dónde viven, cuándo pueden salir del dispositivo, cuánto se conservan y qué permiso/capacidad habilita cada comportamiento**.

Esta baseline debe permitir que Fase 1 implemente permisos sin improvisar y que una revisión posterior de Google Play pueda justificarse con una arquitectura ya diseñada bajo minimización de datos.

---

## 2. Decisión ejecutiva

> **Moto Trip Tracker V2 será local-first y private-by-default: el recorrido completo y la telemetría del viaje permanecen en el dispositivo salvo una acción explícita del usuario o una integración futura aprobada mediante ADR/revisión de privacidad.**

De esta decisión se derivan nueve reglas:

1. No se requiere cuenta ni servidor para registrar, procesar o consultar Trips Core.
2. No se transmite la ruta raw, historial, velocidad, altitud ni eventos del detector a un backend de Moto Trip Tracker en Core V2.
3. No se incluyen SDKs de publicidad en Core V2.
4. No se incluyen SDKs de analytics/crash reporting que reciban coordenadas, rutas o telemetría sensible sin revisión explícita posterior.
5. Los permisos se piden **en contexto y progresivamente**, nunca todos al primer arranque.
6. Denegar permisos opcionales degrada capacidades; no convierte toda la app en inutilizable.
7. Background Location existe únicamente para la función central **Auto Tracking**.
8. Exportar/compartir es una acción voluntaria y visible; una vez que el usuario entrega un archivo a otra app o ubicación externa, Moto Trip Tracker ya no controla esa copia.
9. La aplicación no mantendrá un diario oculto de actividad/movimiento mientras está IDLE.

---

## 3. Clasificación de datos

### 3.1 Datos de sensibilidad alta

Se consideran de alta sensibilidad interna:

- latitud/longitud precisa;
- TrackPoints raw y processed;
- inicio/final de viajes;
- timestamps asociados a ubicación;
- rutas frecuentes;
- velocidad, bearing, altitud y accuracy asociados a posición;
- privacy zones configuradas;
- archivos GPX/backup que contengan rutas completas.

Aunque varios de estos campos no sean individualmente identificadores civiles, en conjunto pueden revelar rutinas, domicilio, trabajo y hábitos de desplazamiento.

### 3.2 Datos de sensibilidad media

- Activity Recognition / estado de movimiento;
- TripEvent y detector state;
- nombres/notas/tags de Trips;
- Motorcycle profile;
- favoritos;
- estadísticas agregadas;
- timestamps sin coordenadas.

### 3.3 Datos de baja sensibilidad

- preferencias de unidades;
- flags de UX;
- versión de schema/detector/location/processing;
- preferencias de notificaciones no sensibles.

---

## 4. Inventario y retención de datos

| Dato | Propósito | Storage Core | ¿Sale del dispositivo por defecto? | Retención baseline |
|---|---|---|---|---|
| RawTrackPoint | Reconstrucción, reprocessing, diagnóstico | Room/app-private | No | Mientras exista referencia válida al Trip/Capture; se purga al quedar huérfano tras eliminación definitiva |
| ProcessedTrackPoint | Mapas/métricas | Room/app-private | No | Vida del Trip; regenerable |
| Trip / TripPart / TripStatistics | Historial y presentación | Room/app-private | No | Hasta eliminación del usuario |
| CaptureEvent | Fiabilidad/detector | Room/app-private | No | Asociado al Capture/Trip; mismo ciclo de vida salvo política de diagnóstico más corta |
| Activity Recognition IDLE | Activar detector | Memoria/transitorio | No | No se conserva como historial continuo |
| Activity Recognition candidato/Trip | Explicabilidad del detector | Local, mínimo necesario | No | Ligado a Capture/diagnóstico; no crear diario de actividad permanente |
| ManualPause / Stop | Semántica del Trip | Room/app-private | No | Vida del Trip |
| Notas/tags/favoritos | Organización personal | Room/app-private | No | Hasta eliminación del usuario |
| Motorcycle | Agrupación futura | Room/app-private | No | Hasta eliminación del usuario |
| PrivacyZone | Ocultar zonas en sharing | Local privado | No | Hasta eliminación del usuario |
| Settings | UX/capacidades | DataStore/app-private | Solo backup allowlist si procede | Hasta reset/uninstall |
| Logs de producción | Diagnóstico básico | App-private, buffer acotado | No | Máximo corto definido en F0.13; sin coordenadas raw por defecto |
| Dataset diagnóstico de desarrollo | Field tests | App-private/export explícito | Solo acción explícita | Hasta borrado/export manual en builds de desarrollo |
| Export GPX/archivo | Interoperabilidad | Destino elegido por usuario | Sí, por acción explícita | Control posterior corresponde al usuario/destino |
| Share card | Compartir resumen | Cache + destino elegido | Sí, por acción explícita | Cache temporal; copia externa fuera de control de la app |

### 4.1 Trash

Baseline Core/P1:

- `Delete` mueve el Trip a Trash cuando la implementación de Trash esté disponible;
- retención objetivo: **30 días**;
- el usuario puede restaurar o eliminar definitivamente antes;
- al expirar Trash, datos raw sin referencias activas/superseded necesarias se vuelven elegibles para purga;
- `Delete all app data` ignora Trash y solicita borrado local definitivo tras confirmación fuerte.

La implementación exacta de GC/reference counting se define con Room en Fase 1.

### 4.2 Merge/Split y privacidad

Merge/Split no debe duplicar raw location.

Los `Trip` superseded pueden conservar provenance lógica, pero un `TripCapture`/RawTrack solo permanece si continúa referenciado por una composición no eliminada o por una ventana de recuperación válida.

No se permite conservar indefinidamente una copia oculta de una ruta que el usuario ya eliminó de todas las composiciones visibles.

---

## 5. Matriz final de permisos y capacidades

| Permiso / capacidad | Tipo | Uso V2 | Core | Si se deniega |
|---|---|---|---|---|
| `ACCESS_COARSE_LOCATION` | Runtime | Base de flujo de ubicación Android | Sí | Sin ubicación utilizable |
| `ACCESS_FINE_LOCATION` | Runtime | Ruta precisa, distancia, velocidad, altitud y tracking confiable | Sí para tracking de calidad | `LOCATION_DEGRADED`; no presentar métricas como precisas |
| `ACTIVITY_RECOGNITION` | Runtime API 29+ | Señal pasiva de posible viaje | Sí para Auto Tracking | Manual; Assisted solo si existe alternativa aprobada |
| `ACCESS_BACKGROUND_LOCATION` | Runtime especial | Validar/iniciar Auto Tracking cuando app no está visible | Solo Full Auto | Assisted/Manual; la app continúa utilizable |
| `POST_NOTIFICATIONS` | Runtime API 33+ | Transparencia + controles Pause/Resume/Finish | Requerido por política de producto para Full Auto | Full Auto no se habilita; manual puede continuar con advertencia según plataforma |
| `FOREGROUND_SERVICE` | Manifest normal | Trip activo perceptible | Sí | Build inválido para diseño Core |
| `FOREGROUND_SERVICE_LOCATION` | Manifest normal API 34+ | FGS de tipo location | Sí | No puede operar tracking Core en targets modernos |
| `RECEIVE_BOOT_COMPLETED` | Manifest normal | Re-registrar detector/capabilities tras reboot | Sí Auto Tracking | Detección puede no rearmarse hasta abrir app |
| `INTERNET` | Manifest normal | Tiles/maps/servicios futuros | Sí si map provider online | Tracking local sigue funcionando |
| `ACCESS_NETWORK_STATE` | Manifest normal | UX online/offline opcional | Opcional | Sin optimización por estado de red |

### 5.1 Permisos explícitamente NO previstos en Core

No se solicitarán sin un nuevo requisito y revisión:

- `MANAGE_EXTERNAL_STORAGE`;
- `READ_MEDIA_IMAGES` / acceso amplio a galería;
- `READ_CONTACTS`;
- `READ_PHONE_STATE`;
- `RECORD_AUDIO`;
- `CAMERA` (salvo futura captura directa; Photo Picker no lo necesita);
- `BLUETOOTH_*`;
- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`;
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` como requisito normal;
- `QUERY_ALL_PACKAGES`;
- Advertising ID / identificadores persistentes de dispositivo.

---

## 6. Política de ubicación precisa

F0.5 concluyó que el producto necesita Fine Location para entregar una ruta y métricas creíbles.

### 6.1 Qué habilita

- geometría de TrackPoints;
- distancia;
- velocidad filtrada;
- inicio/final de Track;
- stops;
- altitud cuando sea utilizable;
- mapas del viaje.

### 6.2 Si el usuario concede solo approximate

La aplicación entra en:

`LOCATION_DEGRADED`

Reglas:

- puede conservar UI, historial y funciones no dependientes de ubicación;
- no afirma que una ruta/distancia/velocidad degradada sea precisa;
- puede permitir un registro degradado si la implementación demuestra que aporta valor, pero debe etiquetarlo claramente;
- Full Auto de producción no se considera READY con approximate-only.

### 6.3 Copy educativo baseline

Antes de pedir ubicación precisa:

> **Ubicación precisa para registrar tu ruta**  
> Moto Trip Tracker usa ubicación precisa durante el viaje para dibujar el recorrido y calcular distancia, velocidad y elevación. Con ubicación aproximada, la ruta y las métricas pueden ser imprecisas.

Debe existir `Ahora no`/cancelación cuando el contexto lo permita.

---

## 7. Background Location y Full Auto

### 7.1 Única función declarable

Para Google Play, V2 debe mantener una explicación única y estable de background location:

> **Detección e inicio automático de un viaje y registro de su ruta cuando Moto Trip Tracker no está visible.**

No se justificará background location con analytics, publicidad, recomendaciones o funciones secundarias.

### 7.2 Solicitud incremental

Orden baseline:

1. Usuario conoce/usa el producto.
2. Se obtiene foreground location en contexto.
3. Se configura Auto Tracking.
4. Se obtiene Activity Recognition.
5. Se confirma capacidad de notificación para Full Auto.
6. Se muestra disclosure prominente de Background Location.
7. Solo después se dirige/solicita `ACCESS_BACKGROUND_LOCATION` según versión Android.

En Android 11+ foreground y background location **no se piden simultáneamente**. El background grant se realiza separadamente y normalmente requiere Settings.

### 7.3 Disclosure prominente baseline

Texto de producto propuesto para revisión final antes de release:

> **Ubicación en segundo plano para Auto Tracking**  
> Moto Trip Tracker recoge datos de **ubicación** para detectar, iniciar y registrar automáticamente tus viajes aunque la aplicación esté cerrada o no se esté usando. Los datos de tus recorridos se guardan localmente en tu dispositivo y no se utilizan para publicidad. Puedes seguir usando el registro manual sin conceder ubicación en segundo plano.

Acciones:

- `Continuar`;
- `Ahora no`.

Reglas:

- aparece inmediatamente antes del flujo de permiso correspondiente;
- no se oculta solo en Settings/Términos;
- incluye explícitamente “ubicación” y “en segundo plano / aunque la aplicación esté cerrada o no se esté usando”;
- la UI no imita el diálogo del sistema;
- denegar mantiene Manual/Assisted cuando sea técnicamente posible.

### 7.4 Publicación Google Play

Si V2 se publica con `ACCESS_BACKGROUND_LOCATION`, se prepara como release gate:

- Permissions Declaration Form;
- una sola función core declarada;
- video corto mostrando feature, disclosure y permission flow;
- privacy policy accesible desde app y Store;
- Store listing donde Auto Tracking sea una función claramente visible;
- revisión de todos los SDKs para confirmar que ninguno accede a location fuera del contrato.

---

## 8. Activity Recognition

### 8.1 Propósito

`ACTIVITY_RECOGNITION` existe únicamente para reducir consumo y activar el detector ante movimiento compatible con un viaje.

No se utilizará para construir un historial general de caminar/correr/estar quieto.

### 8.2 Retención

- IDLE transitions: procesamiento efímero por defecto;
- candidatos y Trip activo: conservar únicamente lo necesario para explicar la decisión del detector;
- developer/field builds pueden capturar mayor detalle de forma explícita y separada;
- F0.13 fijará el ring buffer y formato diagnóstico final.

### 8.3 Copy baseline

> **Reconocer cuándo te desplazas**  
> Moto Trip Tracker usa el reconocimiento de actividad para detectar un posible viaje sin mantener el GPS preciso activo todo el tiempo. Si no lo permites, puedes seguir iniciando viajes manualmente.

---

## 9. Notificaciones y transparencia

Android 13+ permite al usuario negar `POST_NOTIFICATIONS`, aunque un FGS puede iniciarse si cumple sus otros requisitos.

V2 adopta una regla de producto más estricta para automatización:

> **Full Auto no se declara READY sin capacidad de mostrar la notificación normal del Trip activo.**

Motivos:

- el usuario debe saber que existe tracking activo;
- Pause/Resume/Finish deben estar disponibles rápidamente;
- reduce sensación de tracking oculto;
- alinea privacidad con UX F0.9.

Si notificaciones están denegadas:

- el usuario puede acceder a la app y a su historial;
- Manual Tracking podrá mantenerse cuando plataforma/UX lo permitan, mostrando advertencia clara;
- Auto Tracking se degrada y la pantalla de capacidades explica cómo restaurarlo.

Copy baseline:

> **Controles del viaje en notificaciones**  
> Durante un viaje mostramos una notificación para indicar que el registro está activo y ofrecer Pausar, Reanudar o Finalizar.

---

## 10. Permisos de almacenamiento, fotos y exportación

### 10.1 Sin broad storage permission

Core/P1 no usará `MANAGE_EXTERNAL_STORAGE` ni permisos amplios de archivos.

### 10.2 Exportar archivos

Para GPX/backup/documentos:

- usar Storage Access Framework (`ACTION_CREATE_DOCUMENT` o equivalente moderno);
- el usuario escoge destino;
- no se requiere permiso broad storage;
- el archivo exportado puede contener ubicación sensible y la UI debe indicarlo antes de exportar.

### 10.3 Compartir

Para Sharesheet:

- generar archivo/imagen temporal en storage privado/cache;
- exponer mediante `FileProvider`/URI scoped y permiso temporal;
- nunca usar archivos world-readable;
- limpiar cache temporal de acuerdo con política de tamaño/edad;
- preview/resumen antes de compartir cuando contenga ruta.

### 10.4 Fotos futuras

Si Trips admiten fotografías:

- usar Android Photo Picker;
- no solicitar acceso completo a la galería;
- no solicitar Camera salvo que exista una función explícita de captura dentro de la app.

---

## 11. Privacy Zones

Privacy Zones son P1, pero su contrato se fija ahora.

### 11.1 Propósito

Permiten ocultar zonas sensibles al crear artefactos que salen de la app:

- GPX compartido;
- share card/mapa público;
- export parcial.

### 11.2 Reglas

1. No modifican ni recortan Raw Track local.
2. No se infiere automáticamente “casa” o “trabajo” sin acción del usuario.
3. La zona se configura explícitamente por el usuario.
4. Un preview debe mostrar qué parte se ocultará.
5. La protección se aplica antes de generar el artefacto compartible.
6. El usuario puede elegir compartir la ruta completa mediante una acción explícita separada.
7. Privacy Zone data permanece local en Core.

Radio/default exacto se decide en P1 UX/ADR.

---

## 12. Android Auto Backup y restauración

### 12.1 Riesgo

Android Auto Backup incluye por defecto gran parte de app-private storage, incluidas bases SQLite/Room, con un límite de cloud backup de 25 MB. Una base de TrackPoints puede superar rápidamente esa cuota y además contiene ubicación sensible.

### 12.2 Decisión Core

> **La base principal de Trips/RawTrack y datasets diagnósticos quedan fuera del Android cloud Auto Backup y de restauraciones automáticas no diseñadas explícitamente.**

Baseline de manifest/configuración:

- `android:allowBackup` se define explícitamente;
- `dataExtractionRules` (Android 12+) y reglas legacy usan **allowlist**, no “backup de todo menos algunas cosas”;
- solo settings no sensibles y expresamente aprobados pueden participar;
- database de Trips, raw track, diagnostic datasets, export caches y privacy zones se excluyen del cloud backup;
- cualquier Device-to-Device restore de la DB queda deshabilitado inicialmente hasta validar migraciones, active-capture semantics y privacidad.

### 12.3 Backup propio futuro

P1 podrá crear un backup de usuario mediante SAF.

Debe ser:

- iniciado explícitamente;
- versionado (`backupSchemaVersion`);
- validado antes de restore;
- claramente identificado como archivo con datos sensibles de ubicación;
- sin upload automático a servidor de Moto Trip Tracker;
- con estrategia de protección/encryption definida antes de release de esa función.

GPX no se considera backup completo: es formato de interoperabilidad.

---

## 13. Storage local y seguridad

### 13.1 Core baseline

- Room/DB en internal app-private storage;
- archivos raw/temporales en directorios privados;
- no escribir rutas en public shared storage salvo export explícito;
- usar sandbox Android y cifrado del dispositivo como baseline;
- no registrar coordenadas raw en Logcat de release;
- no incluir ubicación en nombres de archivo si no es necesario.

### 13.2 Cifrado adicional

F0.11 no introduce SQLCipher/custom DB encryption automáticamente.

Razón: añade key management, recovery y migraciones que deben justificarse por threat model. Si el producto añade cloud sync, multi-user, app lock o protección frente a dispositivo comprometido, se abre un ADR de cifrado de aplicación.

Esto no impide cifrar un futuro archivo de backup exportado.

---

## 14. Telemetría, analytics, crash reporting y terceros

### 14.1 Core V2

- sin advertising SDK;
- sin analytics SDK externo;
- sin crash SDK que reciba raw route/location;
- observabilidad Core local-first, desarrollada en F0.13.

### 14.2 Regla para nuevas dependencias

Todo SDK con acceso a:

- location;
- network;
- device identifiers;
- files;
- analytics/crash telemetry;

debe revisarse antes de incorporación.

La revisión debe responder:

1. ¿Qué datos accede?
2. ¿Qué datos transmite off-device?
3. ¿A quién?
4. ¿Para qué propósito?
5. ¿Puede deshabilitarse esa telemetría?
6. ¿Cambia Data Safety/privacy policy?
7. ¿Puede recibir coordenadas accidentalmente mediante breadcrumbs/logs?

### 14.3 Map provider

La selección futura de proveedor de mapas debe incluir privacy review.

V2 debe evitar enviar el Track completo al proveedor cuando solo necesita tiles/rendering. Geocoding/reverse geocoding que envíe coordenadas se considera una transmisión distinta y requiere documentación/decisión explícita.

---

## 15. Data Safety y transmisión off-device

Google Play define “collect” para Data Safety como datos transmitidos fuera del dispositivo; el acceso exclusivamente on-device que nunca se transmite no se declara como collection por ese solo hecho.

Pero:

- los SDKs cuentan como comportamiento de la app;
- transferir datos a otra app puede entrar en reglas de sharing;
- map providers, crash SDKs, geocoding y futuras APIs pueden cambiar las respuestas del formulario;
- el formulario Data Safety debe auditarse contra el APK/AAB real antes de cada release relevante.

Por tanto F0.11 **no congela hoy las respuestas finales de Data Safety**. Congela una arquitectura que intenta que Core no transmita el historial sensible por defecto.

---

## 16. Política de privacidad pública

Antes de distribución pública con funciones sensibles se requiere una Privacy Policy real, accesible:

- dentro de la app;
- desde Google Play;
- mediante URL pública activa, no un PDF como única publicación.

Debe cubrir como mínimo:

- datos de ubicación y por qué se usan;
- background location;
- Activity Recognition;
- storage local;
- retención/borrado;
- export/sharing;
- third-party SDK/map provider efectivos del build;
- contacto del desarrollador/entidad;
- cambios de política.

El texto jurídico final se prepara cerca del release y debe reflejar **el comportamiento real del build**, no solo F0.11.

---

## 17. Precise Location y política Google Play 2026–2027

Google Play anunció en 2026 un enfoque de minimum scope para foreground precise location.

Baseline V2 para justificar `ACCESS_FINE_LOCATION`:

- el objetivo principal es registrar una trayectoria continua;
- approximate location no produce geometría suficientemente fiable para distancia/ruta/velocidad;
- el Location Button está orientado a necesidades puntuales/transaccionales y no sustituye una sesión continua de tracking de una ruta;
- V2 debe tener lista una explicación técnica y evidencia F0.5/F0.6 antes de publicación.

Hitos de política a vigilar:

- noviembre 2026: declaración de ubicación precisa disponible en Play Console;
- 27 enero 2027: enforcement anunciado para apps dentro de alcance.

Esto es un release gate dinámico: se debe volver a comprobar la política vigente al publicar.

---

## 18. Modelo final de capacidades

### FULL AUTO READY

Requiere baseline de producto:

```text
Fine Location
+ Activity Recognition
+ Background Location
+ Notifications habilitadas
+ Location services activos
+ FGS manifest/config correcta
```

Resultado:

- detector pasivo;
- candidate validation;
- auto-start/auto-stop;
- Trip activo perceptible/controlable.

### ASSISTED AUTO

Background Location no concedido, o Full Auto no cumple transparencia/capacidad.

- Activity Recognition puede sugerir posible viaje;
- usuario confirma/inicia desde interacción visible;
- tracking activo usa foreground service.

### MANUAL

- usuario inicia explícitamente;
- no requiere Activity Recognition;
- requiere location apropiada para ruta;
- denegar background location no afecta este modo.

### LOCATION DEGRADED

- approximate-only, location services off o precisión insuficiente;
- conservar historial/controles posibles;
- no afirmar precisión falsa.

---

## 19. Flujo de permisos baseline

### 19.1 Primer arranque

No pedir permisos automáticamente.

```text
Welcome
  ↓
Home usable
```

### 19.2 Primer Manual Start

```text
Start Trip
  ↓
Explicar Fine Location
  ↓
Request foreground location
  ↓
Precise granted?
 ├─ sí → notificación (si aplica) → Start
 └─ no → Degraded / resolver / cancelar
```

### 19.3 Activar Auto Tracking

```text
Settings → Auto Tracking
  ↓
Explicar Activity Recognition
  ↓
Request ACTIVITY_RECOGNITION
  ↓
Verificar Fine Location
  ↓
Verificar POST_NOTIFICATIONS (API 33+)
  ↓
Disclosure prominente Background Location
  ↓
Request / Settings ACCESS_BACKGROUND_LOCATION
  ↓
Capability Resolver
  ↓
FULL AUTO / ASSISTED / MANUAL / DEGRADED
```

### 19.4 Revocación posterior

La app nunca asume que un permiso sigue vigente.

Al abrir app, iniciar tracking, rehidratar service o recibir error:

- recalcular capability mode;
- no spamear diálogos;
- mostrar una sola acción clara para resolver;
- respetar `Ahora no`/denegación.

---

## 20. Eliminación y control del usuario

El usuario debe poder:

- eliminar un Trip;
- vaciar Trash;
- eliminar privacy zones;
- eliminar datasets diagnósticos/export cache;
- ejecutar `Delete all local data` desde Settings con confirmación fuerte;
- desactivar Auto Tracking sin perder historial;
- revocar permisos desde Android sin corromper datos existentes.

`Delete all local data` debe eliminar Room, archivos internos de usuario y caches correspondientes. No puede borrar copias GPX/backup que el usuario ya guardó externamente o compartió con otra app.

---

## 21. Reglas de logs y diagnóstico

F0.13 detallará observabilidad, pero F0.11 fija restricciones:

### Release

No loggear por defecto:

- lat/lon;
- dirección textual;
- privacy zone coordinates;
- GPX contents;
- notas del usuario;
- route polyline;
- identificadores persistentes de dispositivo.

Puede loggear:

- state transitions;
- códigos de error;
- versiones;
- conteos;
- accuracy buckets sin posición cuando sea suficiente.

### Developer / Field Test

Puede existir un modo explícito que capture datos completos necesarios para F0.6.

Debe:

- estar marcado como diagnóstico;
- no activarse accidentalmente en release;
- permitir borrar/exportar;
- no transmitir automáticamente.

---

## 22. Invariantes de privacidad

### PRIV-INV-001 — Local-first
El historial Core no requiere backend.

### PRIV-INV-002 — No hidden tracking
No existe ubicación precisa permanente en IDLE y no existe diario continuo de Activity Recognition.

### PRIV-INV-003 — Background location solo Auto Tracking
No reutilizar para ads, analytics, weather, recomendaciones o funciones laterales.

### PRIV-INV-004 — Consentimiento progresivo
No bundlear Fine + Background + Activity + Notifications en una sola pantalla de permiso.

### PRIV-INV-005 — Denial-safe
Denegar un permiso opcional degrada capacidad; no bloquea historial/gestión local.

### PRIV-INV-006 — Sharing explícito
Ningún Trip se comparte/sube automáticamente.

### PRIV-INV-007 — No route in release logs
Ubicación raw no aparece en logs normales.

### PRIV-INV-008 — SDK review
Una dependencia nueva no puede cambiar silenciosamente la política de datos.

### PRIV-INV-009 — Backup controlado
La DB sensible no entra por defecto en cloud Auto Backup.

### PRIV-INV-010 — Delete means delete
Cuando una ruta deja de estar referenciada y termina su ventana de Trash/recovery, debe ser elegible para purga real.

---

## 23. Decisiones aceptadas

| ID | Decisión | Estado |
|---|---|---|
| PRIV-001 | Core V2 es local-first y sin cuenta obligatoria. | ACEPTADO |
| PRIV-002 | Sin advertising SDK ni analytics SDK externo en Core. | ACEPTADO |
| PRIV-003 | `ACCESS_FINE_LOCATION` se justifica por tracking preciso; approximate = degraded. | ACEPTADO |
| PRIV-004 | `ACCESS_BACKGROUND_LOCATION` se reserva exclusivamente para Full Auto. | ACEPTADO |
| PRIV-005 | Background permission se solicita incrementalmente con disclosure prominente. | ACEPTADO |
| PRIV-006 | Full Auto requiere notificaciones habilitadas como política de producto. | ACEPTADO |
| PRIV-007 | `ACTIVITY_RECOGNITION` no crea un diario de actividad IDLE. | ACEPTADO |
| PRIV-008 | No broad storage permission; SAF para export y Photo Picker para media. | ACEPTADO |
| PRIV-009 | Raw DB/diagnostics se excluyen de Android cloud Auto Backup baseline. | ACEPTADO |
| PRIV-010 | Privacy Zones solo afectan artefactos compartidos, no Raw Track local. | ACEPTADO |
| PRIV-011 | Release logs no contienen coordenadas raw por defecto. | ACEPTADO |
| PRIV-012 | Nuevos SDKs requieren privacy/Data Safety review. | ACEPTADO |
| PRIV-013 | Trash target baseline 30 días. | ACEPTADO, reabrible por UX/legal |
| PRIV-014 | El formulario Data Safety final se audita contra el build real, no se congela en Fase 0. | ACEPTADO |

---

## 24. Requisitos derivados para F0.12 y F0.13

### F0.12 Testing

Debe probar como mínimo:

- first-run sin permisos;
- Fine granted / approximate only / denied;
- Activity Recognition denied/revoked;
- Background Location denied/revoked;
- Notifications denied/revoked;
- grant incremental Android 11+;
- capability mode correcto tras cada combinación;
- permiso revocado durante Trip;
- Auto Tracking no opera como FULL AUTO sin requisitos;
- export via SAF sin storage permissions;
- Photo Picker sin broad media permission;
- Delete/Trash/purge;
- backup rules excluyen DB sensible;
- no coordenadas en logs release;
- privacy zone modifica output compartido sin alterar raw.

### F0.13 Observability

Debe definir:

- redaction por build type;
- ring buffer local;
- dataset diagnóstico explícito;
- export diagnóstico;
- ausencia de transmisión automática;
- lista segura de campos para release.

---

## 25. Riesgos abiertos

| Riesgo | Tratamiento |
|---|---|
| Google Play rechaza Background Location | Mantener Assisted/Manual funcionales; Store/disclosure/video claros; revalidar política antes de release |
| Política precise location 2027 cambia | Release gate dinámico; evidencia F0.5/F0.6 y justificación técnica |
| Map SDK transmite datos no previstos | SDK review antes de selección/upgrade |
| Usuario espera cloud backup automático | Explicar local-first; implementar backup propio P1 antes de prometer migración |
| Trash conserva ubicación más de lo esperado | UI muestra retención; purga real tras 30 días salvo referencias válidas |
| Diagnostic mode filtra datos | Separación build/feature explícita; no upload automático; F0.13 |
| Shared GPX revela casa/trabajo | Privacy Zones + preview P1; warning para export full route |

---

## 26. Criterio de cierre F0.11

- [x] Inventario de datos y sensibilidad definido.
- [x] Propósito y retención por categoría definidos.
- [x] Matriz final de permisos Core definida.
- [x] Flujo incremental de Fine/Activity/Background/Notifications definido.
- [x] Disclosure prominente baseline redactado.
- [x] Política de degradación por permisos definida.
- [x] Sharing/export sin broad storage definido.
- [x] Privacy Zones contract definido.
- [x] Android Auto Backup baseline definido.
- [x] Regla de terceros/SDKs y Data Safety definida.
- [x] Política de logs/diagnóstico definida.
- [x] Eliminación/Trash/purge definidos a nivel conceptual.
- [x] Requisitos derivados para F0.12/F0.13 documentados.

**Decisión:** F0.11 queda CERRADO como baseline de privacidad y permisos v0.1.

Siguiente workstream: **F0.12 — Estrategia de Testing**.

---

## 27. Fuentes oficiales consultadas

Consulta: 2026-09-15.

1. Android Developers — Access location in the background  
   https://developer.android.com/develop/sensors-and-location/location/background
2. Android Developers — Request background location  
   https://developer.android.com/develop/sensors-and-location/location/permissions/background
3. Android Developers — Android 11 location updates  
   https://developer.android.com/about/versions/11/privacy/location
4. Android Developers — Foreground service types  
   https://developer.android.com/develop/background-work/services/fgs/service-types
5. Android Developers — Restrictions on starting a foreground service from background  
   https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
6. Android Developers — Notification runtime permission  
   https://developer.android.com/develop/ui/compose/notifications/notification-permission
7. Google for Developers — ActivityRecognitionClient  
   https://developers.google.com/android/reference/com/google/android/gms/location/ActivityRecognitionClient
8. Android Developers — Minimize permission requests  
   https://developer.android.com/privacy-and-security/minimize-permission-requests
9. Android Developers — Photo Picker  
   https://developer.android.com/training/data-storage/shared/photo-picker
10. Android Developers — Auto Backup  
    https://developer.android.com/identity/data/autobackup
11. Android Developers — Security recommendations for backups  
    https://developer.android.com/privacy-and-security/risks/backup-best-practices
12. Google Play Console Help — Understanding location in the background permissions  
    https://support.google.com/googleplay/android-developer/answer/9799150
13. Google Play Console Help — Best practices for prominent disclosure and consent  
    https://support.google.com/googleplay/android-developer/answer/11150561
14. Google Play Console Help — Data Safety  
    https://support.google.com/googleplay/android-developer/answer/10787469
15. Google Play Console Help — Minimum scope: Foreground location access and Location Button  
    https://support.google.com/googleplay/android-developer/answer/17033915
