---
name: new-room-migration
description: Add a Room schema change end to end (version bump, Migration, exported schema, migration test) for Moto Trip Tracker V2.
disable-model-invocation: true
argument-hint: "<what changes in the schema>"
---

Cambio de esquema pedido: $ARGUMENTS

Sigue estos pasos en orden. No saltees ninguno.

1. **Contexto.** Lee `docs/adr/ADR-003-room-source-of-truth.md`, `ADR-006` y `ADR-016`, y `core/database/Migrations.kt` (el `MIGRATION_1_2` es el modelo a seguir: aditivo, columnas nullable, sin backfill inventado).
2. **Entidad/DAO.** Modifica la entidad en `core/database/entity/` y, si hace falta, el DAO en `core/database/dao/`.
3. **Version.** Sube `version` en `MotoTripDatabase.kt` (hoy es 2) y registra la migracion nueva donde se construye la base (`core/di/DatabaseModule.kt`).
4. **Migracion.** Agrega `MIGRATION_<n>_<n+1>` en `Migrations.kt`. Preferi cambios aditivos; un valor desconocido queda `NULL`, nunca `0` inventado (ADR-016). Si el cambio es destructivo o requiere backfill, detenete y consulta antes.
5. **Esquema exportado.** NO edites `app/schemas/`. Compila (`./gradlew assembleDebug`) para que KSP genere el JSON nuevo y verifica con `git status` que aparecio `app/schemas/.../<n+1>.json`.
6. **Test.** Extiende `app/src/androidTest/.../MotoTripDatabaseMigrationTest.kt` para cubrir la migracion nueva con datos preexistentes. Ese test necesita dispositivo/emulador: si no hay uno, dilo explicitamente en vez de darlo por verificado.
7. **Tests JVM.** Corre `./gradlew testDebugUnitTest`.
8. **Resumen.** Lista archivos tocados, la version nueva y si el test de migracion se ejecuto o quedo pendiente. No hagas commit; eso lo hace `/close-task`.
