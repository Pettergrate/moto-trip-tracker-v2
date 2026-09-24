---
name: close-task
description: Close a Moto Trip Tracker V2 task: run tests, update the backlog entry and commit with the TASK-ID format.
disable-model-invocation: true
argument-hint: "<TASK-ID> [short commit description]"
---

Tarea a cerrar: $ARGUMENTS

1. **Estado.** `git status` y `git diff --stat`. Si hay cambios que claramente no pertenecen a esta tarea, avisa y no los incluyas.
2. **Verificacion.** Corre `./gradlew testDebugUnitTest`. Si falla, detenete y reporta; no cierres la tarea con tests rojos.
3. **Backlog.** En `docs/05-roadmap/phase1-backlog.md` busca la entrada del ID. Agrega o actualiza su linea `**Status: Done (<fecha de hoy>).**` con una descripcion breve de lo entregado y, como en las tareas anteriores, cualquier desviacion respecto de lo documentado. Si el ID no existe en el backlog, decilo y pregunta antes de inventarlo.
4. **Commit.** Agrega los archivos por nombre (nada de `git add -A`) y commitea con el formato `TASK-ID: descripcion corta en ingles` (ejemplos: `EDT-001: merge trips into one logical trip`, `PERM-001: guard against starting a capture with no location permission`). Un commit por tarea.
5. **No hagas push.** Confirma con `git status` y `git log -1`.
