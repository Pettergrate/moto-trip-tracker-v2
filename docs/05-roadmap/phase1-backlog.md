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

**Status: Done, fully verified including on-device (2026-09-16).** `tracking/location/LocationGateway` (F0.8 §6's named component) is the seam; `FusedLocationGateway` is the only class touching `com.google.android.gms.location.*`, everything downstream only ever sees the already-framework-free `LocationSample` TST-001 built. Wired into `TrackingSessionCoordinator.recordLocationUpdates(captureId)`, which `TrackingForegroundService` launches (as a separate, independently-tracked `locationRecordingJob`, not the one-shot `lastCommandJob`) after a successful Start and, new in this task, after a sticky-restart rehydration that finds a still-ACTIVE capture — TRK-001's `rehydrateOrStop()` only handled the "nothing to own, stop" half of that branch; this task completes the "something to own, resume it" half.

**Scope boundary, decided against the domain model doc before writing code:** `PointAssessmentEntity` and `LocationGapEntity` are both explicitly derived/versioned-by-`processingVersion` entities per F0.7 §9.2/§9.4, and `PRC-001` ("derive accepted/rejected point assessments... reproducible for a version") is the next backlog item specifically scoped to produce them. So this task writes zero rows to either table — every raw fix is persisted as-is regardless of quality (F0.5 §7.1: "no existirá una regla global como si accuracy > X → borrar punto"), satisfying its own acceptance criterion by construction rather than by implementing an assessment pipeline that belongs to a different task. The one exception the type system itself forces: `RawTrackPointEntity.horizontalAccuracyM` is non-nullable (FND-003), so a raw Android fix with `hasAccuracy() == false` cannot become a well-formed row at all; ADR-016 forbids fabricating a value for it, so `FusedLocationGateway` drops that one fix (logs via `Log.w`, does not emit a `LocationSample`) rather than inventing a `0.0f`. This is the only place anything is filtered before Raw Track.

**Ordering (F0.8 §9 / TST-DB-003):** `sequenceNumber` is assigned by arrival order into `recordLocationUpdates`, not derived from `elapsedRealtimeNanos` — two samples with identical or out-of-order timestamps still get distinct, strictly increasing sequence numbers, and nothing gets reordered or rejected at this layer. Resumes from `MAX(sequenceNumber) + 1` (queried once when recording starts) rather than assuming a fresh capture, specifically so the sticky-restart-into-an-ACTIVE-capture path above doesn't collide with the `(captureId, sequenceNumber)` unique index.

**Persistence-failure visibility (F0.8 §9: "no debe quedar invisible mientras la UI afirma que el Trip está sano"):** a fix that fails to insert is logged as a `PERSISTENCE`/`ERROR` `DiagnosticEvent` (`reasonCode` = the exception's class name, no message/free text — kept consistent with `DiagnosticEventPrivacyTest`'s rules) and then skipped. No retry/buffer was built — that is `REC-006`'s named task, and building it here would be solving a reliability problem this task wasn't scoped for.

**New dependency:** `play-services-location:21.4.0`, pinned exactly per F0.8 §3 — first library this project actually needed from that table; resolved cleanly with no AGP9/compileSdk-style surprises.

**Deviation from the architecture doc, noted rather than silently followed:** F0.8 §6 names `TripCaptureRepository` as the intended owner of TripCapture/RawTrackPoint/CaptureEvent persistence. TRK-001 already accessed `TripCaptureDao`/`DiagnosticEventDao` directly from the coordinator (no repository layer exists), and this task follows that same precedent for `RawTrackPointDao` rather than introducing a repository abstraction for one task when the existing direct-DAO pattern is already established, tested, and working. Revisit if/when enough DAOs accumulate that the coordinator's constructor becomes unwieldy.

**Real bug found writing this task's own tests, not by inspection:** the FK constraint from `raw_track_point.captureId` to `trip_capture.id` (`ON DELETE CASCADE`, from FND-003) is enforced by Room/SQLite even in the Robolectric in-memory database — inserting a point for a nonexistent `captureId` throws a real `SQLiteConstraintException`, not a simulated one. Used deliberately as the persistence-failure test's trigger instead of mocking a DAO failure.

**Verified:** `./gradlew assembleDebug testDebugUnitTest`: 48/48 tests pass (up from 42). New coverage: ordering with colliding/out-of-order timestamps, nullable-field fidelity (no coercion to zero), resume-after-restart sequencing, the persistence-failure diagnostic path, and two service-level tests (`ACTION_START` triggers recording; null-intent rehydration with an ACTIVE capture resumes it instead of stopping).

**On-device verification (Honor DNY-NX9, Android 16/API 36, once the phone was reconnected):** installed the updated APK, confirmed permissions survived the reinstall, confirmed location services were on (`location_mode=3`, high accuracy). Foregrounded `MainActivity` then triggered `ACTION_START` via `adb shell run-as ... am start-foreground-service` (same non-exported-service technique as TRK-001). `dumpsys location` showed the real `LocationRequest` reaching the OS with the exact configured parameters (`Request[@+2s0ms HIGH_ACCURACY, minUpdateInterval=0]`), and 19 real fixes landed in `raw_track_point` within ~35s with `sequenceNumber` 0..18 strictly increasing, `provider="fused"`/later real GPS fixes, correct nullable-field behavior observed live (no `bearingDeg` while the phone sat still; `altitudeMslM` null because this device's GNSS fixes didn't include MSL data for that run — F0.5 §14.2's documented possibility, not a bug), and `isMock=0` confirming the `SDK_INT >= 31` branch of the mock-provider check runs correctly on real API 36 hardware. Re-triggering `ACTION_START` produced **no** second `gps provider +registration` entry in `dumpsys location` — `ensureLocationRecording`'s dedup guard holds on real hardware, not just in the Robolectric test. Force-stopping the app (simulating process death) and restarting with a null-action intent produced a **new** registration under a fresh Service instance (confirming `rehydrateOrStop()` actually resumes recording, not just stays foreground) and the raw points continued at `sequenceNumber` 61 → 81 with zero collision or restart-at-0 — the exact resume-from-`MAX(sequenceNumber)+1` behavior the unit test asserts, now confirmed end-to-end on hardware. Cleaned up with `adb shell am force-stop` afterward.

---

### NOT-001 — Active trip notification controls

**Objective:** persistent notification exposes Pause/Resume/Finish with safe idempotent commands.

**Constraints:** ADR-004, ADR-015.  
**Implements:** FR-NOT-001..004, NFR-SAFE-002.

**Acceptance:** actions work with UI backgrounded; duplicate intents do not corrupt state.

**Status: Done (2026-09-18), on-device verification pending.** F0.9 §7's exact wireframes: TRACKING shows "● Viaje en curso · 38.4 km · 47 min" with `[ Pausar ] [ Finalizar ]`; MANUAL_PAUSED shows "Ⅱ Viaje pausado · 42 min" with `[ Reanudar ] [ Finalizar ]`. `TrackingNotificationController.buildTrackingNotification`/`buildPausedTrackingNotification` now build both variants with real `NotificationCompat.Action`s targeting `TrackingForegroundService.createPauseIntent`/`createResumeIntent`/`createFinishIntent` - the exact same intents the in-app UI already fires, so ADR-015's idempotency was inherited for free rather than re-implemented: a duplicate notification-action tap is exactly as safe as a duplicate in-app tap already is. Actions use `PendingIntent.getForegroundService` (not `getService`) per Android's own documented requirement for a notification action that itself needs to call `startForeground` shortly after firing.

**Live figures via a new `TrackingSessionCoordinator.currentTrackingSnapshot(captureId)`** (distance/elapsed/paused, on-demand rather than a subscribed Flow - the notification only needs "right now" at a few specific moments) - reuses `liveDistanceMeters`, the same non-authoritative figure Home/Active Trip already show. The Service refreshes the notification immediately on Start/Pause/Resume/rehydrate (state transitions, not worth waiting for) plus a 30s ticker otherwise (an ADR-018-style placeholder cadence, not validated against real battery/UX data) - a deliberate choice over recomputing on every ~2s location sample, which would rescan the capture's whole raw-point history far more often than the text visibly needs to change.

**F0.9 §7's "tocar el cuerpo abre TRP-01"** is implemented via `setContentIntent` + a new `MainActivity.EXTRA_OPEN_ACTIVE_TRIP`, read once at `onCreate` to seed `AppNavHost`'s initial back stack as `[Home, ActiveTrip]` (Home stays underneath so Back lands there instead of exiting the app). **Deliberately scoped down**: this only takes effect on a cold/recreated launch - if the app is already running in the foreground on a different screen, Android just brings the existing task forward without forcing this navigation (no `onNewIntent`-driven back-stack push was added for that case, since F0.9 treats this as a UX nicety, not part of NOT-001's own tested Acceptance line).

**Verified:** 219/219 tests pass (up from 211) - `TrackingSessionCoordinatorTest` covers `currentTrackingSnapshot` (missing capture, live distance/elapsed while tracking, reflects an open pause), `TrackingNotificationControllerTest` covers both notification variants via Robolectric shadows (placeholder text before live data, real formatted text once known, exactly the right two actions with the right labels for each state, a content intent present on both). **Not verified live on-device this task** - the phone was not connected when this task ran (an honest gap, the same posture already accepted for `DET-006`), so the real end-to-end behavior (tapping Pause/Resume/Finish from the actual notification shade with the UI backgrounded, confirming the live text actually updates, confirming the content intent opens Active Trip) is unverified beyond what the Robolectric-shadow tests above already prove about the notification's real shape.

---

### TRK-003 — Manual pause/resume

**Objective:** manual pause explicitly owns paused state until user resumes/finishes; no silent auto-resume.

**Constraints:** ADR-007, ADR-015.  
**Implements:** FR-DET-007..009.

**Acceptance:** pause interval persisted; movement while paused can produce warning evidence but cannot auto-cancel pause.

**Status: Done (2026-09-17).** Built out of order, ahead of `DET-005` ("manual-pause warning logic"), because `DET-005`'s own forgotten-pause detection needs a real pause to warn about, and none existed - `ManualPauseIntervalEntity` was schema-only since FND-003, with `TrackingSessionCoordinator.finishCapture`'s own KDoc explicitly naming this task as the one that would close that gap. "Paused" is not a new `CaptureStatus` - it's an open `ManualPauseIntervalEntity` row (`endedAt IS NULL`), exactly as F0.7 §6.4 already documented, so every existing `CaptureStatus.ACTIVE`-based check keeps working unchanged. New `ManualPauseIntervalDao`; `TrackingSessionCoordinator.pauseCapture()`/`resumeCapture()` (new `PauseResult`/`ResumeResult` sealed results, matching `StartResult`/`FinishResult`'s shape); new `TrackingForegroundService.ACTION_PAUSE`/`ACTION_RESUME`.

**Both existing location-recording paths became pause-aware rather than being cancelled/relaunched on Pause/Resume:** `recordLocationUpdates` (TRK-002, manual captures) and `runAutoDetection`'s tracking-phase branch (AUTO-001, auto captures) each now check `manualPauseIntervalDao.findOpenByCapture` per sample and skip persistence while an open pause exists - the collector itself keeps running rather than being torn down and restarted, per F0.3 §8 ("detailed movement during the pause is excluded... missed route geometry should be treated as genuinely missing"). This was a deliberate design choice over cancelling the job: a still-live stream is exactly what `DET-005`'s forgotten-pause monitor will need to watch without a separate subscription of its own. `runAutoDetection` additionally skips the whole `CandidateStopEngine` evaluation while paused (not just persistence) - F0.3 §8's explicit "automatic stop detection should not silently close a manually paused Trip" - freezing the stop engine's own grace-period clock for the duration of the pause rather than letting it tick (DP-005: manual intent wins).

**`finishCapture` now actually does F0.10 §15.1's "cerrar pausa abierta si aplica"** (previously a documented no-op, per that method's own prior KDoc) - closing any open pause inside the same transaction with `endReason = "CAPTURE_FINISHED"`, satisfying F0.3 §8's "Finish is available while paused" without needing a separate Resume the user never issued.

**`manualPauseDurationMs` (PRC-002's own placeholder, previously a hardcoded `0`) is now a real computation**: `TripMetricsCalculator.calculate` gained an optional `pausesByCapture` parameter (defaults to empty, so every existing PRC-002 test/call site kept working unchanged) summing every closed pause's duration; `TripProcessingWorker` now fetches real pause rows per capture and passes them through. `totalDurationMs` deliberately stays the full wall-clock span unchanged - a caller wanting "riding time" computes `totalDurationMs - manualPauseDurationMs` itself.

**A real test-authoring bug found and fixed during this task, not a production bug:** an early version of the interleaved "pause mid-recording" tests drove Pause/Resume from a separately-`launch`ed coroutine timed via `delay()` against `runTest`'s virtual scheduler, racing against Room's suspend DAO calls (which run on Room's own real executor, not the virtual dispatcher) - genuinely flaky, caught by an actual failing run (not by suspicion), not "probably fine." Fixed by sequencing the Pause/Resume calls *inside* the flow builders themselves (`flow { emit(...); coordinator.pauseCapture(); emit(...) }`), which `emit`'s own backpressure makes strictly ordered with zero real/virtual-time races - confirmed stable across 5 repeated runs after the fix.

**Verified:** 158/158 tests pass (up from 143), including real Room-backed proofs (not just the pure-engine tests) that a sample landing exactly inside a pause window is skipped and one landing outside it is persisted, that Finish-while-paused closes the pause in the same transaction, and that `runAutoDetection` ignores an immediate walking-away confirm that would otherwise auto-finish while paused, only actually finishing after a real stop post-Resume. **On-device (Honor DNY-NX9, Android 16), confirmed for real**: a genuine Start → Pause → Resume → Pause → Finish-while-paused sequence against the real production database produced two real `manual_pause_interval` rows (one closed by Resume with `endReason=USER_COMMAND`, one closed by Finish with `endReason=CAPTURE_FINISHED`) and a real `trip_statistics.manualPauseDurationMs = 15539`, matching the sum of both real intervals' wall-clock durations exactly. `PRAGMA integrity_check` passed. **Not verified on-device**: the raw-point skip-while-paused behavior itself, since the test device had Location Services off and, once temporarily enabled for this session, never acquired an indoor GPS fix in the available window (restored to its original off state afterward) - this exact path is what the real Room-backed unit test above proves instead; `distanceM` in the on-device Trip was an honest `0`, matching PRC-002's own "no fabricated numbers" precedent for a fix-less capture.

---

### TRK-004 — Finish capture transaction

**Objective:** idempotently finalize capture, create/update logical Trip composition and enqueue derived processing.

**Constraints:** ADR-003, ADR-005, ADR-010, ADR-015, ADR-020.

**Acceptance:** repeated Finish is safe; no second ACTIVE capture remains; partial failure rolls back structural mutation.

**Status: Done, fully verified including on-device (2026-09-16).** Implements F0.10 §15.1's exact ordering. `TrackingSessionCoordinator.finishCapture(captureId)` wraps the whole mutating sequence — final `CaptureEvent`, `TripCaptureDao.completeActiveCapture`, a new `Trip` (`COMPLETED` — a Trip row is only ever created *at* Finish in this task's scope, so there is no "ACTIVE Trip" row to transition from), and its initial `TripPart` spanning the capture — in one `MotoTripDatabase.withTransaction { }` (REL-INV-008), so a failure partway rolls back the capture's own COMPLETED transition too (F0.10 §15.2). `TrackingForegroundService` owns what the transaction doesn't: `cancelAndJoin()`s the location-recording job *before* calling it (so `finishCapture`'s read of the last `sequenceNumber` can't race an in-flight insert), then stops itself regardless of whether there was anything to finish (a stale/duplicate `ACTION_FINISH` is not an error — F0.10 §15's "Finish sobre captura ya cerrada" case, just with nothing for the service itself to do first).

**Idempotency (REL-INV-007):** if `captureId` is no longer ACTIVE, `finishCapture` looks up the `TripPart` a prior Finish already created and returns `AlreadyFinished` with that same `tripId` — no second Trip, no second processing enqueue. Verified against a **real** concurrency case, not just sequential idempotent calls: 20 concurrent `finishCapture` calls for the same capture, each on its own coordinator instance with a real `UuidIdGenerator` (a first attempt using per-instance `FakeIdGenerator`s produced a misleading `SQLiteConstraintException`, because every fresh `FakeIdGenerator` restarts its counter at 1 — that's a flawed test, not a coordinator bug; fixed by using real UUIDs so a genuine double-Trip bug would show up as two distinct rows instead of a coincidental primary-key collision). Result: exactly one `Finished`, one `Trip` — `database.withTransaction { }` serializes concurrent callers the same way FND-003 already proved `@Transaction`-annotated DAO methods do, now verified for this different Room mechanism too rather than assumed.

**Scope boundaries decided against the domain model doc, not guessed:** (1) F0.10 §15.1's "cerrar pausa abierta si aplica" is a no-op here — no producer of `ManualPauseIntervalEntity` exists yet (`TRK-003`), so no pause can structurally be open; `TRK-003` must extend this transaction with that step when it lands. (2) "marcar processing PENDING" is satisfied by `ProcessingScheduler.enqueueTripProcessing` itself, not a new persisted status column — WorkManager already persists enqueued-work state across process death/reboot (ADR-010's own reason for using it), and neither F0.7's `Trip` nor `TripStatistics` field lists define such a column; inventing one ahead of `PRC-001` actually needing it would be exactly the kind of unrequested schema this project avoids. (3) `TripPart.startSequenceNumber`/`endSequenceNumber` stay `null` (not `0`/`0`) when zero raw points were ever recorded for the capture — F0.7 §2.5's "ausencia no es cero" applied to a real edge case (permission denied or an extremely short capture), not just the documented fields that already carry a `?`.

**New infrastructure, wired end-to-end rather than left for `PRC-001` to discover missing:** `CaptureEventDao`/`TripDao`/`TripPartDao` (F0.7 entities existed since FND-003 with no consumer); `ProcessingScheduler`/`FusedLocationGateway`-style seam + `WorkManagerProcessingScheduler` + `TripProcessingWorker` (`@HiltWorker`, `androidx.work:work-runtime-ktx` + `androidx.hilt:hilt-work` 1.4.0/WorkManager 2.11.2 per F0.8 §3). The worker's `doWork()` is an honest placeholder — it records a `PROCESSING_WORKER`/INFO `DiagnosticEvent` (`reasonCode=PRC_001_NOT_IMPLEMENTED`) and succeeds; `PRC-001` gives it the real Raw→assessment→ProcessedTrackPoint pipeline.

**Real bug found on-device, invisible to every unit test:** all 57 unit tests passed using `FakeProcessingScheduler` (by design — none of them touch real WorkManager), so a genuine integration bug only surfaced when a real Finish command ran on hardware: every `TripProcessingWorker` enqueue failed with `WM-WorkerFactory: NoSuchMethodException: ...TripProcessingWorker.<init>[Context, WorkerParameters]`. Root cause: `MotoTripApplication` implemented `Configuration.Provider` expecting WorkManager's on-demand initializer to auto-detect it and use the injected `HiltWorkerFactory` — but that initializer runs as a `ContentProvider`, created *before* `Application.onCreate()` performs Hilt's field injection, so it read an uninitialized `workerFactory` and silently fell back to WorkManager's reflection-based default factory (no crash, no visible error — the failure only showed up later, per-`WorkSpec`, when a worker actually needed constructing). Fixed per WorkManager's documented on-demand-initialization pattern: disabled the manifest's default `androidx.work.WorkManagerInitializer` `<meta-data>` entry and called `WorkManager.initialize(this, ...)` explicitly from `onCreate()` after `super.onCreate()`, once injection has actually happened. That introduced a second, Robolectric-only problem — `WorkManagerImpl`'s initialization state is a JVM static that survives across `@Test` methods in the same run, so a second test's fresh `Application.onCreate()` calling `initialize()` again crashed with `IllegalStateException` — resolved with a guard (`if (!WorkManager.isInitialized())`) that is also reasonable defensively in production (`Application.onCreate()` only ever runs once for real, but idempotent initialization is cheap and matches this project's own idempotency culture, not just a test workaround bolted on). Re-verified on-device after the fix: `WM-WorkerWrapper: Worker result SUCCESS`, and the placeholder `DiagnosticEvent` (`source=trip-processing-worker`) landed in the production database.

**On-device verification (Honor DNY-NX9, Android 16), beyond the WorkManager fix above:** `trip_capture` reached `COMPLETED` with `endSource=MANUAL`; `trip`/`trip_part` created correctly (`startSequenceNumber=0`, `endSequenceNumber=8` matching 9 real raw points recorded during that capture); `capture_event` recorded `CAPTURE_FINISHED` with the right `stateFrom`/`stateTo`; the service stopped itself (`dumpsys activity services` showed no `ServiceRecord` afterward) and the real FLP location registration was gone from `dumpsys location`.

Verified with `./gradlew assembleDebug testDebugUnitTest`: 57/57 tests pass (up from 48).

---

### PRC-001 — Point assessment + Processed Track v1

**Objective:** derive accepted/rejected point assessments and Processed Track without altering Raw Track.

**Constraints:** ADR-006, ADR-014, ADR-016.

**Acceptance:** processing is reproducible for a version; gaps are explicit; no fabricated interpolation.

**Status: Done, fully verified including on-device (2026-09-16).** `domain/processing/ProcessingEngine` — the `processing/` subpackage the domain README already reserved for this task — implements F0.5 §16's pipeline (`RAW → VALIDATE/ORDER → QUALITY CHECK → GAP DETECTION → PROCESSED TRACK`) as a pure function with zero Android dependency, passing `DomainBoundaryTest`. `TripProcessingWorker` (the placeholder TRK-004 left) now actually calls it: reads a Trip's `TripPart`s and their raw points, runs the engine, then publishes `PointAssessment`/`ProcessedTrackPoint`/`LocationGap` rows inside one short `withTransaction { }` per F0.10 §16.3's exact ordering (read+compute outside the transaction — safe here since nothing writes to a COMPLETED capture's raw points anymore; delete-then-insert inside a short transaction for a clean, idempotent republish).

**Scope deliberately narrow, decided against F0.5 §7.1 itself, not guessed:** F0.5 explicitly rejects a hard accuracy cutoff ("no existirá inicialmente una regla global como si accuracy > X → borrar punto") and defers speed-spike/teleport/staleness thresholds to F0.6 field data that doesn't exist yet. So v1 rejects only what needs **zero invented numbers** — non-monotonic `elapsedRealtimeNanos` relative to the last *accepted* point (`REJECTED_OUT_OF_ORDER` for time moving backward, `REJECTED_DUPLICATE` for an identical timestamp) — and accepts everything else, including low-accuracy fixes, exactly as ADR-006 asks (raw evidence isn't filtered away for being mediocre). A rejected point never becomes the new baseline for the next comparison, so one bad fix can't cascade into rejecting good ones after it. The one numeric threshold that does exist — a 30s gap cutoff, since F0.5 §8.3's own TRACKING hypothesis interval is 1-2s and a gap meaningfully longer than that is a loss of evidence, not jitter — is flagged explicitly as an unvalidated placeholder pending `EXP-003`, the same posture as `DetectorVersion(0)`/`LocationProfileVersion(0)`. `ProcessingVersion(0)` is PRC-001's real first algorithm, not a "nothing exists" placeholder like it was used for elsewhere.

**New DAOs** (`PointAssessmentDao`, `ProcessedTrackPointDao`, `LocationGapDao`, plus `TripPartDao.findAllByTrip`) for FND-003 entities nothing had consumed yet — same recurring pattern as TRK-002/TRK-004.

**Verified:** `ProcessingEngineTest` covers the algorithm in isolation (13 cases: first-point acceptance, ordering/duplicate rejection without baseline poisoning, gap creation/non-creation at the threshold boundary, rejected points not counting as gap evidence, multi-part continuous `orderIndex`, `TripPart` sequence-range filtering, empty input). `TripProcessingWorkerTest` proves the worker's own coordination against a real Robolectric-backed Room database via `TestListenableWorkerBuilder` — including that **re-running the worker replaces rather than duplicates** published rows. One real bug caught while writing that idempotent-republish test (not in production code): reusing a fresh `FakeIdGenerator` across the two `doWork()` calls made both runs generate the identical diagnostic-event id, since `diagnostic_event` is intentionally append-only (never deleted/replaced, unlike the regenerable derived tables) — fixed by using a real `UuidIdGenerator` for that one field in the test, the same class of fix as the earlier concurrent-Finish test fake-ID collision.

**On-device (Honor DNY-NX9):** a real Start→Finish cycle produced 3 real GPS fixes, all `ACCEPTED` (short, stationary capture — no anomalies expected), 3 matching `ProcessedTrackPoint` rows, 0 `LocationGap` rows, and the completion `DiagnosticEvent` with real counts in `metadata`. Gap detection itself wasn't exercised on hardware (no real GPS loss occurred in that short test) — covered instead by `ProcessingEngineTest`'s deterministic threshold-boundary cases, which is sufficient here since gap math has no Android-integration risk the way TRK-004's WorkManager wiring did.

Verified with `./gradlew assembleDebug testDebugUnitTest`: 71/71 tests pass (up from 57).

---

### PRC-002 — Core distance/time/speed metrics

**Objective:** calculate distance, duration, moving/stopped/paused time and speed metrics from approved data sources.

**Implements:** FR-MET-001..008/012.  
**Constraints:** ADR-006, ADR-014/016.

**Acceptance:** deterministic golden tests; max speed rejects documented spike cases; display polyline is not metric source.

**Status: Done, fully verified including on-device (2026-09-16).** `domain/processing/TripMetricsCalculator` takes `ProcessingEngine.Result` (PRC-001) directly — no second pass over raw evidence, no re-deciding what's accepted — and computes `TripStatisticsEntity` for FR-MET-001/002/005/006/007. Wired into `TripProcessingWorker` right after `ProcessingEngine.process(...)`, upserted (not delete-then-insert: `TripStatisticsEntity`'s primary key is exactly `(tripId, processingVersion)`, so there's only ever one row to replace) inside the same short transaction as PRC-001's derived tables.

**Distance:** haversine (pure Kotlin, no `android.location.Location.distanceBetween()` — ADR-013) summed between consecutive `ProcessedTrackPointEntity`s, *except* the edge landing on a `GAP_BOUNDARY` point — ADR-016/F0.5 §11.3's "a gap must not silently inflate distance" applied literally: the edge before/after a gap is fine, only the one edge spanning it is excluded.

**Max speed (FR-MET-006's "clearly invalid GPS spikes MUST NOT be presented"):** the one rejection this task makes needs zero invented numbers, matching PRC-001's own posture — a point's own reported `speedMps` is excluded from the max-speed pool if that point is a gap boundary (the exact teleport-like case F0.5 §12.3 names), full stop. No accuracy cutoff, no acceleration-plausibility check, no persistence-confirmation window — those all need F0.6 field data this project doesn't have yet. Average speed is `distanceM / totalDurationMs`, `null` only when duration is zero.

**Left deliberately `null`, not computed with a guessed threshold:** `movingDurationMs`/`stoppedDurationMs`/`averageMovingSpeedMps` — FR-MET-003/004 themselves say "when technically reliable," and any moving/stopped split needs a speed cutoff; GPS jitter means even a parked, stationary phone rarely reports exactly `0.0 m/s`, so there is no zero-threshold way to do this split (unlike the gap-boundary exclusion above). That's `DET`-family territory once `EXP-003` closes a real value. `minElevationM`/`maxElevationM`/`ascentM`/`descentM` are `PRC-003`'s job, not duplicated here. `manualPauseDurationMs` is a true `0` (not unknown) — no `ManualPauseIntervalEntity` producer exists yet (`TRK-003`), so none can structurally exist yet.

**Total duration** sums each `TripPart`'s own `(endElapsedRealtimeNanos − startElapsedRealtimeNanos)` rather than spanning first-part-start to last-part-end — correct once a Trip has multiple parts with a real break between them (future merge scenarios), and for today's always-one-part Trips it's the same number either way. Throws (loudly, not a silent 0) if a part has no `endElapsedRealtimeNanos` — that would mean metrics ran on a Trip that was never actually finished, a real invariant violation worth surfacing rather than reporting a fabricated duration.

**New:** `TripStatisticsDao` (`@Upsert`, matching the entity's actual single-row-per-version key — no delete-then-insert needed here unlike PRC-001's per-row derived tables).

**Verified:** `TripMetricsCalculatorTest` (13 cases) includes an independently-computed-expected-value haversine check (not just "doesn't crash"), the gap-boundary distance/speed exclusions, multi-part duration summation, the zero-duration→null-average-speed edge case, and the loud failure on a not-actually-finished `TripPart`. `TripProcessingWorkerTest` now also asserts a real `TripStatistics` row lands with the right `validPointCount`/`totalDurationMs`. 83/83 tests pass (up from 71).

**On-device (Honor DNY-NX9):** two separate Start→Finish cycles produced entirely honest results neither test suite could stage: an 8s indoor capture landed exactly 1 raw fix (`distanceM=0`, `maxSpeedMps=null` — correctly nothing to compare), and a 20s capture landed 2 fixes with *identical* coordinates and `speedMps=null` on both (a real, stable-but-weak indoor fix, `horizontalAccuracyM≈300`) — `distanceM=0`/`maxSpeedMps=null` again, correctly reflecting that literally no evidence of movement or speed existed, not a bug in the haversine math (that exactness is what the unit tests already prove with known coordinates). This is the concrete, real-world version of the "no fabrication" principle this task is built around.

---

### PRC-003 — Elevation baseline

**Objective:** provide elevation range and quality-aware provisional gain/loss processing without pretending the deferred algorithm is final.

**Implements:** FR-MET-009/010.  
**Constraints:** ADR-006, ADR-014.

**Acceptance:** algorithm/version is explicit; noisy/unsupported altitude can degrade gracefully; reopen trigger documented.

**Status: Done (2026-09-18).** `gps-location-research.md` §14.3/GPS-011 explicitly rules out summing raw altitude deltas ("genera sobreconteo por ruido vertical") and names hysteresis/minimum-elevation-change and vertical-accuracy filtering as the candidate methods F0.6 should validate - not a blank "wait for research" (FR-MET-010's own text: "SHOULD calculate... only if F0 research establishes a sufficiently reliable method," not "MUST NOT calculate until then"). New `domain/processing/ElevationCalculator` (pure, stateless, directly testable like `CandidateStopEngine`) implements exactly those two candidate methods as a documented placeholder: a running hysteresis baseline that only credits ascent/descent once a delta clears `ElevationProfile.minElevationChangeM` (3m default), discarding a sample only when its own reported vertical accuracy is *known* and worse than `maxVerticalAccuracyM` (20m default) - a device that never reports vertical accuracy isn't punished harder than one honest enough to report poor accuracy. Prefers MSL altitude over WGS84 ellipsoid per GPS-010, falling back when MSL is unavailable.

**Graceful degradation, split at the field-list level, not all-or-nothing**: F0.7 §9.3's own rule ("elevation gain/loss solo se rellena si el algoritmo vigente se considera suficientemente fiable") gates only ascent/descent, not the simple range - `minElevationM`/`maxElevationM` fill as soon as *any* valid sample exists, while `ascentM`/`descentM` additionally require `ElevationProfile.minReliableSampleCount` (3 default) valid samples before being filled at all, degrading to an honest `null` otherwise rather than a number computed from a statistically meaningless sequence. A gap-boundary sample mirrors `TripMetricsCalculator`'s own existing distance/speed posture (ADR-016/F0.5 §11.3): it never credits a hysteresis delta across the edge landing on it, but still resets the baseline afterward and still counts toward the range and the reliable-sample gate.

**A real requirements-vs-schema gap, noted rather than silently resolved either way**: FR-MET-009's text also asks for "starting elevation" and "ending elevation," but F0.7's own frozen `TripStatistics` field list (§9.3, matching the actual `TripStatisticsEntity` schema) only has `minElevationM`/`maxElevationM` - no start/end columns exist. Adding them would be a schema change outside this task's scope (a "baseline," not a migration); the gap is recorded here rather than either inventing new columns unasked or quietly dropping the requirement's own wording.

**`ElevationProfile` is deliberately not a Hilt-injected constructor parameter** on `TripMetricsCalculator` (which is itself real `@Inject constructor()`-managed, unlike the pure detection engines): Dagger resolves every `@Inject constructor` parameter through the dependency graph and does not honor Kotlin default values, so a second constructor parameter would need its own `@Provides` binding for no real benefit. Held as a private property instead, matching how the detection engines' own profile classes are never Hilt-injected either.

**Verified:** 211/211 tests pass (up from 200) - `ElevationCalculatorTest`'s isolated algorithm coverage (empty input, too-few-samples-for-gain/loss, steady climb, steady descent, in-band oscillation never accumulating, a real climb-then-descent crediting both directions from their own confirmed baseline, a gap boundary not crediting a delta across its own edge, missing samples skipped rather than treated as zero) plus `TripMetricsCalculatorTest` integration cases (MSL preferred, ellipsoid fallback, poor-vertical-accuracy exclusion). **Confirmed live on-device (Honor DNY-NX9)**: a real Start→Finish cycle exercised the updated `TripProcessingWorker`/`TripMetricsCalculator` path end-to-end with no crash (`WM-WorkerWrapper: Worker result SUCCESS`), and Trip Detail correctly kept showing an honest "—" for elevation on a stationary indoor trip with no real altitude signal. **Not verified live**: genuine non-zero ascent/descent numbers, which need a real ride with actual elevation change - an honest gap, the same posture already accepted for `DET-005`/`DET-007`'s own motion-dependent claims, not a fabricated pass.

**An unrelated, real HIS-001 gap found and fixed during this task's own on-device check**: tapping a trip row in Home's "Recent" list did nothing - `ux-navigation.md` §4's own navigation hierarchy diagram explicitly lists "Trip reciente -> HIS-02", but `HIS-001` only wired that tap from the History tab, not from Home. Fixed by adding the same `onClick`/`clickable` pattern History's own rows already use; confirmed on-device that tapping "Coastal loop" from Home now opens its real Trip Detail.

---

### UI-001 — App shell, Home and Active Trip

**Objective:** implement F0.9 primary navigation and active-trip-first Home behavior.

**Constraints:** ADR-011.  
**Implements:** NFR-SAFE-001..003.

**Acceptance:** Home/History/Favorites shell; Active Trip is special destination/state, not a fourth permanent tab; predictive back behavior validated.

**Status: Done (2026-09-18).** First UI built on this codebase - everything before this task was headless. `navigation/AppNavHost` implements ADR-011 literally: a plain `mutableStateListOf<Destination>` back stack (Navigation 3, `androidx.navigation3:navigation3-runtime`/`navigation3-ui` 1.1.7), not the saveable/`NavKey`-based flavor - F0.8 §14's "the back stack is not the source of truth for an active Trip" means process death must rehydrate from Room (`TrackingSessionCoordinator`'s existing job), never from a restored stack, so there's nothing to gain from making it saveable and a real invariant to violate if it were. `Destination.ActiveTrip` is exactly what F0.9 calls it - "a persistent special destination, not a tab": `MainTabScaffold`'s bottom nav wraps only Home/History/Favorites; Settings and Active Trip get their own plain top bars and are pushed onto the stack rather than being nav-bar items.

**HOME-01/TRP-01 built reactively, no repository layer** (matching every other class in this codebase, `TrackingSessionCoordinator` included): `HomeViewModel`/`ActiveTripViewModel` inject Room DAOs directly. `HomeViewModel.activeTripFlow` observes the ACTIVE `TripCaptureDao` row via `flatMapLatest`, `combine`s its raw points/open-pause with a 1s ticker for a live, non-authoritative duration/distance readout (`domain/LiveDistance.liveDistanceMeters` - simple consecutive-haversine summation over an in-progress capture, explicitly distinct from PRC-001/PRC-002's authoritative post-Finish figure). Commands (Start/Pause/Resume/Finish) go through `context.startForegroundService(...)`, never straight to the coordinator (ADR-004: the Service owns the Android-facing lifecycle) - the UI naturally recomposes once that command's effect lands in Room, no synchronous return-value plumbing needed.

**A real compileSdk-37 dependency wall, same root cause as the already-documented Compose BOM downgrade, hit again one layer up:** `androidx.hilt:hilt-navigation-compose` 1.4.0 (needed for `hiltViewModel()`) transitively pulls `lifecycle-{runtime,viewmodel}-compose:2.11.0` and a new `hilt-lifecycle-viewmodel-compose:1.4.0`, all reporting `minCompileSdk=37` (confirmed by downloading and inspecting each AAR's own metadata, not guessed from a changelog) - incompatible with this project's compileSdk-36 pin (itself already a deviation, documented in `gradle/libs.versions.toml`, since SDK Platform 37 isn't installed locally). Letting the Compose BOM manage `lifecycle-runtime-compose` alone did not avoid it in practice (tried, still resolved 2.11.0). Fixed by pinning `hilt-navigation-compose` to 1.3.0 (`minCompileSdk=35`, pulls compatible 2.9.1/2.10.0 transitively) and explicitly pinning `lifecycle-runtime-compose`/`lifecycle-viewmodel-ktx` to 2.10.0 - verified via a full `:app:dependencies` resolution and a real `assembleDebug` (two earlier attempts failed with explicit compileSdk-37 errors before the fix).

**A real reactivity bug found live on-device, not by inspection, and fixed with a regression test:** the first `recentTripsFlow` implementation did `tripDao.observeRecent(...).mapLatest { trips -> trips.map { tripStatisticsDao.findByTripAndVersion(...) } }` - a one-shot suspend read nested inside a transform of the *trip* table's own Flow. Room only invalidates a query's Flow when a table **it reads from** changes, so inserting into `trip_statistics` (from `TripProcessingWorker`, seconds after Finish) never re-triggered `observeRecent`, and a freshly-finished trip showed "-/-" forever on Home, only correcting itself once some *other* trip's Finish touched the `trip` table again. Caught by watching the real Home screen after a real on-device Finish (logcat confirmed `TripProcessingWorker` actually succeeded in 73ms; the UI still showed no numbers 15+ seconds later). Fixed by adding `TripStatisticsDao.observeByTripAndVersion` and combining one Flow per visible trip instead of a suspend fetch; re-verified live on-device (a Start→Finish cycle now updates Home's stats within ~1s, no app restart needed) and captured as `HomeViewModelTest.recentTripsReflectsStatisticsInsertedAfterTheTripAlreadyExists` - confirmed to actually fail (timeout) against the original code before being left in place as a passing regression test, not written and trusted blindly.

**Deferred, honestly, to their own not-yet-built tasks:** History/Favorites/Settings are real, navigable destinations wired into the shell but are minimal "coming soon" placeholders - `HIS-001`/`FAV-001`/the `SET-` family own the real screens. `MAP-001` isn't built, so Active Trip's route area is an explicit "Map not available yet" card, never a fake map. Notification content/behavior is `NOT-001`'s job, not touched here beyond the existing tracking notification.

**Verified:** 177/177 tests pass (up from 176), including the new `HomeViewModelTest` regression test. **On-device (Honor DNY-NX9, Android 16), confirmed for real** via screenshots and precise tap-coordinate verification (pixel-scanned from real screencaps, not guessed): Home's idle state (correct MANUAL-mode readiness text, real historical trips in Recent), Home's active state with a live-ticking duration, Active Trip's tracking and paused states (Pause/Resume both actually call the real `TrackingSessionCoordinator` commands), the Finish confirmation dialog, and a full Start→Finish cycle correctly returning to Home with the new trip's real (honestly-zero, no GPS fix available) distance/duration appearing live. History, Favorites, and Settings placeholders all render and navigate correctly, including Settings' own back button.

---

### HIS-001 — History + Trip Detail

**Objective:** display persistent Trips and a detail view that remains useful without map availability.

**Implements:** FR-HIS-001..004, FR-HIS-008, FR-MET-001..012.

**Acceptance:** chronological history; rename; metrics and route summary available offline; map failure does not hide Trip data.

**Status: Done (2026-09-18).** F0.9 §8/§9's HIS-01/HIS-02, replacing `UI-001`'s placeholder History tab. New `feature/history/HistoryScreen`/`HistoryViewModel` show the *full* chronological list (unbounded, unlike Home's own 3-row "Recientes" preview) with a newest/oldest-first toggle (`FR-HIS-008` - the one sort order beyond default chronology this task implements, not the fuller filter/search/tag set F0.9 §8.3 marks P1). New `feature/tripdetail/TripDetailScreen`/`TripDetailViewModel` - the first parameterized Nav3 destination in this graph (`Destination.TripDetail(tripId)`, a `data class` alongside the existing `data object` singletons) - shows every `TripStatisticsEntity` field HIS-02 §9.2 asks for (distance, duration, moving/stopped/pause time, max/avg/avg-moving speed, elevation range/ascent/descent), each rendering an honest "—" rather than a fabricated number wherever the underlying pipeline hasn't computed it yet (elevation is always "—" today - `PRC-003` isn't built). `TripDao` gained `observeAllDescending`/`observeAllAscending`/`observeById`/`rename` on top of `UI-001`'s own `observeRecent`.

**Wrote the reactive statistics join correctly the first time, rather than repeating a bug already found and fixed once**: `DET-007`'s own report documents `UI-001` originally shipping `recentTripsFlow` as a one-shot suspend read of `trip_statistics` nested inside a `mapLatest` over the `trip` table's own Flow - invisible to Room's invalidation tracker, so a freshly-processed Trip's stats never appeared without an unrelated write elsewhere. `HistoryViewModel.uiState` and `TripDetailViewModel.uiState` both combine a real `Flow` per trip's statistics (`TripStatisticsDao.observeByTripAndVersion`, the same method added to fix that bug) instead, and each has its own regression test proving it - not because either screen manifested the bug during manual testing this time, but because the underlying design mistake was proven capable of shipping silently once already.

**Favorite is shown, not built**: `Trip.isFavorite` has existed since `FND-003` with no consumer until now - both the History row and Trip Detail's header show a read-only star when set (F0.9 §8.1's own "favorite indicator" bullet). Tapping it to actually toggle favorite status is `FR-FAV-001`'s job, a separate requirement from this task's own `Implements` line - deliberately not added here to avoid scope creep into a different task's acceptance criteria. Overflow actions HIS-02 §9.4 lists (Split, Delete, Export, Diagnostics) aren't included for the same reason - none of those tasks exist yet, and stubbing disabled menu items would misrepresent unbuilt features as present.

**Rename (`FR-HIS-004`)** reuses the same `AlertDialog` + `OutlinedTextField` pattern `ActiveTripScreen`'s Finish confirmation established; a blank name reverts to the generated fallback (F0.9 §8.2) rather than persisting an empty string, since `Trip.name` is nullable precisely so "no custom name" has one representation, not two.

**Verified:** 200/200 tests pass (up from 188) - DAO-level ordering/filtering/rename tests, and ViewModel-level regression tests for both screens' reactive statistics joins (including one proving a fresh stats write is picked up live, matching `DET-007`'s own fix). **Confirmed live on-device (Honor DNY-NX9)** against the two real GPS rides from `DET-007`'s field session: History listed all 6 real trips with correct distance/duration, the sort toggle correctly reversed the order, Trip Detail for the 5.38km ride showed the exact real figures (98 km/h max, 9 km/h average, honest "—" for moving/stopped/elevation), and renaming it to "Coastal loop" persisted and reactively updated both History and Home's own preview row without restarting the app. **An unrelated incident during this verification, corrected here for accuracy**: an automated tap near the status bar landed on an incoming WhatsApp heads-up notification instead of the app's edit icon (a real interference between on-device UI automation and the device's own notifications), and around the same time a real phone call appeared active on the device - the user later clarified they placed that call themselves, on the phone directly, coincidentally during this same verification window; it was not something the automated tap caused. What genuinely is a lesson for future on-device automation: a heads-up notification can intercept a tap meant for the app's own top bar, so a screenshot immediately before any tap near the status bar area is now this session's practice.

---

### MAP-001 — Map provider decision + isolated renderer

**Type:** spike + implementation after decision.

**Objective:** choose a provider based on documented constraints, record ADR if needed, and implement the provider behind a renderer abstraction.

**Constraints:** ADR-019.

**Acceptance:** provider decision is explicit; recording has no dependency on map tiles/network; large-track strategy has tests/benchmark path.

**Status: Done (2026-09-18).** The spike half: real research, not a guessed pick. `ADR-019` deferred the provider until licensing/cost/Compose-support/offline-behavior could be evaluated - this task did that evaluation for real (live Maven Central listing, MapLibre's own GitHub releases, OpenFreeMap's own site read directly) and recorded it as **`ADR-021`**: MapLibre Native (`org.maplibre.gl:android-sdk:13.6.1`, BSD-2-Clause, verified on Maven Central) rendering OpenFreeMap's public vector tiles (`https://tiles.openfreemap.org/styles/liberty` - no API key, no billing, commercial use allowed, confirmed from OpenFreeMap's own FAQ). Google Maps and Mapbox were both rejected for requiring a billing account/API key even within a free tier - this project already rejected that exact dependency once (F0.5, Google Elevation API) for a Core feature; osmdroid was rejected for depending on OpenStreetMap's own raw tile servers at production scale, which OSM's own usage policy discourages. `ADR-019` itself is unchanged (only its "provider TBD" half is resolved, per its own reopen-trigger wording - a dated clarifying note was added there rather than a blanket supersession).

**The isolation half**: `feature/map/TripRouteMap.kt` is the only file in this codebase allowed to import `org.maplibre.*` - `domain`/`tracking`/`core` never see a map SDK type, only a plain `List<GeoPoint>` crosses the boundary (`ADR-019`'s "presentation adapter", made concrete). No official Compose artifact exists for MapLibre Native's Android SDK (verified against its own API docs - it's a classic View-based `MapView`), so the bridge is Compose's own standard `AndroidView` interop, not a workaround. Scoped to `FR-MAP-001`'s literal wording ("a **completed** Trip MUST be viewable") - Trip Detail's map reads `ProcessedTrackPointEntity` (a post-Finish, `ADR-006`-derived artifact); a live map *during* an active trip is a distinct, harder problem (no Processed Track exists yet for an in-progress capture) and stays out of scope, `ActiveTripScreen`'s own placeholder unchanged.

**Large-track strategy (`FR-MAP-003`)**: new `domain/RouteSimplifier.kt` implements Ramer-Douglas-Peucker with an 8m tolerance placeholder (ADR-018 posture, not field-validated) - `ux-navigation.md` §19's own wording ("la ruta debe poder verse aunque tenga miles de puntos mediante representación optimizada") asks for exactly this, not a fixed max-point decimation that would need to guess a count. A real benchmark test (10,000 synthetic points shaped like a real road - long straight stretches with periodic turns, not adversarial worst-case noise) asserts both a time bound (<2s) and a substantial size reduction, run as an ordinary part of the JVM test suite rather than a separate tool.

**A real bug found and fixed via on-device testing, not caught by any unit test**: navigating from one Trip's detail to a different Trip's detail kept showing the *first* Trip's route on the map, even though the header/metrics above it correctly updated to the new Trip - `AndroidView`'s `factory` only ran once for as long as the underlying `MapView` (and the composable's `remember` scope holding it) survived across that navigation, and the original code only reconfigured the map's route inside that one-time `factory` call. Fixed by moving route configuration into a `LaunchedEffect(points, map)` that reruns whenever the points themselves change, regardless of whether the underlying `MapView` happens to be reused across navigations - the correct reactive behavior for a composable taking `points` as a parameter either way. Confirmed fixed by repeating the exact navigation sequence that first revealed it.

**Verified:** existing 233 tests still pass; `RouteSimplifier` itself has 7 new tests (empty/single/two-point edge cases, a straight line collapsing to its endpoints, GPS-jitter-scale noise dropped, a real corner surviving, first/last points never dropped, the 10k-point benchmark). **No JVM/Robolectric test covers `TripRouteMap` itself** - an honest gap, not an oversight: it needs a real GL-capable Android environment MapLibre Native can render into, which Robolectric doesn't provide; **confirmed instead entirely on a real device** (Honor DNY-NX9): opened the real "Coastal loop" trip's already-persisted `ProcessedTrackPoint` data (from `PRC-003`'s field session, recorded before this task existed) and saw real OpenFreeMap vector tiles, the actual GPS route as a purple polyline, distinguishable green/red start/end markers, and a working "fit route" button - then found and fixed the stale-route bug above by navigating to a second, much shorter real trip and confirming its own (correctly tiny) route rendered instead.


---

### REC-001 — Same-boot process recovery

**Objective:** recover an active capture after process death within the same boot using persisted state and explicit gaps.

**Constraints:** ADR-003, ADR-004, ADR-015/016/020.

**Acceptance:** process recreation does not duplicate ACTIVE capture; evidence records gap/recovery; Raw Track remains intact.

**Status: Done (2026-09-18), confirmed live on a real process kill.** F0.10 §7.1's checklist was already half-built: `TRK-001`'s sticky-restart rehydration already found the existing `ACTIVE` capture and resumed recording into it (checklist items 1/4/5/8). This task closed the two real gaps F0.10 itself calls out - item 6 ("registrar `PROCESS_RECOVERED`") and item 2 ("comprobar que `elapsedRealtime` actual es compatible con los valores persistidos"), the second one turning out to be the harder, more consequential half: F0.10 §10.1's own discontinuity rule ("si el valor monotónico actual es menor/incompatible... se considera que ocurrió un reboot") is directly checkable (`clock.elapsedRealtimeNanos() < capture.startElapsedRealtimeNanos`), and §10.2 is explicit that a reboot must **not** be treated as an ordinary same-boot resume: "no asumir que el viaje continuó durante el reboot" - the capture must instead be closed as `ABORTED` using the *last persisted evidence*, never an invented "now".

**New `TrackingSessionCoordinator.recoverActiveCaptureIfAny()`** replaces the plain `findActiveCapture()` call `rehydrateOrStop()` used to make on every restart, returning one of three outcomes: `NoActiveCapture` (nothing to do), `Resumed` (same-boot process death - logs `PROCESS_RECOVERED`/`RECOVERY_SYSTEM`/INFO, then resumes exactly as before), or `AbortedAfterReboot` (closes the capture via the existing `completeActiveCapture` with `status=ABORTED`/`endSource=RECOVERY`, using the capture's last raw point's own timestamp - or the capture's own start, if literally zero points were ever recorded - and logs `CAPTURE_ABORTED_AFTER_REBOOT`/WARN with `reasonCode=ELAPSED_REALTIME_DISCONTINUITY`). `TrackingForegroundService.rehydrateOrStop` now branches on this instead of unconditionally resuming.

**Deliberately not built, per F0.10 §22's own wording ("una captura `ABORTED` **puede** producir un Trip visible parcial" - may, not must)**: an aborted capture does not get its own visible partial Trip in History. Raw evidence stays fully intact (nothing here ever deletes or rewrites a `RawTrackPoint`) and the capture's own `ABORTED` status/`endSource=RECOVERY` is real, queryable evidence, but it isn't surfaced in the UI - that's explicitly optional in F0.10's own text and outside this task's stated Acceptance line. Similarly, F0.10 §7.1 item 3 ("verificar permisos/capabilities actuales") wasn't added as an explicit gate - `LocationGateway` already silently produces no samples if permission is missing, an existing soft degradation this task didn't need to make more explicit to satisfy its own Acceptance criteria.

**"Evidence records gap" needed no new gap-creation code at all**: `ProcessingEngine`'s existing generic time-gap detection (`PRC-001`, 30s threshold) already creates a `LocationGapEntity` for *any* large delay between consecutive accepted raw points, process-death-caused or not - confirmed by reasoning through the existing code, not assumed. F0.10 §8's own warning ("no atribuir automáticamente todos los gaps a 'GPS malo' cuando el proceso estuvo muerto") is *not* fully addressed - `ProcessingEngine` has no way to know a given gap correlates with a `PROCESS_RECOVERED` event, so its `reasonCode` still always reads `GAP_NO_FIX` even for a process-death gap. Recorded here as a real, honest limitation rather than silently accepted or oversold as fixed - correlating the two would need `ProcessingEngine` to cross-reference `DiagnosticEvent` rows, a bigger change than this task's own scope.

**Verified:** 225/225 tests pass (up from 219) - coordinator-level tests for all three `RecoveryOutcome`s (including the zero-raw-points fallback and a regression guard proving an aborted capture never flips back to `ACTIVE`), plus a `TrackingForegroundServiceTest` proving the reboot case stops the service instead of resuming it. **Confirmed live on a real device (Honor DNY-NX9) with a genuine process kill, not just Robolectric**: started a real trip, found its real PID via `dumpsys`, killed it with `kill -9` (not `force-stop` - a real, unmanaged process death), waited for Android's own sticky restart to bring the service back under a new PID, then confirmed directly from the pulled production database: a real `PROCESS_RECOVERED`/`RECOVERY_SYSTEM` event was logged, the capture stayed `ACTIVE` (never duplicated), and location recording resumed (39 raw points across the restart). Finishing the trip afterward produced a normal, correct Trip in History. The kill-to-restart window this produced (~13s) fell under `ProcessingEngine`'s existing 30s gap threshold, so no `LocationGap` was created for it - expected given that threshold, not a failure, and the gap-creation mechanism itself is already proven separately by `PRC-001`'s own tests. **Not verified live**: the reboot/`AbortedAfterReboot` path, which would need a genuine device reboot - deliberately not attempted given how disruptive/slow that is against the user's actual phone; proven instead by the coordinator/service-level tests above.

---

## W2 — Automatic Detection & Field Freeze

### DET-001 — Activity Recognition transitions
**Objective:** register/restore Transition API signals as passive detector input.  
**Constraints:** ADR-007/008.  
**Acceptance:** IN_VEHICLE is evidence only, not motorcycle proof; registration restored after supported boot/update paths.

**Status: Done, on-device verification partially inconclusive by design (2026-09-16).** `ActivityRecognitionRegistrar` (`tracking/activityrecognition/`) registers the Transition API for F0.4 §4.1's 6-type vocabulary (both ENTER/EXIT — 12 registrations) via a `PendingIntent` targeting `ActivityTransitionReceiver` (`tracking/receiver/`, matching F0.8 §15's planned location for receivers) — a `PendingIntent`, not a live callback, is what lets registration keep delivering transitions while this app's process isn't running at all, which is the entire point of ADR-007's "passive trigger". `BootReceiver` calls the same idempotent `register()` after `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` (F0.4 §4.5/AND-007: Google documents registration doesn't survive either), and `MotoTripApplication.onCreate()` calls it too for the normal first-run/launch case.

**Scope, held to deliberately (this task's own acceptance criterion):** `IN_VEHICLE is evidence only, not motorcycle proof` — this task does not build any Candidate Start/Stop decision logic. Every received transition is persisted as an `ACTIVITY_RECOGNITION`/INFO `DiagnosticEvent` (category DIA-001 already reserved) via `ActivityTransitionRecorder`, with `captureId`/`tripId` both `null` (passive monitoring has neither yet) — a durable, queryable trail for `DET-002`/`DET-003` to consume later, not a decision.

**Wall-clock reconstruction:** `ActivityTransitionEvent` carries only `elapsedRealtimeNanos`, no wall-clock field, but `ActivityTransitionSample.wallTimeEpochMs` (TST-001) is non-optional. Reconstructed from one shared wall/elapsed "now" pair read once per broadcast (not re-read per event, so multiple transitions in the same batch share a consistent baseline) — assumes no reboot occurred between the event and now, true within a boot session per F0.5 §6.1.

**Package-layout correction, found while researching this task's own placement:** F0.8 §15's original repository sketch names `tracking/receiver/` for exactly this kind of class (confirmed here) and `worker/` for `TripProcessingWorker` — the latter had silently ended up in `tracking/processing/` since TRK-004 without anyone noticing the mismatch against `worker/README.md`'s own stated plan. Flagged as a separate cleanup task rather than folded into this one.

**Verified:** `mapActivityType`/`mapTransitionType`/`reconstructWallTimeEpochMs` and `ActivityRecognitionRegistrar.buildTransitions()` (all 12 GMS-constant combinations, real API objects, no mocking needed) are pure-function unit tests. `ActivityTransitionRecorder` is tested against a real Robolectric-backed Room database, including that no captureId/tripId gets fabricated and that missing confidence stays an empty map rather than a fake value. 94/94 tests pass (up from 83).

**Honest limits of both the unit tests and the on-device pass — not glossed over:** `ActivityTransitionResult`'s real wire format is Play-Services-internal, not public API, so it can't be faithfully constructed in a JVM test the way `TrackingForegroundServiceTest` constructs real `Intent`s — unlike `FusedLocationGateway`, whose real callback shape TRK-002 could at least trigger for real via `run-as am start-foreground-service`, there is no adb-level equivalent for delivering a fabricated Activity Transition broadcast. On real hardware (Honor DNY-NX9, Magic OS): confirmed `BootReceiver` is correctly wired into the manifest resolver table for both actions; confirmed `MotoTripApplication`'s own `register()` call at normal launch produces no crash and no registration-failure log (the best available evidence for that path, since AR registration state isn't independently inspectable the way `dumpsys location` inspects FLP). What was **not** confirmed: neither a genuine `MY_PACKAGE_REPLACED` (triggered via a real `adb install -r`, not a spoofed broadcast — the OS refuses to let even `adb shell` send that protected action directly) nor a real physical-movement-triggered transition were observed actually reaching this app's process within a reasonable wait — no new process was ever started for the broadcast, and the app has no doze-whitelist exemption. This matches F0.4 §16's own already-accepted risk ("fabricantes pueden aplicar políticas adicionales de batería/proceso") to the letter: design correctly against the official API (done), measure on real hardware (done, now on record for this specific OEM/model), and do **not** reach for a manufacturer-specific workaround from a single data point — F0.4 §16 explicitly defers that to actual evidence of need. Re-check with a real reboot and real physical movement once the device is available for a longer session, or once `EXP-002`'s field campaign is underway.

---

### DET-002 — Candidate Start engine
**Objective:** implement F0.3 candidate-start state/evidence evaluation.  
**Constraints:** ADR-007/013/018.  
**Acceptance:** deterministic replay tests; no single sample starts a Trip by itself.

**Status: Done (2026-09-16).** `domain/detection/CandidateStartEngine` — the `detection/` subpackage the domain README already reserved for this task — is a pure, stateful reducer over a merged `DetectionEvent` stream (DET-001's `ActivityTransitionSample` + TRK-002's `LocationSample`, plus a synthetic `TimeTick` so a stalled candidate can still time out with no new sample ever arriving). `IN_VEHICLE` ENTER (ADR-007's passive trigger) only *opens* a candidate; confirming it (DP-001/DP-002) needs both sustained time **and** real straight-line displacement from the candidate's anchor point (the first location fix received after opening) — activity evidence alone, or a single GPS jump alone, can never confirm (SCN-015, SCN-025). Displacement is anchor-to-current, not summed path distance, specifically so ordinary jitter while essentially stationary can't accumulate into a false confirmation.

**Placeholder thresholds (ADR-018 — not frozen until `EXP-008`/G4), passed as a constructor `CandidateStartProfile` rather than hardcoded, per F0.12 §4/TST-UNIT-002's explicit "must be testable with injected profiles":** 15s minimum confirmation duration, 40m minimum displacement, 120s maximum candidate window. The 40m/15s pairing is sized against F0.3 §6's own "slow parking-lot departure shouldn't need unrealistically high speed" requirement: at ~10 km/h (2.8 m/s) — a genuinely slow departure — 15s covers ~42m, just past the threshold; a normal urban departure clears it several times over. Neither number has field validation yet.

**No live caller yet, deliberately — the same posture `CAP-001`'s `CapabilityResolver` had before `AUTO-001` existed to consume it:** turning a `Confirmed` decision into an actual auto-started capture (via `TrackingSessionCoordinator`), gated by `CapabilityResolver`'s mode, is `AUTO-001`'s job. This engine also assumes its future caller only routes events to it while the system is actually IDLE (F0.3 §4: CANDIDATE_START is only reachable from IDLE) — it has no way to know a real capture is already active elsewhere, and giving it that knowledge would mean depending on `TripCaptureDao` for no real benefit, breaking ADR-013's boundary. Because nothing calls this yet, there is nothing to verify on-device for this task — unlike every `tracking/`-package task so far, this is pure domain logic verified entirely by deterministic tests, matching ADR-018's own design ("lógica crítica se prueba con replay... los thresholds no se congelan hasta el gate físico").

**Extracted `domain/GeoMath.kt`** (`haversineMeters`) rather than duplicating PRC-001's `TripMetricsCalculator`'s copy of the exact same formula — a real, immediate duplication once this task needed the same distance math, not a speculative shared-utility refactor.

**Verified:** 16 new deterministic tests (13 for the engine's own state transitions — every scenario TST-UNIT-002 names: remains idle, opens a candidate, confirms, abandons via exit, abandons via timeout with and without further samples, duplicate/repeated inputs, reopening after abandon/confirm — plus 3 for `GeoMath` including a value cross-checked against an independently-written second implementation of the same formula, not just "doesn't crash"). 110/110 tests pass (up from 94).

---

### DET-003 — Candidate Stop engine
**Objective:** implement candidate-stop evidence and delayed finalization logic.  
**Acceptance:** traffic lights/short stops do not end normal Trip fixtures.

**Status: Done (2026-09-17).** `domain/detection/CandidateStopEngine` mirrors `CandidateStartEngine`'s design with the trigger reversed: `IN_VEHICLE` EXIT (not ENTER) opens a candidate. Deliberately leans on Google's own Transition API filtering (F0.4 §4.2: transient `STILL` at a red light is already filtered out before a real EXIT ever fires) rather than re-implementing that filtering here — this engine never sees raw speed at all, only the already-filtered activity signal, which is what makes F0.3 §7 requirement 1 ("zero speed alone MUST NOT finalize a Trip") true by construction rather than by an extra rule.

**No displacement check for confirmation, unlike `CandidateStartEngine` — a deliberate asymmetry, not an oversight:** F0.3 §7 requirement 5 treats *either* staying put *or* walking away as evidence the ride ended, so there's no "insufficient movement" failure mode to guard against the way candidate-start needed one (there, lack of displacement was exactly the false-positive risk). A `WALKING`/`ON_FOOT` ENTER while a candidate is open confirms **immediately** — requirement 5's own wording ("SHOULD contribute to confirming") for the single strongest, still zero-threshold signal available; otherwise, sustained absence of a re-`ENTER` for the grace period confirms (DP-002). An `IN_VEHICLE` ENTER before either abandons the candidate (requirement 4).

**Placeholder grace period (ADR-018 — field-gated, not frozen):** 3 minutes, via an injectable `CandidateStopProfile` (same testability requirement as `CandidateStartEngine`'s profile). F0.3 §7's own opening line — "automatic stop is intentionally more conservative than temporary-stop recognition" — is why this is 12× `CandidateStartProfile`'s 15s start window rather than a symmetric value: 3 minutes comfortably outlasts a traffic light, a stop sign, or brief congestion (§7's own named tolerance list) while staying a bounded wait, not indefinite.

**Grace-period timing works from ordinary location samples, not only from an explicit `TimeTick`:** since `TrackingSessionCoordinator.recordLocationUpdates` keeps recording every ~2s throughout a real Trip (including while stopped, until something actually ends it), real location samples already give this engine plenty of "time has passed" opportunities in the common case; `TimeTick` exists specifically for the GPS-loss-during-a-stop edge case (tunnel, parking garage) where no location sample would otherwise arrive to trigger the check.

**Same "no live caller yet" posture as `DET-001`/`DET-002`:** turning a `Confirmed` decision into an actual Finish is `AUTO-001`'s job. Also assumes its caller only routes events here while a Trip is genuinely being tracked — the mirror image of `CandidateStartEngine`'s IDLE-only assumption — for the same ADR-013-boundary reason (no `TripCaptureDao` dependency for no real benefit).

**Verified:** 14 new deterministic tests covering every scenario this task and F0.3 §7 name directly — the traffic-light case (short stop under the grace period stays open, neither confirmed nor abandoned) is this task's own acceptance criterion, tested explicitly by name. 124/124 tests pass (up from 110). No on-device verification, same reasoning as `DET-002` — nothing calls this engine yet.

---

### DET-004 — Temporary-stop hysteresis
**Objective:** implement temporary hold/churn protection.  
**Acceptance:** congestion/semáforo replay stays one Trip.

**Status: Done (2026-09-17) — no new production code; explicit test coverage proving an existing property.** F0.3 §5 itself says `TEMPORARY_HOLD` "may ultimately be implemented as an internal condition/substate rather than a persisted top-level state — the product behavior is what matters," and §7 draws a hard line between it and Candidate Stop ("automatic stop is intentionally more conservative than temporary-stop recognition"). Tracing §18's own named scenarios for this task (SCN-006 "heavy congestion with repeated 0–10 km/h motion", SCN-026 "multiple stop/start blocks in city → one logical Trip") against the actually-built pipeline: Google's own Transition-API filtering (F0.4 §4.2, already leaned on by `DET-003`'s own KDoc) means ordinary creeping/congestion never even emits an `IN_VEHICLE` EXIT, so the common case is a no-op by construction; and for the case where AR genuinely does flap EXIT/ENTER during dense traffic, `CandidateStopEngine` resets fully to `Tracking` on every `Abandoned` with no memory across cycles, so N repeated churn cycles are provably just the same single-cycle proof N times — nothing in `runAutoDetection`'s orchestration treats a re-`CandidateOpened` any differently after a prior abandon, and no diagnostic events are written per candidate open/abandon (only on `Confirmed`/persistence-failure), so churn can't spam the diagnostic trail either.

**What this task actually delivered, then, is verification, not implementation:** a new `CandidateStopEngineTest.repeatedTrafficLightChurnNeverConfirmsAndDoesNotPreventARealStopAfterward` (10 rapid EXIT/ENTER cycles, then proves a genuine subsequent stop still confirms correctly and reports the right `candidateOpenedAtElapsedRealtimeNanos`) and a new `TrackingSessionCoordinatorTest.runAutoDetectionSurvivesRepeatedCongestionChurnAsOneContinuousAutoTripBeforeFinishingOnARealStop` — a full-stack proof through `runAutoDetection` itself: one AUTO-started capture survives three EXIT/ENTER churn cycles with location samples continuing to persist uninterrupted throughout, ending in exactly one `TripCapture`/`Trip` row, before a real stop (walking away) finishes it. This is a deliberate departure from every prior task's shape in this wave — rather than writing code to satisfy an acceptance criterion, the criterion was traced against what `DET-002`/`DET-003`/`AUTO-001` already do and confirmed to already hold; inventing a new hysteresis mechanism on top would have been unrequested complexity solving a problem that doesn't exist in the current design.

**Verified:** 143/143 tests pass (up from 141). No on-device verification needed or attempted — this is pure domain-logic verification against an already-shipped, already-on-device-checked pipeline (`AUTO-001`), not new integration surface.

### DET-005 — Manual-pause warning logic
**Objective:** detect sustained movement while manually paused and warn without auto-resume.  
**Acceptance:** manual ownership remains authoritative.

**Status: Done (2026-09-17).** New `domain/detection/ForgottenPauseEngine` — deliberately **location-only**, unlike `CandidateStartEngine`'s activity+location combo: `recordLocationUpdates` (the path a manually-started, manually-paused capture actually uses — F0.3 §8's own primary example is "eating, resting or visiting a location", nothing auto-detection-specific) never sees activity transitions at all, only location samples, so gating on an `IN_VEHICLE` signal the way candidate-start does would leave that whole case undetectable. A new `ForgottenPauseProfile` (60s/100m defaults, ADR-018 placeholder) stands in for the missing activity evidence with a larger bar than `CandidateStartProfile`'s own 15s/40m start thresholds, reducing false alarms from ordinary walking-around-at-a-stop movement (F0.3 §18 SCN-008/SCN-009). Warns once per pause, never twice (F0.3 §8 explicitly defers "second reminder/escalation" to future research).

**Wired into both existing location-recording paths, sharing one small `ForgottenPauseWatch` helper** so neither duplicates "notice a new pause, run a fresh engine for it, forget it again once resumed": `TrackingSessionCoordinator.recordLocationUpdates` (TRK-002/TRK-003, for manually-started captures) and `runAutoDetection`'s already-paused branch (AUTO-001/TRK-003, for auto-started captures manually paused mid-ride). Both gained an `onForgottenPauseWarning` callback (mirroring `runAutoDetection`'s existing `onCaptureStarted` pattern) so `TrackingForegroundService` — the only thing allowed to touch notifications (ADR-013) — can surface a real, dismissible reminder via a new `TrackingNotificationController.postForgottenPauseReminder()` on its own channel/ID, distinct from the ongoing tracking notification. A warning is always just a diagnostic event (`DETECTOR`/`FORGOTTEN_PAUSE_WARNING`, correlated to the pause via `correlationId`) plus that notification — **nothing in this task ever touches capture or pause state**, which is exactly what "manual ownership remains authoritative" means made concrete: the engine only ever detects, never acts.

**Two genuinely subtle test races found and fixed while writing the `runAutoDetection` warning test, not just one:** an early version had `resumeCapture()` embedded in the location flow racing ahead of `pauseCapture()` (called from `onCaptureStarted`) — merged flows buffer independently, so a producer coroutine races arbitrarily far ahead of what the *consumer* has actually processed, not just what's been emitted. Fixing that (gating the location flow's post-confirm emissions on the same `confirmed` signal the activity flow already used) surfaced a *second* instance of the identical bug one step later: `resumeCapture()` then raced ahead of the 25s/90s samples being consumed and warned about. Fixed with a third explicit gate (`warningFired`, completed from inside `onForgottenPauseWarning` itself — a genuine consumer-side event, never a guessed delay). Both were caught by an actual hang/failure on a real test run, not suspicion — consistent with how TRK-003's own flaky-test fix was found.

**Verified:** 169/169 tests pass (up from 158), including the engine's own isolated tests (anchor/duration/displacement/warn-once), real Room-backed coordinator tests for both `recordLocationUpdates` and `runAutoDetection` (sustained movement warns without persisting/resuming/auto-finishing; ordinary small movement doesn't warn at all), and a Robolectric-shadow test proving the real posted notification's shape (dismissible, correct title, distinct ID from the ongoing tracking notification). **On-device (Honor DNY-NX9):** a full Start→Pause→Resume→Finish cycle with this code active ran cleanly with no crash and a correctly COMPLETED capture — a regression check on real hardware, not a claim of having triggered the warning itself. Triggering the actual "sustained motorized movement while paused" scenario live needs a genuine, sustained real GPS fix in motion, which this device couldn't produce indoors in the time available (same limitation already documented for `AUTO-001`) — an honest gap, not a claimed pass.

### DET-006 — Duplicate-start / post-finish suppression
**Objective:** prevent immediate duplicate or rebound starts.  
**Constraints:** ADR-015/020.

**Status: Done (2026-09-18).** F0.3 §9's "post-manual-finish suppression": "a rider/passenger may press Finish while the device is still moving. Without protection, the detector could immediately create a new candidate Trip." New `domain/detection/PostFinishSuppression` (a pure decision object, same posture as `CapabilityResolver` — zero DAO/Android access, ADR-013) plus `PostFinishSuppressionProfile` (a 2-minute placeholder, ADR-018 — F0.3 §9 itself calls the exact timeout "a research question"). New `TripCaptureDao.findMostRecentlyEnded` (whichever capture, COMPLETED or ABORTED, ended most recently, ordered by `endElapsedRealtimeNanos` per GPS-004 rather than wall-clock `endedAt`) feeds it real data; `ActivityTransitionReceiver.maybeStartAutoDetection` gained one more gate alongside its existing "no active capture"/capability-mode checks, refusing to start `ACTION_AUTO_DETECT` while the most recently ended capture is still inside the suppression window.

**Scope decided against F0.3 §9's own wording, not guessed:** the spec explicitly leaves open whether the window "should end after evidence shows movement ended, or after another explicit Manual Start" as a research question — lifting it early on non-motorized evidence would need real field data about what that evidence reliably looks like, which doesn't exist yet, so this task implements only the straightforward time-based half. A Manual Start already bypasses this entirely by construction: it's a direct UI/service command that never goes through `ActivityTransitionReceiver` at all, so no special-casing was needed to satisfy "ends after an explicit Manual Start" — DP-005 (manual intent wins) already guarantees it structurally. "Duplicate-start" and "post-finish suppression" turned out to be the same concern from two angles (matching how F0.3 groups them under one shared section) rather than two separate mechanisms — the duplicate-start half ADR-015/ADR-020 already cover (idempotent Start, single-ACTIVE-capture invariant); this task's actual job was the *rebound-after-Finish* half neither of those touches.

**Verified:** 176/176 tests pass (up from 169) — the suppression policy's own boundary cases in isolation (never suppressed with no prior capture, suppressed immediately after a Finish, still suppressed just before the window elapses, no longer suppressed exactly at and after it) plus two `ActivityTransitionReceiver`-level tests proving the gate actually blocks and later un-blocks a real `ACTION_AUTO_DETECT` start. **No on-device verification this task** — the trigger this gate sits behind (a genuine Activity Recognition transition) has the same wire-format-not-fabricable-via-adb limitation already documented for `DET-001`/`AUTO-001`, and the phone was not connected when this task ran regardless.

### DET-007 — Forgotten-finish reminder for manual captures

**Objective:** close a real gap `UI-001`'s own field use surfaced (`trip-detection-spec.md`'s new "Forgotten-finish scenario"/SCN-029), not one `F0.3` originally scoped: a manually-started capture has no automatic stop at all, so a rider who simply forgets to press Finish keeps recording indefinitely.

**Constraints:** ADR-018 (placeholder thresholds), DP-005 (manual intent wins — a nudge, never an auto-finish).

**Status: Done (2026-09-18).** Not planned ahead of time — the user ran two real outdoor test rides right after `UI-001` shipped and reported forgetting to press Finish on one of them. Pulling the real production database (`sqlite3.exe`, bundled with the Android SDK's `platform-tools`, since no `sqlite3` binary exists on-device or in this shell) confirmed the app itself behaved correctly - both captures ended `COMPLETED`/`endSource=MANUAL`, distance stayed accurate (5.38 km over 37.6 minutes, real GPS accuracy ~7m) because raw distance is a real point-to-point sum, not a time-based estimate - but average speed came out an implausible ~8.6 km/h, exactly what "rode normally, then sat parked for a long time before remembering to finish" looks like. New `domain/detection/ForgottenFinishEngine`/`ForgottenFinishProfile` — the deliberate mirror of `DET-005`'s `ForgottenPauseEngine`: instead of warning once cumulative displacement from an anchor proves sustained *movement*, it re-anchors on every sample that clears a small radius (still riding) and warns once the anchor's age clears a duration bar without that happening (sustained *non*-movement). Considered reusing `CandidateStopEngine` (`DET-003`) directly first, but its own code confirmed it's entirely `IN_VEHICLE EXIT`-gated - useless here since `recordLocationUpdates` (the manual-capture path) never sees activity transitions at all, the same reason `ForgottenPauseEngine` had to be location-only.

**Wired only into `recordLocationUpdates`, deliberately not `runAutoDetection`:** an AUTO-started capture already gets a real stop-and-finish from `CandidateStopEngine` on a genuine stop, so it was never exposed to this gap - only a manual capture has no safety net at all, which is the actual problem being solved. Warns once per *stationary episode*, not once ever (unlike `ForgottenPauseEngine`'s one-shot-per-bounded-pause scope): a long multi-stop ride can plausibly have more than one genuinely-forgotten-length stop, and real movement resets the episode, so an earlier stop's warning doesn't silently exhaust the engine's ability to warn about a later, unrelated one. A new `TrackingSessionCoordinator.ForgottenFinishWatch` (mirroring `ForgottenPauseWatch`'s shape) resets its engine whenever a sample arrives during an open manual pause — without that reset, the first sample after a real Resume would inherit an anchor whose age spans the entire pause and could misfire immediately, a bug caught by its own dedicated regression test before it could ever reach a device. New `TrackingNotificationController.postForgottenFinishReminder()` shares `DET-005`'s reminder channel/ID rather than adding a third one (the two reminders are mutually exclusive per capture), with that channel's display name generalized from "Pause reminders" to "Trip reminders" to stay honest about covering both.

**Verified:** 188/188 tests pass (up from 177) — the engine's own isolated tests (anchors without warning, no warning while genuinely still moving, warns past the duration bar, small GPS-jitter-scale movement doesn't reset the anchor, never warns twice for the same episode, warns again for a later independent episode after real movement in between) plus real Room-backed coordinator tests (warns while still persisting samples normally and leaving the capture `ACTIVE` — never auto-finishing; never warns during continuous real movement; a pause correctly discards the pre-pause anchor rather than letting its age carry across the gap) and a Robolectric-shadow test proving the real notification's shape. **Not verified live on-device**: the default 600s stationary bar needs a genuine 10-real-minute stationary wait to trigger naturally, which wasn't done this task (the same class of honest gap already accepted for `DET-005`'s own movement-based reminder) — the exact behavior it protects is proven deterministically by the coordinator tests instead.

### AUTO-001 — Capability-aware automatic orchestration
**Objective:** connect detector to Full Auto or Assisted flows based on CAP-001.  
**Constraints:** ADR-004/007/008/020.

**Status: Done (2026-09-17).** `CandidateStartEngine`/`CandidateStopEngine` (`DET-002`/`DET-003`) are now wired to real Start/Finish. New production consumer of `CapabilityResolver` (`CAP-001`), which had none before this task: `AndroidCapabilityInputsProvider` reads real permission/service state (`ContextCompat.checkSelfPermission`, `NotificationManagerCompat.areNotificationsEnabled`, `LocationManagerCompat.isLocationEnabled`) plus a new `AutoTrackingPreferences` (DataStore Preferences 1.2.1, per F0.8 §3's pin) for the one persisted "Auto Tracking enabled" boolean — defaults `false`, so a fresh install with no onboarding correctly resolves `MANUAL`, never an auto mode by accident (F0.11 §7.2's onboarding sequence treats this as a deliberate opt-in).

**Trigger → orchestration split.** `ActivityTransitionReceiver` (unchanged in its own recording duty) now also: (a) publishes every transition onto a new `@Singleton ActivityTransitionBus` (a `SharedFlow`, `DROP_OLDEST`, non-suspending `tryEmit` — a live signal, not a delivery-guaranteed queue), and (b) on an `IN_VEHICLE` ENTER with no ACTIVE capture and `CapabilityResolver` resolving `FULL_AUTO`/`ASSISTED_AUTO`, starts `TrackingForegroundService` with a new `ACTION_AUTO_DETECT`. Everything from there runs inside one new `TrackingSessionCoordinator.runAutoDetection` coroutine: a single continuous subscription (`merge` of the bus, the same TRACKING-grade `LocationGateway`, and an internal 15s ticker) that hands events to `CandidateStartEngine` until confirmed, then switches to persisting Raw Track points and feeding `CandidateStopEngine` until it confirms, calling `finishCapture(..., EndSource.AUTO)`. `TrackingSessionCoordinator.startManualCapture`/`finishCapture` were refactored (a shared private `startCapture(source)`; `finishCapture` gained an `endSource` parameter defaulting to `MANUAL`) rather than duplicated for the AUTO path.

**Notification honesty (F0.9 §5.3):** candidate validation shows a distinct "Checking for a possible trip…" notification, not "Recording your trip" — swapped in place the moment a candidate actually confirms, so a candidate abandoned seconds later was never mislabelled.

**Deliberate v1 simplifications, documented rather than silently accepted:**
- **Reuses the TRACKING-grade location profile during candidate validation too**, instead of building a separate lighter "burst" profile — justified by DP-008's own wording ("high-detail location tracking SHOULD be activated only when a Trip is being validated OR recorded").
- **F0.3 §6 requirement 5's "SHOULD not lose a large initial route section" is not fully met**: location samples evaluated during candidate validation are never retroactively persisted once confirmed, only samples from the moment of confirmation onward are. Buffering/backdating would need real sample-retention/replay machinery out of this task's scope.
- **Auto-stop monitoring does not survive a process death mid-flight.** If the process dies while an AUTO-started capture is active, the existing sticky-restart path (`rehydrateOrStop`) resumes plain Raw Track recording (no data loss) but does not resume automatic candidate-stop monitoring for it — the trip remains finishable manually. Re-entering just the stop-monitoring phase after a restart is deferred.
- **Auto-stop monitoring only ever applies to AUTO-started captures**, never a manually-started one, so an automatic system can never silently finish a trip the rider explicitly started by hand (DP-005, "manual intent wins").
- Manifest gained `ACCESS_BACKGROUND_LOCATION` (declared, not yet requested by any onboarding UI — F0.11 §7.2's incremental request sequence is a separate, not-yet-built task) so `FULL_AUTO`'s own capability check can ever resolve true at all.

**Verified:** 141/141 tests pass (up from 124), including new coordinator-level tests driving `runAutoDetection` through a fully deterministic, virtual-time-scheduled merged Flow (abandon-before-confirm, a lost race against a pre-existing manual capture, and a complete confirm → auto-finish-via-walking-away round trip asserting the real DB rows), plus real-Robolectric-shadow tests for `AndroidCapabilityInputsProvider` (permission/location/notification state) and `AutoTrackingPreferences` (real DataStore file I/O), plus service-level tests proving the distinct validating notification, the auto-detection dedupe guard, and a real `ActivityTransitionBus`-delivered abandon path.

**On-device (Honor DNY-NX9, Android 16), confirmed after the phone reconnected:** installed the rebuilt debug APK, granted the runtime permissions, and invoked `ACTION_AUTO_DETECT` directly on the real, non-exported `TrackingForegroundService` via `adb shell run-as ... am start-foreground-service` (the same bypass-the-normal-trigger technique used for every prior on-device check) — the same real bypass this device's foreground-service-start-from-background restriction that TRK-001/002/004 already worked around, this time via a brief `am start` of the launcher Activity first to obtain the temporary exemption. Confirmed via `dumpsys activity services` that the service actually reached `isForeground=true` with no crash, and via a device screenshot that the real posted notification reads **"Checking for a possible trip…"**, not "Recording your trip" — the F0.9 §5.3 notification-honesty claim, now confirmed rendering correctly on real hardware, not just asserted against a Robolectric shadow. Pulled and diffed the real production database (binary-safe via `adb exec-out`, not `adb shell ... >`, which was found here to corrupt the pull with CRLF-mangled bytes — `PRAGMA integrity_check` failed until switched) before and after: `PRAGMA integrity_check` passed both times, and no `ACTIVE` or `AUTO`-sourced `trip_capture` row and no new `diagnostic_event` row was created by the run, confirming that real GPS noise and the internal ticker alone — with no genuine `IN_VEHICLE` transition ever arriving — correctly produce no false-positive capture. Cleanly stopped via the existing `ACTION_FINISH` no-op path (already unit-tested for "no active capture"), re-confirmed via `dumpsys` that the service record was gone afterward.

**Still not reachable on this device, honestly unverified:** a genuine end-to-end run driven by a real Activity-Recognition `IN_VEHICLE` transition (receiver-triggered start → real GPS-based confirm → auto-finish) — `ActivityTransitionResult`'s wire format inside a broadcast Intent extra isn't practically fabricable via `adb`, the same limitation `DET-001`/this task's own `ActivityTransitionReceiverTest`/`ActivityTransitionBus` tests already document, and this device's real transition delivery was separately found unreliable during `DET-001`. What was verified is everything reachable up to that boundary: the service's real lifecycle, the real notification, and that nothing spurious gets written without a real trigger.

### EXP-002 — Pilot field campaign
**Objective:** execute F0.6 pilot and identify logging/profile defects before comparative measurements.

**Status: harness UI done (2026-09-19); the actual pilot rides are not.** F0.6 §5's harness didn't exist as a UI before this - EXP-001 only built the backend (`FieldTestSessionExporter` et al.) and explicitly left "a future harness UI/service" for whoever needed to actually tap the buttons. This task built that UI (`feature/fieldtest/FieldTestHarnessScreen` + `FieldTestHarnessViewModel`), reachable from Settings ("Field test harness (internal)") - not a tab, per EXP-001's own acceptance criterion.

Covers F0.6 §5's minimum functions: start/stop an experimental session, free-text `experimentProfileId` (§10 profile IDs are still evolving, so this isn't a closed dropdown), a live "detector state" glance, ground-truth marker buttons, and export. Two new small seams (`FieldTestDeviceInfoProvider`/`AndroidFieldTestDeviceInfoProvider`) read the device-side fields F0.6 §6.1 asks for (manufacturer/model/Android version/battery-saver/location-settings/notification state) the same way `AndroidCapabilityInputsProvider` already reads permissions; `playServicesVersion` is left `null` on purpose (F0.6 marks it optional, and reading it for real needs `play-services-base`, not currently a dependency here).

"Show detector state" deliberately means the F0.11 capability mode (`CapabilityResolver`) plus whatever `TrackingSessionCoordinator.currentTrackingSnapshot` already exposes about an active capture (start source/paused/distance/elapsed - the same figures NOT-001's notification shows), refreshed every 2s. It does **not** expose DET-002/003's internal candidate-start/stop state machine live - that's private/pure by design and already captured to `DiagnosticEvent` for post-hoc analysis (F0.13); building a live view into it would be new scope this task didn't need. Ground-truth buttons cover F0.6 §7's vocabulary except GT_START/GT_END, which `GroundTruthMarker`'s own KDoc says are reviewed post-hoc against the Raw Track, never tapped live.

Verified: `./gradlew assembleDebug testDebugUnitTest` green (240/240, including 7 new `FieldTestHarnessViewModelTest` cases covering blank-profile-id rejection, capability-mode resolution against real `CapabilityInputs`, a real active capture's info surfacing correctly, marker-count accumulation, and a full stop→export round trip whose `session.json`/`annotations.json` are parsed back with `org.json` to confirm the written content, not just that a file exists).

**A real crash was found and fixed during on-device testing, not a notification-interference issue as first (wrongly) suspected.** The first two live attempts to tap "Start session" made `MainActivity` exit and reveal whatever was previously open underneath (WhatsApp) - initially misdiagnosed as a heads-up notification stealing the tap. Pulling `adb logcat` showed the real cause: both attempts hit the identical `FATAL EXCEPTION` - `IllegalStateException: Vertically scrollable component was measured with an infinity maximum height constraints` - from a `LazyVerticalGrid` (the marker-button grid) nested inside `FieldTestHarnessScreen`'s outer `LazyColumn`, which Compose disallows. Robolectric's `FieldTestHarnessViewModelTest` never caught this because it only exercises the ViewModel, never real Compose layout/measurement. Fixed by replacing the nested `LazyVerticalGrid` with a plain chunked `Row`/`Column` grid (the marker vocabulary is a small, fixed 7 entries - a lazy grid was unneeded there anyway).

After the fix, the full flow was verified live end-to-end on the Honor DNY-NX9: Home → Settings → "Field test harness (internal)" → Configuring (required-field gating confirmed) → Start session → Active screen (live elapsed ticker, real resolved `CapabilityMode` = Manual matching the device's actual Auto-Tracking-off state, two `READY_TO_START` markers tapped and counted correctly) → Stop & export → confirmation banner. Pulled the real written files via `adb shell run-as` and confirmed their content directly: `session.json` has the device's real manufacturer/model (`HONOR`/`DNY-NX9`), Android version, and real permission states; `annotations.json` has both real markers with real timestamps.

**Resumability across a real process death, added before any field use (2026-09-19).** A real ride can run long enough, with the screen off, for Android to kill the app in the background - the harness's session/marker state lived only in the ViewModel's memory, so a kill mid-ride would have silently lost the whole session before export. Added `FieldTestHarnessStateStore`/`AndroidFieldTestHarnessStateStore` (a single `field-tests/harness-in-progress.json`, since at most one harness session is ever active) and `FieldTestHarnessStateJson` (round-trip, unlike the write-only `FieldTestSessionJson` - this one has to be read back by a recreated ViewModel). `FieldTestHarnessViewModel` now persists on start and on every marker tap, rehydrates `Active` state from it in `init`, and clears it once a session is genuinely exported. Verified live on the Honor DNY-NX9 with a **real kill**, not a simulated one: started a session, recorded a marker, confirmed the on-disk JSON, then `adb shell am force-stop com.mototriptracker.app` (a real process kill, not just backgrounding), relaunched, and the Active screen came back with the same session ID, profile, and marker count - the elapsed ticker kept counting correctly through the kill since `elapsedRealtimeNanos` is monotonic across a process death (only a real reboot would break it, which this doesn't handle - out of scope for an internal tool). 246/246 unit tests green, including 4 new persistence cases and a `FieldTestHarnessStateJsonTest` round-trip test.

**Still not done, and the actual point of this task's title:** real pilot rides. The harness now exists, and now survives the phone's own aggressive background-killing; nobody has ridden with it yet.

**Known limitation for whoever does the first pilot ride:** `experimentProfileId` is currently just a free-text label for record-keeping - there is no real profile-switching mechanism yet. `FusedLocationGateway`'s actual GPS request is a single hardcoded configuration (`TRACKING_PROFILE_ID = "tracking-manual-v0"`, 2s interval, 0m min-distance). Typing "S1-A" in the harness does not make the GPS actually sample at 1s; building that switch is EXP-003's job ("compare interval/min-distance/batching experiment profiles"), not this task's. A pilot ride today validates the harness itself (F0.6 §22's actual pilot goals - dataset completeness, logging bugs, real observed ranges) under the one real profile that exists, and should be labeled `tracking-manual-v0` rather than an S1/S2/S3 ID that doesn't reflect a real switch.

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

## V1 Reference — F0.16 (reinstated 2026-09-19)

Narrowly-scoped follow-on to F0.16's reinstatement (`docs/00-master/f0-16-v1-reference.md`): feature parity and data migration planning only, not a V1 architecture review. Not gating any wave; schedule opportunistically alongside W2/W3.

### REF-001 — V1 feature-parity checklist

**Objective:** produce the parity table described in F0.16 §3 — every V1 user-facing feature classified as Covered/Planned/Gap/Deliberately excluded against V2's actual backlog state.

**Constraints:** `docs/00-master/f0-16-v1-reference.md` §3, `ADR-001` clarifying note (2026-09-19). Read-only against V1 (`C:\proyectos\moto-trip-tracker`) — no V1 code changes, no V1 install touched.  
**Depends on:** none technically; most useful once W2's automatic-detection slice is closer to done, since several V1 features to compare against are detection-adjacent.

**Acceptance:** a checklist artifact (doc) covering all of V1's Etapa 0-8 features from its `README.md`/`docs/roadmap.md`; every "Gap" row either gets a new backlog task ID or an explicit owner decision to exclude it, recorded in this file or `phase-0-master.md`.

### REF-002 — V1 data migration design

**Objective:** produce the mapping/design document described in F0.16 §4 — V1 `Trip`/`TrackPoint` fields mapped to V2's actual tables, the `endReason`→V2 end-source mapping table, and an explicit list of what cannot be migrated given V1's flat schema (no raw stream, no rejected points, no point-level quality assessment, no lineage/edit history).

**Constraints:** `docs/00-master/f0-16-v1-reference.md` §4, `ADR-005`/`ADR-006`/`ADR-014` (V2's raw/processed/versioned data model, which V1 has no equivalent for). Design/planning only — no import code, no execution against the real V1 pilot database, unless a later explicit owner decision asks for it.  
**Depends on:** none; independent of `REF-001`.

**Acceptance:** a design doc with the field-by-field mapping, the enum mapping table, an explicit recommendation on how an imported trip is marked/versioned relative to `processingVersion`, and an explicit unrecoverable-data list. No silent defaults on the "mandatory vs optional import" product question — surfaced for an owner decision instead.

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
