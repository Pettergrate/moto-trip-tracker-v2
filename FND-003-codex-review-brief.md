# Review brief for Codex — FND-003 (Room schema v1)

You are reviewing, not implementing. Do not redesign unless you find a
documented conflict — flag it instead. This mirrors the two-agent workflow
in `docs/05-roadmap/v2-roadmap.md` §10 (Codex/Claude review each other).

## Context

Moto Trip Tracker V2 is in Phase 1, Wave W0. `FND-003` (Room schema v1 +
migration harness) was just implemented by Claude Code in this repo. Read
these source docs first, in this order:

1. `docs/03-architecture/domain-data-model.md` (F0.7) — the conceptual data
   model. This is the authority for what fields/entities/rules should exist.
2. `docs/03-architecture/system-architecture.md` (F0.8) §7-8, §16-17 —
   physical representation rules (ID types, versioning, schema export).
3. `docs/adr/ADR-003-room-source-of-truth.md`,
   `docs/adr/ADR-005-capture-vs-logical-trip.md`,
   `docs/adr/ADR-006-raw-preserved-processed-reproducible.md`,
   `docs/adr/ADR-014-independent-data-algorithm-versioning.md`,
   `docs/adr/ADR-020-single-active-capture-invariant.md`.
4. `docs/05-roadmap/phase1-backlog.md` — search "FND-003" for the task-level
   report of what was done and why, including deviations from F0.8's exact
   version pins (all discovered by attempting real builds, documented with
   reasoning).

## What to check

1. **Field-by-field fidelity.** For each of the 18 entities in
   `app/src/main/java/com/mototriptracker/app/core/database/entity/`, compare
   against F0.7's dictionary (§6-§11). Flag any missing field, wrong
   nullability, wrong type, or invented field not present in F0.7.
2. **Foreign key policy.** Each entity's `onDelete` choice (CASCADE / RESTRICT
   / SET_NULL) has a one-line rationale in a KDoc comment. Check each against
   F0.7's actual stated rules (§15 "Reglas de integridad", §11, §13). The
   `TripLineageLinkEntity → TripEntity` CASCADE choice was explicitly flagged
   by the implementer as a judgment call, not spelled out precisely in F0.7 —
   scrutinize that one particularly.
3. **The single-active-capture invariant**
   (`core/database/dao/TripCaptureDao.kt`, `startCaptureIfNoneActive`).
   ADR-020/REL-INV-001 require at most one ACTIVE `TripCapture` ever. This is
   enforced via a Room `@Transaction` check-then-insert, not a DB constraint.
   Is that actually safe under concurrent callers (e.g. a notification action
   and an app-UI action racing), given Room's connection/transaction model?
   If you think a raw partial-unique index (`CREATE UNIQUE INDEX ... WHERE
   status = 'ACTIVE'`) is needed in addition, say so and explain the failure
   mode the transaction alone doesn't cover.
4. **Taxonomy enums** (`core/model/Taxonomies.kt`) against F0.7 §5 — same
   fidelity check as #1.
5. **Version deviations.** `gradle/libs.versions.toml` documents three
   real build failures that forced version changes from F0.8's exact pins
   (Compose BOM, Hilt, plus Robolectric/KSP additions). Sanity-check the
   stated reasoning is internally consistent; you don't need to re-verify
   the underlying Maven metadata yourself unless something looks off.
6. **Migration-harness readiness.** `room.schemaLocation` export
   (`app/schemas/`), `androidTest` asset wiring in `app/build.gradle.kts`,
   and confirm no `fallbackToDestructiveMigration()` exists anywhere.

## Out of scope

- Do not add DAOs/repositories for entities beyond `TripCaptureDao` — that's
  deliberately deferred to the tasks that need them (see `core/README.md`
  and `data/README.md`).
- Do not touch package structure, Hilt wiring, or anything from FND-001/002.
- Do not change dependency versions without the same "attempt a real build,
  document the failure" discipline already used here — don't swap a pin on
  suspicion alone.

## How to verify your findings

`./gradlew assembleDebug testDebugUnitTest compileDebugAndroidTestSources`
(JAVA_HOME must point at a JDK 17+; Android Studio's bundled JBR at
`C:\Program Files\Android\Android Studio\jbr` works and is what was used to
verify this task originally.)

## Report format

List findings as: file/entity → what's wrong → why it matters → suggested
fix. If nothing is wrong, say so plainly — don't invent findings to seem
thorough.
