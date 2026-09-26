# Moto Trip Tracker V2

Android nativo (Kotlin + Jetpack Compose), un solo módulo `:app`, paquete `com.mototriptracker.app`.
Stack: Hilt + KSP, Room (esquemas exportados en `app/schemas`), WorkManager, DataStore, Navigation 3, MapLibre.

## Antes de tocar código
- Las decisiones de arquitectura viven en `docs/adr/` (ADR-001..021) y `docs/03-architecture/`. Leer el ADR relevante antes de cambiar dominio, base de datos o tracking.
- El trabajo va por tareas con ID (`FND-003`, `MET-001`, `PERM-001`...). Backlog en `docs/05-roadmap/phase1-backlog.md`, plantilla en `docs/05-roadmap/agent-task-template.md`.
- Sin cambios silenciosos de arquitectura ni upgrades de dependencias no relacionados con la tarea. Las versiones de `gradle/libs.versions.toml` están fijadas con justificación en comentarios: respetarla.

## Invariantes que no se rompen
- Raw track se preserva; lo procesado es reproducible (ADR-006).
- Un único capture activo a la vez (ADR-020).
- Comandos idempotentes y transaccionales (ADR-015).
- Huecos explícitos, nunca datos fabricados: un valor desconocido queda `null`, no `0` (ADR-016).
- `domain/` no depende de Android (ADR-013); lo verifica `DomainBoundaryTest`.
- Nada de red/backend/analytics salvo que la tarea lo pida (ADR-009, ADR-017). Ninguna coordenada en logs ni diagnósticos.

## Capas (`app/src/main/java/com/mototriptracker/app/`)
`core` (database, di, model, datastore, common), `data`, `domain` (detection, processing, capability), `tracking`, `worker`, `feature`, `navigation`, `experiment`.

## Comandos
- Compilar: `./gradlew assembleDebug`
- Tests unitarios (JVM, Robolectric): `./gradlew testDebugUnitTest`
- Una clase: `./gradlew testDebugUnitTest --tests "*CandidateStartEngineTest"`
- Migraciones Room: `MotoTripDatabaseMigrationTest` está en `androidTest` y necesita dispositivo/emulador.

## Room
Cambiar el esquema implica subir `version` en `MotoTripDatabase`, agregar la `Migration` en `Migrations.kt` y extender el test de migración. Los JSON de `app/schemas/` los genera KSP al compilar: no se editan a mano (un hook lo bloquea). Skill: `/new-room-migration`.

## Fallos de escritura en el teléfono (solo debug)
El teléfono de pruebas guarda viajes reales: no llenar el almacenamiento, no tocar el WAL, no correr `connectedAndroidTest`. Para probar la ruta de fallo de persistencia (REC-006) hay un inyector que solo existe en builds `debug` (`src/debug`, activado por archivos bandera en `files/` de la app; no toca datos ni ajustes del sistema). Entrecomillar toda la orden para que la redirección la haga `run-as` y no el shell externo:
- `adb shell "run-as com.mototriptracker.app sh -c 'echo transient > files/fail_raw_writes'"` (o `full` para `SQLiteFullException`)
- `adb shell "run-as com.mototriptracker.app rm files/fail_raw_writes"` para volver a la normalidad
- `adb shell "run-as com.mototriptracker.app sh -c 'echo 10 > files/raw_buffer_capacity'"` reduce el buffer (se lee al iniciar el viaje); borrar el archivo al terminar.
`sqlite3.exe` está en `platform-tools`: copiar `databases/moto-trip-tracker.db` (+ `-wal`/`-shm`) con `adb exec-out run-as ... cat` y consultar la copia. Borrar siempre las copias, contienen coordenadas reales.

## Commits
Formato `TASK-ID: descripción corta en inglés` (ej. `EDT-001: merge trips into one logical trip`). Un commit por tarea. Skill: `/close-task`.

## Codex
Para una segunda opinión usar el CLI directo: `codex review --base main` o `codex exec "<prompt>"`. Los comandos `/codex:*` del plugin se cuelgan en esta máquina (Job Object de Windows impide el daemon).
