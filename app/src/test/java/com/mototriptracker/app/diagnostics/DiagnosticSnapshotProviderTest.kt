package com.mototriptracker.app.diagnostics

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.model.CapabilityMode
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.testing.FakeCapabilityInputsProvider
import com.mototriptracker.app.testing.FakeCapabilityProvider
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import com.mototriptracker.app.tracking.persistence.PersistenceHealthBus
import com.mototriptracker.app.tracking.persistence.PersistenceLevel
import com.mototriptracker.app.tracking.persistence.PersistenceState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DIA-002 / F0.13 §10.1 and §18.1: the debug snapshot is rebuilt from repositories and the platform every time, says
 * "unknown" when it does not know, and can never carry a coordinate.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticSnapshotProviderTest {

    private class FakeSystem(var notificationShown: Boolean? = true, var batterySaver: Boolean? = false) : SystemDiagnosticsInfo {
        override fun deviceSummary() = "Acme Phone 9"
        override fun androidApi() = 36
        override fun batterySaverOn() = batterySaver
        override fun trackingNotificationShown() = notificationShown
    }

    private class FakeWork(var counts: ProcessingSection = ProcessingSection(0, 0, 0, 0, 0)) : ProcessingWorkReader {
        override suspend fun counts() = counts
    }

    private lateinit var db: MotoTripDatabase
    private lateinit var clock: FakeClock
    private lateinit var system: FakeSystem
    private lateinit var work: FakeWork
    private lateinit var bus: PersistenceHealthBus
    private lateinit var provider: DiagnosticSnapshotProvider

    private val realCaptureId = "3f2a91c4-77aa-4b6e-9d21-0c5e8e1d9b10"

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        clock = FakeClock(wallMillis = 1_000_000L, elapsedNanos = 100_000_000_000L)
        system = FakeSystem()
        work = FakeWork()
        bus = PersistenceHealthBus()
        provider = DiagnosticSnapshotProvider(
            database = db, tripCaptureDao = db.tripCaptureDao(), rawTrackPointDao = db.rawTrackPointDao(),
            diagnosticEventDao = db.diagnosticEventDao(), tripDao = db.tripDao(), tripStatisticsDao = db.tripStatisticsDao(),
            capabilityInputsProvider = FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()),
            system = system, processingWork = work, persistenceHealthBus = bus, clock = clock
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun activeCapture(id: String = realCaptureId) = db.tripCaptureDao().insert(
        TripCaptureEntity(
            id = id, status = CaptureStatus.ACTIVE, startedAt = 1L, endedAt = null, startElapsedRealtimeNanos = 1L,
            endElapsedRealtimeNanos = null, localTimeZoneId = "UTC", startSource = StartSource.MANUAL, endSource = null,
            detectorVersion = DetectorVersion(0), locationProfileVersion = LocationProfileVersion(0), createdAt = 1L, updatedAt = 1L
        )
    )

    private suspend fun point(seq: Long, elapsedSeconds: Long, accuracy: Float = 5f, speed: Float? = null) = db.rawTrackPointDao().insert(
        RawTrackPointEntity(
            captureId = realCaptureId, sequenceNumber = seq, capturedAt = seq, elapsedRealtimeNanos = elapsedSeconds * 1_000_000_000L,
            receivedAtElapsedRealtimeNanos = null, latitude = 10.123456, longitude = -20.654321, horizontalAccuracyM = accuracy,
            altitudeEllipsoidM = null, altitudeMslM = null, verticalAccuracyM = null, speedMps = speed, speedAccuracyMps = null,
            bearingDeg = null, bearingAccuracyDeg = null, provider = "fused", isMock = false, requestProfileId = "p",
            callbackBatchId = null, detectorStateSnapshot = "TRACKING"
        )
    )

    private suspend fun event(id: String, type: String, category: DiagnosticCategory, at: Long, elapsed: Long? = null, reason: String? = null, capture: String? = null, metadata: Map<String, String> = emptyMap()) =
        db.diagnosticEventDao().insert(
            DiagnosticEventEntity(
                eventId = id, occurredAt = at, elapsedRealtimeNanos = elapsed, category = category, eventType = type,
                severity = DiagnosticSeverity.INFO, source = "test", captureId = capture, tripId = null, correlationId = null,
                stateBefore = null, stateAfter = null, reasonCode = reason, metadata = metadata, appVersion = "t", schemaVersion = 1,
                detectorVersion = DetectorVersion(0), locationProfileVersion = LocationProfileVersion(0), processingVersion = ProcessingVersion(0)
            )
        )

    @Test
    fun withNothingRecordingItSaysSoAndIsHealthy() = runTest {
        val snapshot = provider.snapshot()

        assertFalse(snapshot.tracking.activeCapture)
        assertNull(snapshot.tracking.shortCaptureId)
        assertFalse(snapshot.location.hasActiveCapture)
        assertNull("no fix to age", snapshot.location.lastFixAgeMs)
        assertEquals(HealthState.HEALTHY, snapshot.tracking.health)
        assertEquals(CapabilityMode.FULL_AUTO, snapshot.capabilities.mode)
    }

    @Test
    fun theAppSectionReadsTheRealDatabaseVersionAndTheDeviceNotAGuess() = runTest {
        val app = provider.snapshot().app

        assertEquals(3, app.databaseSchemaVersion)
        assertEquals(36, app.androidApi)
        assertEquals("Acme Phone 9", app.device)
        assertEquals(0, app.processingVersion)
    }

    @Test
    fun anActiveRecordingIsDescribedByAgeAccuracyAndIntervalNeverByPosition() = runTest {
        activeCapture()
        listOf(70L, 72L, 74L, 76L, 78L).forEachIndexed { i, t -> point(i.toLong(), t, accuracy = 4f + i, speed = if (i == 4) 3.5f else null) }

        val snapshot = provider.snapshot()

        assertEquals(realCaptureId.let(DiagnosticFormulas::shortId), snapshot.tracking.shortCaptureId)
        assertNotNull(snapshot.tracking.shortCaptureId)
        assertEquals("100 s of clock minus the last fix at 78 s", 22_000L, snapshot.location.lastFixAgeMs)
        assertEquals(8f, snapshot.location.lastAccuracyM)
        assertEquals(true, snapshot.location.lastFixHadSpeed)
        assertEquals(2_000L, snapshot.location.effectiveIntervalMs)
        assertEquals(5, snapshot.location.pointCount)
        assertEquals(snapshot.location.lastFixAgeMs, snapshot.tracking.lastPersistenceAgeMs)
    }

    @Test
    fun anOpenGapAndApproximateOnlyAreReportedAndDegradeTheHealth() = runTest {
        activeCapture()
        point(0, 90)
        event("g", TrackingSessionCoordinator.EVENT_LOCATION_GAP_STARTED, DiagnosticCategory.LOCATION, at = 1L, elapsed = 1L, reason = "NO_FIX", capture = realCaptureId)
        event("a", TrackingSessionCoordinator.EVENT_LOCATION_ACCURACY_DEGRADED, DiagnosticCategory.LOCATION, at = 2L, elapsed = 2L, reason = "PRECISE_LOCATION_MISSING", capture = realCaptureId)

        val snapshot = provider.snapshot()

        assertTrue(snapshot.location.gapActive)
        assertEquals("NO_FIX", snapshot.location.gapReason)
        assertTrue(snapshot.location.approximateOnly)
        assertEquals(HealthState.DEGRADED, snapshot.tracking.health)
    }

    @Test
    fun aGapThatEndedIsNotActive() = runTest {
        activeCapture()
        event("g", TrackingSessionCoordinator.EVENT_LOCATION_GAP_STARTED, DiagnosticCategory.LOCATION, at = 1L, elapsed = 1L, reason = "NO_FIX", capture = realCaptureId)
        event("e", TrackingSessionCoordinator.EVENT_LOCATION_GAP_ENDED, DiagnosticCategory.LOCATION, at = 2L, elapsed = 2L, reason = "SIGNAL_RESTORED", capture = realCaptureId)

        assertFalse(provider.snapshot().location.gapActive)
    }

    @Test
    fun persistenceThatCannotSaveIsShownAsCriticalAndOutranksTheRest() = runTest {
        activeCapture()
        bus.publish(PersistenceState(PersistenceLevel.CRITICAL, storageFull = true, pointsLost = false))

        val tracking = provider.snapshot().tracking

        assertEquals(HealthState.PERSISTENCE_CRITICAL, tracking.health)
        assertTrue(tracking.persistence.storageFull)
    }

    @Test
    fun anActiveCaptureWithNoNotificationShowingIsRecoveryRequiredAndSaysWhy() = runTest {
        activeCapture()
        system.notificationShown = false

        val snapshot = provider.snapshot()

        assertEquals(HealthState.RECOVERY_REQUIRED, snapshot.tracking.health)
        assertTrue(snapshot.recovery.openInconsistencies.single().contains("notification is not showing"))
    }

    /** ADR-016: not knowing is not the same as "no". */
    @Test
    fun whenThePlatformCannotSayWhetherTheNotificationShowsNothingIsAssumedWrong() = runTest {
        activeCapture()
        system.notificationShown = null

        val snapshot = provider.snapshot()

        assertNull(snapshot.tracking.foregroundNotificationShown)
        assertEquals(HealthState.HEALTHY, snapshot.tracking.health)
        assertTrue(snapshot.recovery.openInconsistencies.isEmpty())
    }

    @Test
    fun theRecoverySectionShowsTheLastExitAndTheLastRecoveryActionSeparately() = runTest {
        event("x1", "PROCESS_EXIT", DiagnosticCategory.RECOVERY_SYSTEM, at = 5_000L, reason = "CRASH", metadata = mapOf("importance" to "FOREGROUND_SERVICE"))
        event("x2", "PROCESS_EXIT", DiagnosticCategory.RECOVERY_SYSTEM, at = 9_000L, reason = "SIGNALED", metadata = mapOf("importance" to "CACHED"))
        event("r1", "PROCESS_RECOVERED", DiagnosticCategory.RECOVERY_SYSTEM, at = 9_500L)

        val recovery = provider.snapshot().recovery

        assertEquals("SIGNALED", recovery.lastProcessExit?.reasonCode)
        assertEquals("importance=CACHED", recovery.lastProcessExit?.detail)
        assertEquals("PROCESS_RECOVERED", recovery.lastRecoveryAction?.eventType)
    }

    @Test
    fun completedTripsWithNoStatisticsAndNothingQueuedAreFlaggedButNotWhenWorkIsPending() = runTest {
        db.tripDao().insert(TripEntity(id = "t", status = TripStatus.COMPLETED, name = null, isFavorite = false, motorcycleId = null, routeId = null, notes = null, createdAt = 1L, updatedAt = 1L, deletedAt = null))

        assertTrue(provider.snapshot().recovery.openInconsistencies.single().contains("no statistics"))

        work.counts = ProcessingSection(pending = 1, running = 0, succeeded = 0, failed = 0, maxAttemptCount = 0)
        assertTrue(provider.snapshot().recovery.openInconsistencies.isEmpty())
    }

    /** F0.13 §10.1 "no mostrar coordenadas exactas": enforced on the shape of the snapshot, like DiagnosticEventPrivacyTest does for events. */
    @Test
    fun noSnapshotFieldCanCarryACoordinateOrAFreeTextName() {
        val classes: List<Class<*>> = listOf(
            DiagnosticSnapshot::class.java, AppSection::class.java, CapabilitiesSection::class.java, DetectorSection::class.java,
            LocationSection::class.java, TrackingSection::class.java, ProcessingSection::class.java, RecoverySection::class.java,
            EventBrief::class.java
        )

        // A field that could hold where someone was, or something a person typed. "versionName" is the app's, not a person's.
        fun risky(field: String): Boolean {
            val n = field.lowercase()
            return n.contains("latitude") || n.contains("longitude") || n == "lat" || n == "lon" || n == "lng" ||
                n.contains("coordinate") || n.contains("position") || n.contains("address") || n.contains("note") ||
                (n.endsWith("name") && n != "versionname")
        }

        val offenders = classes.flatMap { c -> c.declaredFields.map { it.name }.filter(::risky).map { "${c.simpleName}.$it" } }

        assertEquals("fields that could hold a coordinate or free text: $offenders", emptyList<String>(), offenders)
    }

    /** And by construction of the values: nothing built from a real ride contains its coordinates. */
    @Test
    fun aSnapshotBuiltFromARealRideContainsNoCoordinateDigits() = runTest {
        activeCapture()
        (0..4).forEach { point(it.toLong(), 70L + it * 2) }

        val text = provider.snapshot().toString()

        assertFalse(text.contains("10.123456"))
        assertFalse(text.contains("20.654321"))
        assertFalse("the real capture id must not appear, only its short label", text.contains(realCaptureId))
    }
}
