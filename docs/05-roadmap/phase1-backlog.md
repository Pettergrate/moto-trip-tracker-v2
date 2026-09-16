# Phase 1 Initial Backlog
## Moto Trip Tracker V2

**Status:** Baseline backlog for post-F0.17 implementation  
**Roadmap version:** F0.15 v0.1  
**Date:** 2026-09-15

This backlog is intentionally task-sized. It is not authorization to code before F0.17 GO.

---

## W0 — Foundation & Experiment Enablement

### FND-001 — Android project bootstrap

**Objective:** create the minimal Android app that builds/runs with the accepted platform baseline and no product logic beyond a shell.

**Constraints:** ADR-001, ADR-002, ADR-012.  
**Implements:** NFR-MNT-001..004, NFR-CMP-001/002.  
**Depends on:** F0.17 GO.

**Acceptance:** app installs/launches; Compose shell exists; dependency catalog/build config is explicit; no V1 code copied; basic local test command passes.

**Status: Done (2026-09-15).** Bootstrapped as `applicationId`/namespace `com.mototriptracker.app`, single `:app` module, Kotlin (AGP 9.4.0 built-in Kotlin support — the separate `org.jetbrains.kotlin.android` plugin no longer applies under AGP 9.0+), Compose (compiler via `org.jetbrains.kotlin.plugin.compose`), Material 3, single Activity. Verified end-to-end: `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` both succeed, and the resulting debug APK was installed and launched on a physical Android 16 (API 36) device via `adb`, confirmed running without crashing.

Deviations from the F0.8-pinned dependency table, discovered only by attempting a real build, and documented in `gradle/libs.versions.toml`:
- Compose BOM pinned to **2026.06.01**, not F0.8's 2026.08.00 — that BOM resolves `androidx.compose.ui:ui-android:1.12.0`, whose AAR metadata requires `minCompileSdk=37`, which is unavailable in this environment and outside F0.8's pinned SDK baseline (compileSdk/targetSdk 36). compileSdk/targetSdk 36 and minSdk 26 were kept exactly as F0.8 specifies.
- `core-ktx` pinned to 1.18.0 and `activity-compose` to 1.11.0 for the same reason (newer releases require compileSdk 37).
- Kotlin pinned to 2.3.21 (not specified by F0.8, which left the exact Kotlin version open).

None of these are architecture changes; all are routine version-pinning explicitly anticipated by `docs/03-architecture/system-architecture.md` §3.2 ("revalidate releases estables... al crear Fase 1/W0"). If compileSdk 37 is adopted later, this pin should be revisited.

---

### FND-002 — Architecture and package skeleton

**Objective:** establish UI/Data/Domain/Platform boundaries and DI wiring without implementing features.

**Constraints:** ADR-002, ADR-012, ADR-013.  
**Depends on:** FND-001.

**Acceptance:** boundaries mirror F0.8; domain does not depend on Android framework types; no speculative multimodule split.

**Status: Done (2026-09-15).** Created the top-level boundary packages from F0.8 §15 (`app/navigation`, `core`, `data`, `domain`, `tracking`, `worker`, `feature`), each with a short `README.md` stating its F0.8 responsibility and which later task populates it — leaf subpackages (e.g. `core/model`, `feature/home`) are intentionally *not* pre-created, to avoid empty structure with no owner; they get created by the task that first needs them. Wired Hilt end-to-end: `MotoTripApplication` (`@HiltAndroidApp`), `MainActivity` (`@AndroidEntryPoint`), and an empty `core/di/AppModule.kt` proving the component graph builds with no bindings yet. Added `architecture/DomainBoundaryTest.kt`, a JVM test that scans `domain/` for `android.*`/`androidx.*` imports and fails the build if any appear — it was verified to actually fail (not just always pass) by temporarily introducing a violating file, then removing it. Single `:app` module unchanged (ADR-012).

Verified with `./gradlew assembleDebug testDebugUnitTest` (both green, including the new architecture test). Not re-verified with a live device install this time — no device was connected when this task ran — but that is not required by this task's acceptance criteria (no product/UI logic changed).

Deviation from F0.8's dependency table, discovered only by attempting a real build:
- Hilt/Dagger bumped to **2.60.1**, not F0.8's pinned 2.57.1 — that release's Gradle plugin fails to apply against AGP 9's new DSL with `Android BaseExtension not found`. 2.60.1 applies and builds cleanly. Revisit if a 2.57.x patch adds AGP 9 support later.
- KSP pinned to 2.3.12 (plain version; KSP has decoupled its version string from the Kotlin version it targets since ~2.3.0).

---

### FND-003 — Room schema v1 + migration harness

**Objective:** implement F0.7/F0.8 persistence model needed by Core and schema tests.

**Constraints:** ADR-003, ADR-005, ADR-006, ADR-014, ADR-020.  
**Implements:** FR-REC-005/006/008, NFR-REL-001/003/004.

**Acceptance:** schema/invariants exist; single ACTIVE capture enforced at domain/transaction boundary; migration-test harness created; destructive migration is not enabled.

**Status: Done (2026-09-15).** All 18 entities from F0.7's dictionary are implemented as Room entities under `core/database/entity/` (capture layer, composition layer, derived/processed layer, organization/context, vehicle), wired into one `MotoTripDatabase` (schema v1, `exportSchema = true`). Taxonomy enums from F0.7 §5 (`CaptureStatus`, `TripStatus`, `StartSource`, `EndSource`, `EditOperationType`, `LineageRole`, `TrackPointDecision`, `StopOrigin`, `DataOrigin`) live in a new `core/model/Taxonomies.kt` — a small, deliberate adjustment to this task's originally-assumed boundary (`core/README.md` said `core/model` was FND-004's job; it turned out FND-003 genuinely needed these small Android-independent enums to type the entities, so they were added here instead of invented redundantly later).

Foreign keys follow F0.7's stated rules, not invented ones: CASCADE for rows with no independent existence apart from their parent (raw points/events/pauses under a capture, trip parts/derived data under a trip), RESTRICT from `TripPart` to `TripCapture` (F0.7 §15: "una captura aún referenciada por otro Trip no puede purgarse"), SET_NULL from `Trip` to `Motorcycle`/`Route` (F0.7: archiving either must not destroy trip history). Every FK column is indexed; KSP produced zero Room warnings.

**Single-active-capture invariant (ADR-020/REL-INV-001):** enforced in `TripCaptureDao.startCaptureIfNoneActive`, a `@Transaction` method that re-checks for an existing ACTIVE row before inserting — per this task's own acceptance wording ("at domain/transaction boundary"), not as a raw partial-unique SQL index. Verified with a JVM test (`TripCaptureDaoTest`, run via Robolectric — see below) proving a second concurrent Start is rejected while one capture is ACTIVE, and accepted again once the first is no longer ACTIVE.

**Codex review round (2026-09-16):** an independent review (Codex) of this task found 5 issues; 3 were confirmed and fixed, 1 was resolved differently than suggested (with reasons), and reviewing it prompted finding one further bug Codex didn't catch. Nothing was found in: the 18-entity field coverage generally, the taxonomy enums, the dependency-version reasoning, or the migration-harness setup.

- **Fixed —** `insert()`/the old generic `updateStatus()` could create or complete captures without going through the invariant guard at all, even with zero concurrency involved. Replaced `updateStatus()` with `completeActiveCapture(...)`, which can only transition ACTIVE → COMPLETED and always sets `endedAt`/`endElapsedRealtimeNanos`/`endSource` together (fixes the next point too), gated by `WHERE status = 'ACTIVE'` so it's idempotent. `insert()` itself can't have its visibility narrowed below `public` on a Room `@Dao` interface member (`internal`/`private` on an abstract interface method doesn't compile — verified) and ADR-012's single-module bootstrap means there's no real module boundary to hide it behind anyway; it's now just documented not to be called directly. A real DB-level fix (a partial unique index on `status = 'ACTIVE'`, added via `RoomDatabase.Callback.onCreate`) is flagged as a to-do for whichever task adds the first production `MotoTripDatabase` builder (FND-004 or TRK-001) — none exists yet for it to attach to.
- **Fixed —** `completeActiveCapture` (formerly `updateStatus`) left `endedAt` null on a COMPLETED capture, contradicting F0.7 §6.1/§15 ("COMPLETED requiere endedAt definido"). Now structurally impossible: there's no way to set status without also setting the end fields.
- **Fixed —** `CaptureEventEntity.elapsedRealtimeNanos` was nullable; F0.7 §6.3 lists it without `?` (required). No conflicting source — straightforward fix.
- **Resolved differently —** Codex also flagged `RawTrackPointEntity.receivedAtElapsedRealtimeNanos` as should-be-required per F0.7 §6.2. Checking that against F0.5 §5 (which F0.7 §6.2 explicitly says this contract follows) found F0.5 lists the same field (`receivedAtElapsed?`) as **optional** ("medir latencia de entrega si se necesita") — a genuine F0.5/F0.7 inconsistency, not something to silently resolve either way. Kept nullable, following F0.5's more specific rationale, and documented the conflict in the entity's KDoc rather than picking a side quietly.
- **Additional bug found while re-verifying this area** (not flagged by Codex): `RawTrackPointEntity.horizontalAccuracyM` was nullable; F0.7 §6.2 lists it without `?` (required), and F0.5 agrees (no conflict here). Fixed to non-null `Float`.
- **Applied as a defensible interpretation, not a literal spec fix:** `TripStatisticsEntity.movingDurationMs`/`stoppedDurationMs` are now nullable. F0.7 §9.3's field list doesn't mark them with `?`, but its own general rule for this entity ("una métrica desconocida queda nula, no cero") supports it — a Trip can have a known `totalDurationMs` with no reliable location data at all, making the moving/stopped split genuinely unknown.
- **Fixed —** the DAO test claimed to verify a concurrent second Start but only made two sequential calls. Added `onlyOneOfManyConcurrentStartsSucceeds`, which fires 20 real concurrent coroutines (`Dispatchers.IO` under `runBlocking`, not `runTest`'s virtual scheduler) and asserts exactly one succeeds — this is what actually exercises Room's transaction serialization, which Codex confirmed is correctly applied here.

Re-verified after all fixes: `./gradlew assembleDebug testDebugUnitTest` — 4/4 DAO tests pass (up from 2), full suite green.

**Migration-test harness:** `room.schemaLocation` exports schema JSON to `app/schemas/` (F0.8 §17) — verified present with all 18 tables (`app/schemas/.../1.json`). That directory is wired as `androidTest` assets so a future `MigrationTestHelper` instrumented test can read it. `room-testing` is added to both `test` and `androidTest`. There is no v1→v2 migration to test yet (nothing has changed the schema), so no fake migration was invented just to exercise the harness — the next schema-changing task adds the first real migration test. Destructive migration is not enabled anywhere (no `fallbackToDestructiveMigration()` call exists in the codebase).

Verified with `./gradlew assembleDebug testDebugUnitTest compileDebugAndroidTestSources` (all green; 2/2 new DAO tests pass alongside the existing FND-002 tests).

Deviations discovered only by attempting real builds, documented in `gradle/libs.versions.toml`:
- Room's Android-artifact `Room.databaseBuilder`/`inMemoryDatabaseBuilder` only exposes the classic `(Context, Class)` factory in this (non-multiplatform) module — the Context-free KMP overload shown in Room's own multiplatform docs isn't visible here. Added **Robolectric 4.17** + `androidx.test:core` so `TripCaptureDaoTest` supplies a real `Context` and still runs as a fast JVM test, not an instrumented one.
- Robolectric needs `--add-opens` JVM flags on JDK 17+ (fails with an `IllegalAccessException` reaching `jdk.internal.access.SharedSecrets` otherwise) — added to `tasks.withType<Test>` in `app/build.gradle.kts`.
- Initially tried `androidx.sqlite:sqlite-bundled` + `.setDriver(BundledSQLiteDriver())` as a Robolectric-free alternative; removed after it failed with `UnsatisfiedLinkError` (that artifact's native library is Android-ABI-only, not usable from a desktop JVM). Not used in the final version.

---

### FND-004 — IDs, clocks and version primitives

**Objective:** implement stable identifiers, wall/elapsed time abstractions and schema/detector/location/processing version values.

**Constraints:** ADR-013, ADR-014.  
**Depends on:** FND-002.

**Acceptance:** core logic can use fake clocks; domain does not directly call Android system clock; versions are persisted/available where defined.

**Status: Done (2026-09-16).** `core/common/Clock` (interface) + `AndroidClock` (the only class allowed to call `System.currentTimeMillis()`/`android.os.SystemClock.elapsedRealtimeNanos()`) give domain/data code a seam instead of a direct platform dependency (ADR-013). `core/common/IdGenerator` + `UuidIdGenerator` do the same for F0.7 §2.3/F0.8 §8.1 stable identifiers. Both are bound in `core/di/AppModule.kt` via `@Binds` (its first real bindings — FND-002 only proved the empty graph compiled). Test doubles `FakeClock`/`FakeIdGenerator` live under `app/src/test/.../core/common/` and are proven correct by `ClockTest`/`IdGeneratorTest` — this satisfies "core logic can use fake clocks" directly; no artificial domain-logic consumer was invented just to exercise the seam, since none exists yet (DET-*/PRC-* will be the first real consumers and get tested against these same fakes).

Version primitives (`DetectorVersion`, `LocationProfileVersion`, `ProcessingVersion` — `@JvmInline value class` wrapping `Int`, zero runtime cost, `Comparable`) live in `core/model/Versions.kt` per ADR-014 ("identificadores independientes"). Retrofitted into the FND-003 entities that already carried these as plain `Int` (`TripCaptureEntity.detectorVersion/locationProfileVersion`; `processingVersion` on `ProcessedTrackPointEntity`, `PointAssessmentEntity`, `TripStatisticsEntity`, `LocationGapEntity`, `TripStopEntity`) — verified empirically that Room 2.8.5 accepts value classes natively (including inside composite primary keys) with **no schema/affinity change** (confirmed by re-diffing the exported schema JSON: same `INTEGER` affinity, same column names). `schemaVersion` itself is not wrapped — Room's own `@Database(version = ...)` already models it, per ADR-014's own listing.

Verified with `./gradlew assembleDebug testDebugUnitTest` — 12/12 tests pass across the whole suite (up from 4), including the retrofitted `TripCaptureDaoTest`.

One self-caught bug during this task, unrelated to the above: a KDoc comment containing the literal substring `*/` inside running text (`"...DET-*/PRC-*..."`) closed the Kotlin block comment early, turning the rest of the doc into invalid top-level code. Caught by the compiler immediately, fixed by rewording. Noted here only because it's a sharp edge worth remembering when writing KDoc that mentions task-ID wildcards.

---

### TST-001 — Deterministic test harness

**Objective:** implement `FakeClock`, deterministic dispatchers, database factories, `LocationReplaySource`, `ActivityReplaySource` and capability fakes.

**Constraints:** ADR-013, ADR-018.  
**Implements:** NFR-TST-001..003.

**Acceptance:** a sample replay is deterministic; tests use no arbitrary sleeps for core state timing.

**Status: Done (2026-09-16).** `core/common/DispatcherProvider` (+ `AndroidDispatcherProvider`) is the last ADR-013 seam before domain/data logic can be written without touching `Dispatchers.IO`/`Default`/`Main` directly; bound in `AppModule` alongside `Clock`/`IdGenerator` from FND-004.

Three new pure, Android-free data shapes in `core/model/` back the rest of the harness — not new product decisions, just already-specified F0.4/F0.5/F0.6/F0.11 vocabularies transcribed into domain-safe types so the replay/fake infrastructure has something concrete to work with:
- `LocationSample` — the observation-level subset of F0.5 §5/F0.7 §6.2's RawTrackPoint contract (everything except capture bookkeeping), per F0.12 §5.2's field list.
- `ActivityTransitionSample` (+ `ActivityType`, `TransitionType`) — F0.4 §4.1's activity vocabulary + F0.6 §6.3's event field list.
- `CapabilityInputs` — the raw permission/service/setting inputs F0.11 §18-19 resolves into FULL_AUTO/ASSISTED_AUTO/MANUAL/LOCATION_DEGRADED. This is deliberately just the input shape, not the resolver — `CAP-001` (next task, not yet implemented) owns the actual decision logic.

Test infrastructure lives in a new `app/src/test/.../testing/` package: `LocationReplaySource`/`ActivityReplaySource` (replay a fixed sequence via `Flow`, no real delays, no interpretation — just data replay), `FakeDispatcherProvider` (collapses io/default/main onto one `TestDispatcher`), `FakeCapabilityProvider` (mutable `CapabilityInputs` behind a `StateFlow`, with `fullAuto()`/`cleanInstall()` presets matching CAP-001's own stated test matrix), and `TestDatabaseFactory` (extracted from `TripCaptureDaoTest`'s original inline setup — both the in-memory and file-backed flavors F0.12 §5.6 asks for).

Proved the acceptance criterion directly with `ReplayDeterminismTest` (replaying the same sequence twice yields identical output; wall-clock time measured to confirm nothing is actually sleeping) and `TestDatabaseFactoryTest` (file-backed DB survives a real close/reopen — the one flavor that hadn't been exercised yet). `TripCaptureDaoTest` refactored to use `TestDatabaseFactory` instead of repeating its own setup.

Verified with `./gradlew assembleDebug testDebugUnitTest`: 17/17 tests pass across the whole suite (up from 12).

Two self-caught bugs this task, both the same class of mistake as one already found in FND-004: a KDoc comment containing the literal substring `*/` in running text (`"...DET-*/PRC-*..."`, twice) closed a Kotlin block comment early. Both caught immediately by the compiler; grepped the whole `app/src` tree afterward for the same pattern to confirm no third instance existed.

**Follow-up self-audit (2026-09-16):** re-checked `LocationSample`/`ActivityTransitionSample`/`CapabilityInputs` field-by-field against F0.4 §4.1, F0.5 §5, F0.6 §6.3 and F0.11 §18-19 directly (the same discipline the Codex round applied to FND-003's Room entities), rather than continuing straight to `CAP-001`/`DET-*` on unaudited assumptions. Found 3 real bugs, same failure pattern as FND-003 (a field the spec marks required left nullable):
- `ActivityTransitionSample.wallTimeEpochMs` was nullable; F0.6 §6.3 lists "timestamp / elapsedRealtime" together, neither marked `?`. Fixed to non-null.
- `ActivityTransitionSample.source` was nullable; F0.6 §6.3 lists it without `?`. Fixed to non-null. (Only `confidence` is actually marked optional there.)
- `LocationSample.sourceProfileId` was nullable and inconsistently named; F0.5 §5 lists the equivalent field (`requestProfileId/version`) without `?`, and `RawTrackPointEntity` already calls it `requestProfileId`. Renamed and fixed to non-null.

`CapabilityInputs` checked against F0.11 §18's FULL_AUTO_READY/ASSISTED_AUTO/MANUAL/LOCATION_DEGRADED requirements and CAP-001's own stated test matrix — no defect found; every field maps to a named F0.11 dimension and nothing is invented.

Re-verified with `./gradlew assembleDebug testDebugUnitTest`: 17/17 tests still pass after the fixes.

---

### DIA-001 — Structured diagnostic event foundation

**Objective:** persist privacy-safe diagnostic events, reason codes, correlation IDs and current algorithm versions.

**Constraints:** ADR-014, ADR-017.  
**Implements:** FR-DIA-001..004.

**Acceptance:** event schema exists; ordinary GPS points do not generate event spam; release logging does not expose coordinates.

**Status: Done (2026-09-16).** `DiagnosticEventEntity` (19th table, still schema v1 — pre-release, so no migration ceremony needed for adding it) implements F0.13 §4's full field contract, plus `DiagnosticCategory`/`DiagnosticSeverity` (F0.13 §5/§6, in `core/model`) and a minimal `DiagnosticEventDao` (insert + the reads needed to prove persistence — no Debug Screen/export query needs pre-built without a caller). `metadata` (F0.13 §4: "mapa pequeño y allowlisted") persists via `DiagnosticMetadataConverters`, a `Map<String,String>` <-> TEXT converter using plain `java.net.URLEncoder`/`URLDecoder` — no JSON library dependency added for a handful of short key-value pairs.

Deliberately **no foreign key** from `DiagnosticEventEntity` to `TripCaptureEntity`/`TripEntity`: `captureId`/`tripId` are correlation hints, not referential constraints, because diagnostic data has its own independent retention policy (F0.13 §12: ~14 days/~20,000 events) that must stay decoupled from domain data's lifecycle in both directions.

Two of the three acceptance criteria are structural and verified directly:
- **"event schema exists"** — `DiagnosticEventDaoTest` round-trips a full event (with metadata) and an empty-metadata event through a real Room database.
- **"release logging does not expose coordinates"** — enforced at the type level, the same way `DomainBoundaryTest` enforces ADR-013: `DiagnosticEventPrivacyTest` reflects over `DiagnosticEventEntity`'s fields and fails the build if any name matches `latitude`/`longitude`/`notes`/`name`. Verified it actually catches a violation (temporarily added a `temporaryLatitude` field, confirmed the test failed, removed it) rather than trusting the entity has no such field by inspection alone.

The third — **"ordinary GPS points do not generate event spam"** — is a documented contract for future producers (F0.13 §3.1: don't insert one of these per accepted RawTrackPoint), not something this task can operationally verify: there is no producer yet (`TRK-002`/`PRC-001` will be the first ones). Said so directly rather than claiming a test proves a rule nothing yet exercises.

Verified with `./gradlew assembleDebug testDebugUnitTest`: 30/30 tests pass across the whole suite (up from 27); schema export confirms 19 tables including `diagnostic_event`.

---

### CAP-001 — Capability resolver

**Objective:** derive `FULL_AUTO`, `ASSISTED_AUTO`, `MANUAL` and `LOCATION_DEGRADED` from current permissions/services/capabilities.

**Constraints:** ADR-008, ADR-009.  
**Implements:** NFR-PRV-002/003, F0.11 capability matrix.

**Acceptance:** deterministic unit coverage for clean install, denied permissions, approximate location, disabled location services and notification denial.

**Status: Done (2026-09-16).** `domain/capability/CapabilityResolver` — the first real logic in `domain/` (everything before it was pure data shapes or infrastructure); `DomainBoundaryTest` from FND-002 finally has real code to check, and still passes (zero `android.*`/`androidx.*` imports).

Precedence, documented in the resolver's own KDoc: (1) missing precise location or disabled location services → `LOCATION_DEGRADED`, overriding everything else — this includes a clean install with every field false, deliberately, since Android's own permission API can't distinguish "never asked" from "denied" either; a friendlier first-run copy for that case is a UI/onboarding concern, not this resolver's. (2) Auto Tracking off by the user, or Activity Recognition not granted → `MANUAL` (F0.9 §16 / F0.11 §5, no approved Assisted fallback for AR denial). (3) otherwise `FULL_AUTO` only if background location AND notifications are both available (F0.11 §18/§9); `ASSISTED_AUTO` otherwise.

10 deterministic tests cover every named scenario from the acceptance criterion (clean install, denied Activity Recognition, denied background location, approximate-only, disabled location services, denied notifications) plus the Auto-Tracking-off case and a couple of precedence checks (AR denial overriding an otherwise-fine location; multiple simultaneous Full-Auto disqualifiers still landing on Assisted, not a worse state).

Verified with `./gradlew assembleDebug testDebugUnitTest`: 27/27 tests pass across the whole suite (up from 17).

---

### EXP-001 — Diagnostic harness shell

**Objective:** provide internal controls/log export needed to execute F0.6 experiments.

**Constraints:** ADR-017, ADR-018.  
**Depends on:** TST-001, DIA-001, CAP-001.

**Acceptance:** session metadata, ground-truth markers and diagnostic export can be captured without becoming user-facing Core UX.

**Status: Done (2026-09-16) — W0 complete.** New `experiment/` package (not Room-backed, deliberately: F0.6's harness output is external analysis files — `session.json`/`annotations.json` — not a production-queryable feature, so keeping it out of `MotoTripDatabase` avoids conflating throwaway experiment data with domain data). `FieldTestSessionMetadata` transcribes F0.6 §6.1's field list verbatim (re-read directly before writing it, given the transcription-bug pattern already found twice); `GroundTruthMarkerType`/`GroundTruthMarker` transcribe F0.6 §7's exact vocabulary. `GroundTruthMarkerLog` accumulates markers (pure, no Android). `FieldTestDatasetWriter` (seam, ADR-013) + `AndroidFieldTestDatasetWriter` (writes to `filesDir/field-tests/sessions/<sessionId>/`) + `FieldTestSessionJson` (serialization via `org.json`, bundled in the Android SDK — no new dependency) + `FieldTestSessionExporter` (ties it together) implement the two files EXP-001 is actually scoped to produce.

**Explicitly not built:** F0.6 §20 lists more dataset files (`raw-track.csv`, `activity-events.jsonl`, `detector-events.jsonl`, `system-events.jsonl`, `processed-track.geojson`, `battery/`) — these need data sources that don't exist yet (`TRK-002` raw location ingestion, `DET-001` activity recognition, a real detector/system-lifecycle observer). Writing placeholder/empty versions of those files just to match F0.6 §20's directory structure would be fabricating dataset content with nothing behind it, so this task doesn't do that — those files get written by the tasks that actually produce their data, when W2's field campaigns need them.

Verified, including one real bug caught: `org.json.JSONObject`/`JSONArray` resolve to the Android SDK **stub** jar outside Robolectric, and every method on the stub throws `RuntimeException("Stub!")` at runtime — the two tests exercising serialization failed immediately until `@RunWith(RobolectricTestRunner::class)` was added (same class of environment gotcha as TST-001's Room/Context issue, different API). `AndroidFieldTestDatasetWriterTest` additionally proves the production writer reaches real file I/O under Robolectric's `Context.filesDir`, not just that it compiles.

`./gradlew assembleDebug testDebugUnitTest`: 36/36 tests pass across the whole suite (up from 30). This closes every task in Wave W0 (`FND-001..004`, `TST-001`, `DIA-001`, `CAP-001`, `EXP-001`) — see `docs/05-roadmap/v2-roadmap.md` §4 for W0's exit condition and W1's entry point.

---

## W1 — Manual Recording Vertical Slice

### TRK-001 — Foreground tracking service + manual start

**Objective:** explicit Start creates/reuses the only ACTIVE TripCapture and starts location FGS tracking.

**Constraints:** ADR-003, ADR-004, ADR-015, ADR-020.  
**Implements:** FR-DET-005/010, FR-REC-008.

**Acceptance:** one active capture only; repeated Start is idempotent; service rehydrates state from Room.

**Status: Done (2026-09-16).** `tracking/coordinator/TrackingSessionCoordinator` (`domain`-shaped, no Android dependency — ADR-013) owns the decision logic: `startManualCapture()` delegates the check-then-insert to FND-003's `TripCaptureDao.startCaptureIfNoneActive` (ADR-020/REL-INV-001), stamps `DetectorVersion(0)`/`LocationProfileVersion(0)` placeholders (no real detector or location-sampling profile exists yet — `DET-001`/`TRK-002`; a manual Start doesn't need one per F0.3 §6, but the columns are non-null), and records a `DiagnosticEvent` either way (`NEW_CAPTURE` vs `ALREADY_ACTIVE` reason code). `tracking/service/TrackingForegroundService` (`@AndroidEntryPoint`, ADR-004) is the thin Android owner: calls `startForeground()` as the first statement in `onStartCommand` (before any suspending work — required within a short window on API 26+/31+/34+, verified against F0.4's sources; doing DB work first risks `ForegroundServiceDidNotStartInTimeException` in the field even though it'd work fine on a fast emulator), dispatches `ACTION_START` to the coordinator, and treats any other intent (including `null`, the sticky-restart case) as "rehydrate from Room or stop" (F0.10 §7.2/REL-002) rather than trusting stale intent state. `core/notification/TrackingNotificationController` builds the minimal foreground notification (no actions yet — `NOT-001`).

**Fixed — Hilt gap found by this task, not a review:** FND-003 built the Room schema/DAOs but nothing had ever wired a real `MotoTripDatabase` builder into Hilt (nothing needed one until this coordinator). Compiling this task surfaced `[Dagger/MissingBinding]` for `TripCaptureDao`/`DiagnosticEventDao`; fixed with a new `core/di/DatabaseModule.kt` (`@Provides @Singleton` builder, no `fallbackToDestructiveMigration()` and no migrations yet — schema v1 is still pre-release, ADR-008-adjacent caution against ever being destructive once there's real user history).

**Real bug found writing this task's own test, not by inspection:** completing `DatabaseModule` made `MotoTripApplication`'s (`@HiltAndroidApp`) production Hilt graph buildable, which meant Robolectric — using the app's real `Application` class, not a stub — started performing **genuine** Hilt field injection on `TrackingForegroundService` during `ServiceController.create()`. The test had been assigning fake `coordinator`/`notificationController`/`dispatchers` *before* calling `.create()`, on the reasonable-looking assumption that nothing else would touch those fields; instead Hilt's generated injection ran during `.create()` and silently overwrote the fakes with real production instances — including a real `MotoTripDatabase` bound to `moto-trip-tracker.db`, completely separate from the test's own in-memory `db`. Symptom: `coordinator.startManualCapture()` reported `Started(captureId=<uuid>)` (a real `UuidIdGenerator` value, not the `FakeIdGenerator(prefix = "capture")` the test configured), yet `db.tripCaptureDao().countByStatus(ACTIVE)` against the test's own database read back `0` — the write had genuinely succeeded, just against the wrong database. Fixed by reordering: `.create()` first (let Hilt's real injection happen and be immediately discarded), *then* assign the fakes, then invoke `startCommand()` — nothing re-injects between `create()` and a later `onStartCommand()`, so the fakes are what the command handling actually reads. A `CoroutineExceptionHandler` + a `@VisibleForTesting` `Job` handle (`lastCommandJob`) added mid-investigation to rule out a swallowed-exception hypothesis turned out to also be the right production behavior (F0.13's evidence-not-silence principle for a fire-and-forget service command) and the right test pattern (`.join()` instead of fighting dispatcher/scheduler synchronization), so both were kept rather than reverted.

**On-device verification (Honor DNY-NX9, Android 16/API 36, serial `AX3C025B12001958`):** installed the debug APK, granted `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION`/`POST_NOTIFICATIONS` via `adb shell pm grant` (no permission-request UI exists yet — `PERM-001`). The service is intentionally `android:exported="false"` (only this app should ever start it); starting it from `adb shell` directly fails with a `SecurityException` as designed, so verification used `adb shell run-as com.mototriptracker.app am start-foreground-service` (runs as the app's own UID, which `exported="false"` always permits) with `MainActivity` foregrounded first (Android's background-FGS-start restriction, unrelated to `exported`, requires the calling process to be in an exempted state — a real production Start will always be user-tap-initiated from a foregrounded UI, so this exactly mirrors the real call path). Pulled the live `moto-trip-tracker.db`/`-wal`/`-shm` off-device (`run-as ... cat`) and queried them locally (Node 24's built-in `node:sqlite`) instead of trusting only logcat/dumpsys:
- First `ACTION_START`: exactly one `trip_capture` row, `status=ACTIVE`, `endedAt=null`; one `diagnostic_event` row (`USER_COMMAND`/`START`/`NEW_CAPTURE`); `dumpsys activity services` confirmed `isForeground=true` with a real posted notification (`ONGOING_EVENT|FOREGROUND_SERVICE`).
- Second `ACTION_START` (idempotency): still exactly one `ACTIVE` row, **same** `captureId`; a second `diagnostic_event` row was added with `reasonCode=ALREADY_ACTIVE` — no duplicate capture, no lost signal that a redundant Start happened.
- Null-action restart (sticky-restart simulation) with the `ACTIVE` capture still present: service stayed foreground (`isForeground=true`), did not stop itself — correct rehydrate-and-continue behavior, the complement of the no-active-capture case already covered by the unit test below.
Cleaned up afterward with `adb shell am force-stop` and removal of the locally pulled DB copies; no manifest or production code was changed for this — a stray attempt to temporarily flip `exported="true"` for easier adb access was caught and blocked by the session's own security-weakening guard before any build ran, and was reverted immediately (confirmed via `git diff` showing no residual change) in favor of the `run-as` approach above.

Verified with `./gradlew assembleDebug testDebugUnitTest`: 42/42 tests pass across the whole suite (up from 27; new: `TrackingSessionCoordinatorTest` ×4, `TrackingForegroundServiceTest` ×2, plus `DatabaseModule`/`TrackingNotificationController` exercised transitively). Deviations from F0.8's pinned versions carried over unchanged from FND-001 (Compose BOM 2026.06.01, Hilt/Dagger 2.60.1) — nothing new pinned by this task.

---

### TRK-002 — FLP location ingestion + RawTrackPoint persistence

**Objective:** record timestamped precise locations and quality metadata into Raw Track.

**Constraints:** ADR-003, ADR-006, ADR-016.  
**Implements:** FR-REC-001..005/007/008.

**Acceptance:** ordering preserved; invalid/poor points are assessed without rewriting raw evidence; offline works.

---

### NOT-001 — Active trip notification controls

**Objective:** persistent notification exposes Pause/Resume/Finish with safe idempotent commands.

**Constraints:** ADR-004, ADR-015.  
**Implements:** FR-NOT-001..004, NFR-SAFE-002.

**Acceptance:** actions work with UI backgrounded; duplicate intents do not corrupt state.

---

### TRK-003 — Manual pause/resume

**Objective:** manual pause explicitly owns paused state until user resumes/finishes; no silent auto-resume.

**Constraints:** ADR-007, ADR-015.  
**Implements:** FR-DET-007..009.

**Acceptance:** pause interval persisted; movement while paused can produce warning evidence but cannot auto-cancel pause.

---

### TRK-004 — Finish capture transaction

**Objective:** idempotently finalize capture, create/update logical Trip composition and enqueue derived processing.

**Constraints:** ADR-003, ADR-005, ADR-010, ADR-015, ADR-020.

**Acceptance:** repeated Finish is safe; no second ACTIVE capture remains; partial failure rolls back structural mutation.

---

### PRC-001 — Point assessment + Processed Track v1

**Objective:** derive accepted/rejected point assessments and Processed Track without altering Raw Track.

**Constraints:** ADR-006, ADR-014, ADR-016.

**Acceptance:** processing is reproducible for a version; gaps are explicit; no fabricated interpolation.

---

### PRC-002 — Core distance/time/speed metrics

**Objective:** calculate distance, duration, moving/stopped/paused time and speed metrics from approved data sources.

**Implements:** FR-MET-001..008/012.  
**Constraints:** ADR-006, ADR-014/016.

**Acceptance:** deterministic golden tests; max speed rejects documented spike cases; display polyline is not metric source.

---

### PRC-003 — Elevation baseline

**Objective:** provide elevation range and quality-aware provisional gain/loss processing without pretending the deferred algorithm is final.

**Implements:** FR-MET-009/010.  
**Constraints:** ADR-006, ADR-014.

**Acceptance:** algorithm/version is explicit; noisy/unsupported altitude can degrade gracefully; reopen trigger documented.

---

### UI-001 — App shell, Home and Active Trip

**Objective:** implement F0.9 primary navigation and active-trip-first Home behavior.

**Constraints:** ADR-011.  
**Implements:** NFR-SAFE-001..003.

**Acceptance:** Home/History/Favorites shell; Active Trip is special destination/state, not a fourth permanent tab; predictive back behavior validated.

---

### HIS-001 — History + Trip Detail

**Objective:** display persistent Trips and a detail view that remains useful without map availability.

**Implements:** FR-HIS-001..004, FR-HIS-008, FR-MET-001..012.

**Acceptance:** chronological history; rename; metrics and route summary available offline; map failure does not hide Trip data.

---

### MAP-001 — Map provider decision + isolated renderer

**Type:** spike + implementation after decision.

**Objective:** choose a provider based on documented constraints, record ADR if needed, and implement the provider behind a renderer abstraction.

**Constraints:** ADR-019.

**Acceptance:** provider decision is explicit; recording has no dependency on map tiles/network; large-track strategy has tests/benchmark path.

---

### REC-001 — Same-boot process recovery

**Objective:** recover an active capture after process death within the same boot using persisted state and explicit gaps.

**Constraints:** ADR-003, ADR-004, ADR-015/016/020.

**Acceptance:** process recreation does not duplicate ACTIVE capture; evidence records gap/recovery; Raw Track remains intact.

---

## W2 — Automatic Detection & Field Freeze

### DET-001 — Activity Recognition transitions
**Objective:** register/restore Transition API signals as passive detector input.  
**Constraints:** ADR-007/008.  
**Acceptance:** IN_VEHICLE is evidence only, not motorcycle proof; registration restored after supported boot/update paths.

### DET-002 — Candidate Start engine
**Objective:** implement F0.3 candidate-start state/evidence evaluation.  
**Constraints:** ADR-007/013/018.  
**Acceptance:** deterministic replay tests; no single sample starts a Trip by itself.

### DET-003 — Candidate Stop engine
**Objective:** implement candidate-stop evidence and delayed finalization logic.  
**Acceptance:** traffic lights/short stops do not end normal Trip fixtures.

### DET-004 — Temporary-stop hysteresis
**Objective:** implement temporary hold/churn protection.  
**Acceptance:** congestion/semáforo replay stays one Trip.

### DET-005 — Manual-pause warning logic
**Objective:** detect sustained movement while manually paused and warn without auto-resume.  
**Acceptance:** manual ownership remains authoritative.

### DET-006 — Duplicate-start / post-finish suppression
**Objective:** prevent immediate duplicate or rebound starts.  
**Constraints:** ADR-015/020.

### AUTO-001 — Capability-aware automatic orchestration
**Objective:** connect detector to Full Auto or Assisted flows based on CAP-001.  
**Constraints:** ADR-004/007/008/020.

### EXP-002 — Pilot field campaign
**Objective:** execute F0.6 pilot and identify logging/profile defects before comparative measurements.

### EXP-003 — Location profile campaign
**Objective:** compare interval/min-distance/batching experiment profiles.

### EXP-004 — Detector start/stop campaign
**Objective:** measure start latency, stop latency, fragmentation and missed Trips.

### EXP-005 — False-positive campaign
**Objective:** measure car/bus/bicycle/on-foot ambiguity and adjust only with documented evidence.

### EXP-006 — Battery campaign
**Objective:** compare candidate profiles under controlled conditions.

### EXP-007 — Held-out validation campaign
**Objective:** validate selected behavior on routes/rides not used to tune the detector.

### EXP-008 — Freeze Detector/Location Profile v1
**Objective:** approve production defaults and version them.  
**Hard gate:** G4. No freeze without evidence.

---

## W3 — Editing & Everyday Use

### EDT-001 — Merge Trips
**Objective:** compose multiple TripParts without duplicating/re-writing Raw Track.  
**Constraints:** ADR-005/015.

### EDT-002 — Split Trip
**Objective:** create logical Trips from defined boundaries while preserving capture lineage.

### EDT-003 — Boundary correction
**Objective:** adjust logical start/end boundaries reversibly where specified.

### EDT-004 — Recalculation/invalidation
**Objective:** invalidate/recompute derived artifacts after structural edits using processingVersion.

### TRS-001 — Trash/restore/purge
**Objective:** non-immediate destructive delete with restore path and documented purge semantics.

### FAV-001 — Trip favorites
**Objective:** favorite/unfavorite and Favorites area for Trips.

### HIS-002 — Search/filter/sort
**Objective:** complete Core history management at scale.

### MAP-002 — Stops/markers/large tracks
**Objective:** render start/end/stop markers and long routes efficiently.

### MET-001 — Core metric presentation
**Objective:** finish user-facing Core metrics, quality/degraded labels and recalculation visibility.

---

## W4 — Reliability, Permissions & Diagnostics Hardening

### REC-002 — Sticky service restart recovery
### REC-003 — Reboot boundary reconciliation
### REC-004 — User stop / force-stop semantics
### REC-005 — GPS gap and location-degraded handling
### REC-006 — Persistence failure buffer/retry
### PERM-001 — Progressive permission onboarding
### PERM-002 — Background location disclosure/settings flow
### PERM-003 — Notifications/location-service degradation UX
### DIA-002 — Internal Debug Screen
### DIA-003 — Sanitized diagnostic ZIP
### DIA-004 — Process exit/recovery evidence
### PRV-001 — Backup/export/privacy enforcement

Each task must implement the corresponding F0.10/F0.11/F0.13 contract rather than redesign it.

---

## W5 — Core Release Hardening

### PERF-001 — Long-trip ingestion benchmark
### PERF-002 — Large-history benchmark
### PERF-003 — Map preparation/render benchmark
### REL-001 — Reliability fault-injection matrix
### MIG-001 — Migration preservation suite
### PRIV-001 — Privacy/security release audit
### CMP-001 — Android/device compatibility matrix
### ACC-001 — Accessibility validation
### RC-001 — G3 release candidate suite
### RC-002 — Core acceptance review

`RC-002` may only close when all P0/P1 blockers are resolved or explicitly reclassified through project review; an agent cannot waive a blocker on its own.
