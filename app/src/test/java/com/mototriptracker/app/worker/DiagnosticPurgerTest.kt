package com.mototriptracker.app.worker

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.persistence.RawPointWriter
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/** DIA-004 / F0.13 §12.2: the diagnostic evidence is bounded, and the bound can never reach domain data. */
@RunWith(RobolectricTestRunner::class)
class DiagnosticPurgerTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var clock: FakeClock
    private lateinit var purger: DiagnosticPurger

    private val now = TimeUnit.DAYS.toMillis(100)

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        clock = FakeClock(wallMillis = now, elapsedNanos = 1_000L)
        purger = DiagnosticPurger(db.diagnosticEventDao(), clock, FakeIdGenerator(prefix = "purge"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun event(id: String, ageDays: Long, type: String = "SOMETHING") = DiagnosticEventEntity(
        eventId = id, occurredAt = now - TimeUnit.DAYS.toMillis(ageDays), elapsedRealtimeNanos = null,
        category = DiagnosticCategory.RECOVERY_SYSTEM, eventType = type, severity = DiagnosticSeverity.INFO,
        source = "test", captureId = null, tripId = null, correlationId = null, stateBefore = null, stateAfter = null,
        reasonCode = null, metadata = emptyMap(), appVersion = "test", schemaVersion = 1, detectorVersion = DetectorVersion(0),
        locationProfileVersion = LocationProfileVersion(0), processingVersion = ProcessingVersion(0)
    )

    private suspend fun ids() = db.diagnosticEventDao().findAll().map { it.eventId }.toSet()

    @Test
    fun eventsOlderThanFourteenDaysAreRemovedAndRecentOnesKept() = runTest {
        val dao = db.diagnosticEventDao()
        dao.insert(event("old", ageDays = 15))
        dao.insert(event("edge", ageDays = 13))
        dao.insert(event("new", ageDays = 1))

        val result = purger.purge()

        assertEquals(1, result.purgedByAge)
        assertEquals(setOf("edge", "new"), ids() - db.diagnosticEventDao().findAll().filter { it.eventType == DiagnosticPurger.EVENT_PURGE_COMPLETED }.map { it.eventId }.toSet())
    }

    @Test
    fun whenStillOverCapacityTheOldestGoFirst() = runTest {
        val dao = db.diagnosticEventDao()
        (1..6).forEach { dao.insert(event("e$it", ageDays = 7L - it)) } // e1 oldest ... e6 newest, all inside 14 days

        val result = purger.purge(maxEvents = 4)

        assertEquals(0, result.purgedByAge)
        assertEquals(2, result.purgedByCapacity)
        assertEquals(setOf("e3", "e4", "e5", "e6"), ids().filter { it.startsWith("e") }.toSet())
    }

    /** Trip Detail's data-loss note is derived from these; purging them would silently drop a warning about a trip that still exists. */
    @Test
    fun dataLossEvidenceOutlivesBothTheAgeAndTheCapacityLimit() = runTest {
        val dao = db.diagnosticEventDao()
        dao.insert(event("loss-ancient", ageDays = 400, type = RawPointWriter.EVENT_DATA_LOSS))
        dao.insert(event("old", ageDays = 20))
        (1..5).forEach { dao.insert(event("e$it", ageDays = 1)) }

        purger.purge(maxEvents = 3)

        assertNotNull("kept", dao.findById("loss-ancient"))
        assertEquals(null, dao.findById("old"))
    }

    /** F0.13 §18.1: "purge técnico no elimina Trip/Capture/RawTrack". */
    @Test
    fun purgingNeverTouchesTripsCapturesOrRawPoints() = runTest {
        db.tripCaptureDao().insert(
            TripCaptureEntity(
                id = "cap", status = CaptureStatus.COMPLETED, startedAt = 1L, endedAt = 2L, startElapsedRealtimeNanos = 1L,
                endElapsedRealtimeNanos = 2L, localTimeZoneId = "UTC", startSource = StartSource.MANUAL, endSource = null,
                detectorVersion = DetectorVersion(0), locationProfileVersion = LocationProfileVersion(0), createdAt = 1L, updatedAt = 2L
            )
        )
        db.rawTrackPointDao().insert(
            RawTrackPointEntity(
                captureId = "cap", sequenceNumber = 0, capturedAt = 1L, elapsedRealtimeNanos = 1L, receivedAtElapsedRealtimeNanos = null,
                latitude = 10.0, longitude = -20.0, horizontalAccuracyM = 5f, altitudeEllipsoidM = null, altitudeMslM = null,
                verticalAccuracyM = null, speedMps = null, speedAccuracyMps = null, bearingDeg = null, bearingAccuracyDeg = null,
                provider = null, isMock = false, requestProfileId = "p", callbackBatchId = null, detectorStateSnapshot = "TRACKING"
            )
        )
        db.tripDao().insert(TripEntity(id = "trip", status = TripStatus.COMPLETED, name = null, isFavorite = false, motorcycleId = null, routeId = null, notes = null, createdAt = 1L, updatedAt = 1L, deletedAt = null))
        db.diagnosticEventDao().insert(event("ancient", ageDays = 900))

        purger.purge()

        assertEquals(1, db.rawTrackPointDao().countByCapture("cap"))
        assertNotNull(db.tripCaptureDao().findById("cap"))
        assertNotNull(db.tripDao().findById("trip"))
    }

    @Test
    fun aRunWithNothingToPurgeWritesNothingSoTheHousekeepingIsNotItselfNoise() = runTest {
        db.diagnosticEventDao().insert(event("fresh", ageDays = 1))

        val result = purger.purge()

        assertEquals(0, result.total)
        assertEquals(setOf("fresh"), ids())
    }

    @Test
    fun aRunThatPurgesLeavesOneSummaryEventWithTheCounts() = runTest {
        db.diagnosticEventDao().insert(event("old1", ageDays = 30))
        db.diagnosticEventDao().insert(event("old2", ageDays = 31))

        purger.purge()

        val summary = db.diagnosticEventDao().findAll().single { it.eventType == DiagnosticPurger.EVENT_PURGE_COMPLETED }
        assertEquals("2", summary.metadata["purgedByAge"])
        assertEquals("0", summary.metadata["purgedByCapacity"])
    }
}
