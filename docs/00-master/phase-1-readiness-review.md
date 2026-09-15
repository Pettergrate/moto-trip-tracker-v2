# F0.17 — Phase 1 Readiness Review
## Moto Trip Tracker V2

**Estado:** CERRADO — revisión de documentación completa
**Versión:** 1.0
**Fecha:** 2026-09-15
**Depende de:** F0.1–F0.15 (F0.16 eliminado por decisión del propietario, 2026-09-15)
**Autoriza:** Únicamente esta revisión y las correcciones editoriales descritas abajo. **No autoriza implementación.**

---

## 1. Resumen ejecutivo

Se revisó el contenido, las referencias y la coherencia entre todos los documentos de Fase 0 de Moto Trip Tracker V2 — no solo su estado declarado "cerrado" — para determinar si V2 está lista para comenzar la Fase 1 (Wave W0).

**Veredicto: GO, con alcance explícito limitado a Wave W0** (`FND-001` a `EXP-001`, según `docs/05-roadmap/phase1-backlog.md`).

No se encontró ninguna decisión de producto, arquitectura o requisito faltante que bloquee el inicio de W0. Se encontraron y corrigieron dos problemas de referencias/documentación (una referencia rota y una tabla de IDs de ADR obsoleta) y se identificaron tres hallazgos adicionales de menor severidad, ninguno bloqueante. Los parámetros que legítimamente dependen de evidencia de campo (umbrales del detector, perfil de muestreo GPS de producción, proveedor de mapas, algoritmo de elevación) permanecen correctamente diferidos con un gate explícito (`EXP-008` / Gate G4, `MAP-001`) y **no se congelan** por esta revisión.

Esta revisión cubre exclusivamente documentación. No se creó el proyecto Android, no se instalaron dependencias y no se inició ninguna tarea de implementación como resultado de este trabajo.

---

## 2. Documentos revisados y límites de la revisión

### 2.1 Documentos revisados en su totalidad

- `README.md`
- `docs/00-master/phase-0-master.md` (F0 índice maestro)
- `docs/01-product/requirements-scope.md` (F0.2)
- `docs/01-product/trip-detection-spec.md` (F0.3)
- `docs/02-research/android-platform-research.md` (F0.4)
- `docs/02-research/gps-location-research.md` (F0.5)
- `docs/02-research/field-experiment-design.md` (F0.6)
- `docs/02-research/field-tests/experiment-profile-template.md`
- `docs/02-research/field-tests/session-result-template.md`
- `docs/03-architecture/domain-data-model.md` (F0.7)
- `docs/03-architecture/system-architecture.md` (F0.8)
- `docs/03-architecture/ux-navigation.md` (F0.9)
- `docs/03-architecture/reliability-recovery.md` (F0.10)
- `docs/03-architecture/privacy-permissions.md` (F0.11)
- `docs/04-testing/testing-strategy.md` (F0.12)
- `docs/04-testing/observability-diagnostics.md` (F0.13)
- `docs/adr/README.md` (F0.14, índice) y los 20 ADRs (`ADR-001` a `ADR-020`)
- `docs/05-roadmap/v2-roadmap.md` (F0.15)
- `docs/05-roadmap/phase1-backlog.md`
- `docs/05-roadmap/agent-task-template.md`

Adicionalmente, se inspeccionó `Fase_0_Documento_Maestro_Moto_Trip_Tracker_V2.docx` (raíz del repositorio) únicamente para verificar si contenía el documento F0.1 aparentemente faltante (ver Hallazgo H1). No forma parte del árbol `docs/` ni de los documentos que esta tarea pidió revisar como fuente autoritativa.

### 2.2 Método

- Lectura completa de cada archivo (no solo encabezados o tablas de contenido).
- Verificación cruzada de referencias a archivos (`docs/.../*.md`) mencionadas en cualquier documento contra el listado real de archivos del repositorio.
- Verificación de consistencia entre: (a) el estado declarado por cada documento en su propio encabezado/cierre, (b) el estado que le atribuye el índice maestro, y (c) cómo lo usan documentos posteriores (p. ej. si el roadmap y los ADR citan los mismos IDs de requisito/ADR que los documentos fuente).
- Verificación de que los 20 ADR citados por `docs/05-roadmap/v2-roadmap.md` §6 (Epic map) y por `docs/05-roadmap/phase1-backlog.md` coinciden con el baseline de `docs/adr/README.md`.

### 2.3 Límites explícitos de esta revisión

- **No se verificó código** porque no existe código en el repositorio; la revisión es puramente documental.
- **No se ejecutó ni se exige** ninguna validación de campo (F0.6/EXP). Su ejecución está correctamente diferida al gate `EXP-008`/G4 dentro de Wave W0/W2, y esta revisión no la sustituye ni la da por aprobada.
- **No se verificaron externamente** las referencias técnicas citadas por F0.4/F0.5/F0.8 (versiones de AGP/Compose/Room/Navigation/Play services, fechas de política de Google Play sobre ubicación precisa). Se tratan como entradas de investigación ya citadas por esos documentos con sus propias fuentes; su revalidación antes de fijar `libs.versions.toml` ya está exigida por `docs/03-architecture/system-architecture.md` §3.2 y no se repite aquí.
- **No es una revisión multi-agente ni "ultra"**: es una auditoría documental de una sola pasada sobre el contenido íntegro de cada archivo listado.
- Esta revisión **no evalúa viabilidad de negocio, coste, ni cronograma** — el roadmap es explícitamente *dependency-driven, not calendar-driven* y esta revisión respeta esa decisión.

---

## 3. Matriz de preparación

| # | Criterio | Evidencia principal | Estado | Acción pendiente |
|---|---|---|---|---|
| 1 | Alcance Core, requisitos y criterios de aceptación | `requirements-scope.md` (FR-*/NFR-* numerados, P0/P1/P2, Core/Post-Core/Future §6-8, convención de trazabilidad §11); `phase-0-master.md` §2 | **Listo** | Ninguna bloqueante. Armonizar etiqueta de estado (ver H2). |
| 2 | Detección automática y controles manuales | `trip-detection-spec.md` (máquina de estados, DP-001..008, SCN-001..028, HYP-001..007, RQ-DET/START/STOP/PAUSE/BAT/LOC/ALT/PLAT); `ADR-007`, `ADR-020` | **Listo** | Ninguna bloqueante. Umbrales correctamente diferidos a `EXP-008`/G4 (ver §5). |
| 3 | Modelo de datos, invariantes, persistencia y recuperación | `domain-data-model.md` (F0.7, capas, taxonomías, reglas de integridad); `system-architecture.md` §7-13 (F0.8, persistencia física, concurrencia); `reliability-recovery.md` (F0.10, REL-INV-001..010, Recovery Matrix); `ADR-003/005/006/014/015/016/020` | **Listo** | Ninguna. |
| 4 | Arquitectura, permisos, privacidad, UX, diagnósticos | `system-architecture.md` (F0.8); `ux-navigation.md` (F0.9, criterios UX-01..18); `privacy-permissions.md` (F0.11, matriz de permisos, invariantes PRIV-INV-001..010); `observability-diagnostics.md` (F0.13); `ADR-002/004/008/009/011/012/013/017/019` | **Listo** | Referencia obsoleta corregida durante esta revisión (ver H3). |
| 5 | Estrategia de pruebas, experimentos de campo y gates | `testing-strategy.md` (F0.12: capas T0-T4, gates G0-G4, matriz de fault-injection); `field-experiment-design.md` (F0.6: Diagnostic Harness, campañas S1-S4, escenarios FT-*, Pilot→Validation); plantillas `experiment-profile-template.md`/`session-result-template.md`; `ADR-018` | **Listo** | Ejecutar `EXP-001..008` dentro de W0/W2 (ya planificado; no bloqueante para iniciar W0). |
| 6 | Dependencias y preparación de tareas W0 | `phase1-backlog.md` (`FND-001..004`, `TST-001`, `DIA-001`, `CAP-001`, `EXP-001` con constraints/ADR/acceptance por tarea); `v2-roadmap.md` §16 (Phase 1 start sequence), §8-9 (DoR/DoD) | **Listo** | Aplicar formalmente el checklist DoR (`v2-roadmap.md` §8) a cada tarea antes de asignarla a un agente de implementación (recomendado, no bloqueante — el contenido ya satisface los criterios). |
| 7 | Contradicciones, documentos faltantes, decisiones sin resolver | Ver §6 Hallazgos | **Con notas, ninguna bloqueante** | Ver §6. |

---

## 4. Verificación específica: `docs/01-product/product-definition.md`

`docs/00-master/phase-0-master.md` (antes de esta revisión, línea 172) citaba `docs/01-product/product-definition.md` como la fuente de F0.1. **Ese archivo no existe** en el repositorio; se confirmó mediante listado completo del árbol `docs/`.

Esto **no significa que el contenido de F0.1 esté perdido o inventado por esta revisión**. Se verificó que el contenido conceptual de F0.1 (visión, principios de producto, alcance Core/Post-Core/Future, diferenciador) está genuinamente presente, distribuido en:

1. `docs/00-master/phase-0-master.md` §2 "Product summary".
2. `docs/01-product/requirements-scope.md`, que declara explícitamente `Depends on: F0.1 Product Definition` y convierte esa visión en requisitos numerados.
3. `docs/adr/ADR-001-greenfield-v2.md`, que traza directamente a F0.1.
4. Una instantánea histórica condensada en `Fase_0_Documento_Maestro_Moto_Trip_Tracker_V2.docx` (raíz del repositorio), sección "4. F0.1 · Definición del producto" — un documento **legado**, no parte del árbol `docs/`, que además contradice el estado actual del proyecto en otro punto (ver H5).

**Conclusión:** no existe un documento F0.1 dedicado con la profundidad de F0.2/F0.3 (que tienen cientos de líneas de requisitos/especificación numerados). Esto es una **brecha de completitud documental**, no una decisión de producto faltante ni un bloqueo real para W0, porque ninguna tarea de `phase1-backlog.md` (W0) depende de un archivo F0.1 dedicado — todas citan F0.2/ADR directamente. La referencia rota se corrigió en esta revisión (ver §7, cambios realizados); no se creó el archivo faltante ni se asumió equivalencia total, conforme a las instrucciones de esta tarea.

Queda como **decisión abierta y no bloqueante para el propietario**: ¿se desea producir retroactivamente un `docs/01-product/product-definition.md` dedicado por completitud histórica, o se acepta formalmente que el contenido de F0.1 quede distribuido como está ahora documentado? Ninguna opción bloquea W0.

---

## 5. Decisiones diferidas y momento en que deben resolverse

Estas decisiones están **deliberadamente abiertas** en la documentación revisada. Ninguna se congela por esta revisión y ninguna bloquea el inicio de W0; cada una tiene un mecanismo/gate explícito de resolución ya documentado.

| Decisión diferida | Dónde se resuelve | Cuándo |
|---|---|---|
| Umbrales de Auto Start/Auto Stop, confidence/hysteresis | `EXP-002..007` → `EXP-008` (Freeze Detector/Location Profile v1) | Gate **G4**, al cierre de Wave W2 |
| Perfil de producción de sampling GPS / min-distance / batching | Campañas S1-S4 de F0.6 ejecutadas en `EXP-003` | Gate **G4**, junto con lo anterior |
| Proveedor de mapas | `MAP-001` (spike + ADR específico que supersede parcialmente `ADR-019`) | Wave **W1**, antes de cerrar `MAP-001` |
| Algoritmo de suavizado de elevación / ascent-descent (`FR-MET-010`) | Evidencia de `EXP-002..007`; sigue *research-gated* | Durante W1 (`PRC-003` baseline) y refinado en W2 |
| Retención final de `DiagnosticEvent` (baseline: 14 días / 20 000 eventos) | Medición real en Fase 1 | Durante W4 (`DIA-002/003`) tras uso real |
| Esquema/cifrado de backup propio de usuario | Post-Core Train B3 (`EXP-101..106`) | Después de Core (Post-Core) |
| minSdk final, triggers de extracción multimódulo, estrategia física de `RawTrackPoint` | Triggers de reapertura explícitos en `system-architecture.md` §22 | Cuando aparezca evidencia concreta durante implementación |
| Vigencia real de versiones de stack citadas (AGP 9.4.0, Compose BOM 2026.08.00, Room 2.8.5, Navigation 3 1.1.7, play-services-location 21.4.0) | Revalidación exigida por `system-architecture.md` §3.2 | Al fijar `libs.versions.toml` en **`FND-001`** |
| Política de Google Play sobre ubicación precisa (declaración nov-2026, enforcement 27-ene-2027) | `privacy-permissions.md` §17, gate dinámico de release | Antes de publicar (no antes de W0) |

Ninguna de estas decisiones diferidas requiere una respuesta del propietario para autorizar el inicio de W0; todas tienen dueño/mecanismo. La única pregunta abierta no bloqueante es la de `docs/01-product/product-definition.md` (§4 arriba).

---

## 6. Hallazgos

### H1 — Referencia rota a `docs/01-product/product-definition.md` (Prioridad: Moderada)

- **Fuente:** `docs/00-master/phase-0-master.md:172` (antes de esta revisión).
- **Efecto sobre el inicio de W0:** Ninguno. Ninguna tarea de `phase1-backlog.md` depende de este archivo.
- **Acción tomada en esta revisión:** referencia corregida; se documentó dónde vive realmente el contenido de F0.1 (ver §4).
- **Acción pendiente:** decisión opcional y no bloqueante del propietario sobre crear el archivo dedicado (ver §4).

### H2 — Inconsistencia de estado entre F0.2/F0.3 y el índice maestro (Prioridad: Baja-Moderada)

- **Fuente:** `docs/01-product/requirements-scope.md:4-5` ("**Status:** Draft baseline") y `docs/01-product/trip-detection-spec.md:4-5` y `:585` ("Draft v0.1: The detector is specified...") vs. `docs/00-master/phase-0-master.md` §4 (tabla: "Closed baseline" / "Approved baseline") y `README.md` ("F0.1 through F0.15 closed").
- **Efecto sobre el inicio de W0:** Ninguno de fondo. F0.4 en adelante, el roadmap y el backlog usan F0.2/F0.3 como base estable sin contradicción sustantiva; el contenido es completo y coherente. Es una discrepancia de metadatos/etiqueta de encabezado, no de contenido.
- **Acción pendiente:** tarea editorial menor (no realizada en esta revisión para no alterar el contenido decisional de documentos ya usados como base por trabajo posterior): añadir a `requirements-scope.md` y `trip-detection-spec.md` una declaración explícita de cierre análoga a la de F0.4-F0.15 ("Decisión: F0.X CERRADO..."), y actualizar sus encabezados de `Draft` a `Closed`/`Approved`. Es puramente editorial y no requiere decisión del propietario.

### H3 — Mapeo de IDs de ADR obsoleto en `system-architecture.md` §23 (Prioridad: Baja) — **Corregido en esta revisión**

- **Fuente:** `docs/03-architecture/system-architecture.md` §23 (antes de esta revisión) listaba un mapeo candidato `ADR-001..ADR-010` que no coincidía con la numeración final aceptada en F0.14. Ejemplo concreto: F0.8 §23 asignaba `ADR-002` a "Room es source of truth local", pero el baseline final (`docs/adr/README.md`) asigna `ADR-002` a "Android nativo con Kotlin y Jetpack Compose" y `ADR-003` a Room.
- **Efecto:** Bajo. El roadmap (`v2-roadmap.md` §6 Epic map) y el backlog (`phase1-backlog.md`) siempre citaron los IDs finales correctos, por lo que ninguna tarea de implementación estaba en riesgo de citar un ADR equivocado. El riesgo era que un lector de F0.8 en aislamiento se confundiera.
- **Acción tomada:** se corrigió la sección para remitir a `docs/adr/README.md` como fuente única de la numeración, sin repetir un mapeo que pueda volver a desalinearse.

### H4 — `phase-0-master.md` §8 incompleta y no secuencial (Prioridad: Baja)

- **Fuente:** `docs/00-master/phase-0-master.md` §8 "Current Phase 0 progress" no incluye subsecciones dedicadas para F0.3-F0.7 (aunque sí aparecen correctamente resumidos en la tabla del §4 y con todo detalle en sus propios documentos), y el orden narrativo salta F0.1 → F0.15 → F0.2 → F0.8 → F0.9... en vez de ser secuencial.
- **Efecto sobre el inicio de W0:** Ninguno. La tabla del §4 y los documentos fuente individuales son correctos y completos; esta sección es un resumen narrativo adicional, no la única fuente de trazabilidad.
- **Acción pendiente:** tarea editorial de bajo esfuerzo para completar/reordenar §8. No se realizó en esta revisión para mantener el alcance en "revisión y documentación" sin reescribir secciones sustanciales de un documento que otros ya citan por número de línea/sección.

### H5 — Documento `.docx` raíz desactualizado (Prioridad: Informativa)

- **Fuente:** `Fase_0_Documento_Maestro_Moto_Trip_Tracker_V2.docx` (raíz del repositorio) todavía lista F0.16 como pendiente y usa una casilla sin marcar para "Gate final aprobado: GO para Fase 1", contradiciendo la decisión vigente de eliminar F0.16 documentada en `phase-0-master.md` §6, `v2-roadmap.md` §15/16 y `ADR-001`.
- **Efecto sobre el inicio de W0:** Ninguno. Ningún documento vivo del árbol `docs/` lo cita como fuente de verdad.
- **Acción pendiente:** recomendar archivarlo o anotarlo explícitamente como "instantánea histórica, no autoritativa" para evitar que alguien lo comparta como el estado actual del proyecto.

### Hallazgos descartados explícitamente

- **Ausencia de F0.16:** no se trató como hallazgo ni como bloqueo, conforme a la instrucción explícita de esta tarea y a la decisión del propietario del 2026-09-15 documentada en `phase-0-master.md` §6 y `ADR-001`.
- **Ausencia de resultados de campo (F0.6/EXP):** no se trató como hallazgo. El propio F0.6 y el roadmap difieren su ejecución a W0/W2 con gate `EXP-008`/G4; exigir resultados ahora habría sido incorrecto según las instrucciones de esta tarea.
- **Umbrales de detector/ubicación no fijados:** no se trató como hallazgo ni se congelaron; están correctamente diferidos.

---

## 7. Cambios realizados durante esta revisión

| Archivo | Cambio | Naturaleza |
|---|---|---|
| `docs/00-master/phase-0-master.md` | Corrección de referencia rota a F0.1 (§8); actualización de versión (0.15→0.17) y estado; fila de F0.17 en la tabla §4 marcada como cerrada con veredicto; nueva subsección "F0.17 — Phase 1 Readiness Review" | Editorial / actualización de estado de revisión |
| `docs/03-architecture/system-architecture.md` | Corrección del mapeo obsoleto de IDs de ADR en §23 | Editorial (referencia cruzada) |
| `README.md` | Actualización de baseline/versión, veredicto F0.17 y enlace a este documento | Editorial / actualización de estado de revisión |
| `docs/00-master/phase-1-readiness-review.md` | Documento nuevo (este archivo) | Entregable de F0.17 |

**Ningún requisito, ADR, umbral, parámetro de detector/ubicación, proveedor o algoritmo fue alterado, inventado o congelado** para producir este veredicto. Los únicos cambios son correcciones de referencias/estado y el nuevo documento de revisión.

---

## 8. Veredicto

> ## GO — alcance explícito: Wave W0 únicamente

**Se autoriza, desde el punto de vista documental, iniciar Wave W0** (`FND-001`, `FND-002`, `FND-003`, `FND-004`, `TST-001`, `DIA-001`, `CAP-001`, `EXP-001`) exactamente como está definida en `docs/05-roadmap/phase1-backlog.md` y en la secuencia de `docs/05-roadmap/v2-roadmap.md` §16.

**Este veredicto NO autoriza:**

- crear el proyecto Android ni ejecutar `FND-001`;
- instalar dependencias;
- avanzar a Wave W1 o posteriores;
- congelar umbrales de detector, perfil de ubicación de producción, proveedor de mapas o algoritmo de elevación;
- tratar la ejecución de campo (F0.6/EXP) como completada o aprobada.

**Justificación:** los siete puntos de revisión (alcance/requisitos, detección/controles manuales, modelo de datos/persistencia/recovery, arquitectura/permisos/privacidad/UX/diagnóstico, testing/campo/gates, dependencias de W0, contradicciones/documentos faltantes) están cubiertos con evidencia documental coherente y trazable. Los hallazgos (H1-H5) son de naturaleza editorial/documental y ninguno bloquea, invalida o contradice una decisión de producto o arquitectura ya aceptada. Las decisiones legítimamente diferidas (§5) tienen gate y dueño explícitos y no necesitan resolverse antes de W0.

---

## 9. Próximo paso concreto

1. **Esta sesión se detiene aquí.** No se crea el proyecto Android, no se instalan dependencias y no se inicia `FND-001`, conforme al alcance explícito de esta tarea.
2. El propietario decide si:
   - acepta el veredicto GO y autoriza a un agente de implementación a ejecutar `FND-001` siguiendo su task card en `phase1-backlog.md` (constraints: `ADR-001`, `ADR-002`, `ADR-012`); y
   - resuelve o pospone la pregunta no bloqueante de §4 (`product-definition.md` dedicado sí/no) y las acciones editoriales recomendadas en H2/H4/H5.
3. Antes de asignar `FND-001` a un agente, aplicar el checklist de Definition of Ready (`v2-roadmap.md` §8) a esa tarea específica, como recomienda el criterio 6 de la matriz de preparación.
