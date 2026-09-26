package com.mototriptracker.app.core.database

import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.testing.TestDatabaseFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** DIA-001 acceptance: "event schema exists" — proven by a real round-trip. */
@RunWith(RobolectricTestRunner::class)
class DiagnosticEventDaoTest {

    private lateinit var db: MotoTripDatabase

    @Before
    fun createDb() {
        db = TestDatabaseFactory.createInMemory()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun eventWithMetadataRoundTripsExactly() = runTest {
        val dao = db.diagnosticEventDao()
        val event = DiagnosticEventEntity(
            eventId = "event-1",
            occurredAt = 1_000L,
            elapsedRealtimeNanos = 1_000_000_000L,
            category = DiagnosticCategory.LOCATION,
            eventType = "LOCATION_GAP_STARTED",
            severity = DiagnosticSeverity.WARN,
            source = "tracking-service",
            captureId = "capture-1",
            tripId = null,
            correlationId = "cmd-42",
            stateBefore = "TRACKING",
            stateAfter = "TRACKING",
            reasonCode = "STALE_FIX",
            metadata = mapOf("accuracyBucket" to "POOR", "rejectedPoints" to "4"),
            appVersion = "0.1-w0",
            schemaVersion = 1,
            detectorVersion = DetectorVersion(1),
            locationProfileVersion = LocationProfileVersion(1),
            processingVersion = ProcessingVersion(1)
        )

        dao.insert(event)

        val reloaded = dao.findById("event-1")
        assertEquals(event, reloaded)
        assertEquals(1, dao.count())
    }

    @Test
    fun eventWithEmptyMetadataRoundTrips() = runTest {
        val dao = db.diagnosticEventDao()
        val event = DiagnosticEventEntity(
            eventId = "event-2",
            occurredAt = 2_000L,
            elapsedRealtimeNanos = null,
            category = DiagnosticCategory.USER_COMMAND,
            eventType = "START",
            severity = DiagnosticSeverity.INFO,
            source = "ui",
            captureId = null,
            tripId = null,
            correlationId = null,
            stateBefore = null,
            stateAfter = null,
            reasonCode = null,
            metadata = emptyMap(),
            appVersion = "0.1-w0",
            schemaVersion = 1,
            detectorVersion = DetectorVersion(1),
            locationProfileVersion = LocationProfileVersion(1),
            processingVersion = ProcessingVersion(1)
        )

        dao.insert(event)

        val reloaded = dao.findById("event-2")
        assertNotNull(reloaded)
        assertEquals(emptyMap<String, String>(), reloaded?.metadata)
    }

    private fun gapEvent(id: String, type: String, elapsedNanos: Long, reason: String, captureId: String = "capture-1") = DiagnosticEventEntity(
        eventId = id, occurredAt = elapsedNanos / 1_000_000, elapsedRealtimeNanos = elapsedNanos,
        category = DiagnosticCategory.LOCATION, eventType = type, severity = DiagnosticSeverity.WARN, source = "tracking-service",
        captureId = captureId, tripId = null, correlationId = null, stateBefore = null, stateAfter = null, reasonCode = reason,
        metadata = emptyMap(), appVersion = "0.1-w0", schemaVersion = 1, detectorVersion = DetectorVersion(0),
        locationProfileVersion = LocationProfileVersion(0), processingVersion = ProcessingVersion(0)
    )

    private suspend fun openGapReason(captureId: String = "capture-1") =
        db.diagnosticEventDao().observeOpenGapReason(captureId, "GAP_STARTED", "GAP_ENDED").first()

    /** REC-005: the Active Trip screen reads "is a location gap open, and why" straight from these events. */
    @Test
    fun aCaptureWithNoGapEventsHasNoOpenGap() = runTest {
        assertEquals(null, openGapReason())
    }

    @Test
    fun aStartedWithoutAnEndedIsAnOpenGapWithItsReason() = runTest {
        db.diagnosticEventDao().insert(gapEvent("a", "GAP_STARTED", 1_000_000_000L, "NO_FIX"))

        assertEquals("NO_FIX", openGapReason())
    }

    @Test
    fun aGapThatEndedIsNoLongerOpen() = runTest {
        val dao = db.diagnosticEventDao()
        dao.insert(gapEvent("a", "GAP_STARTED", 1_000_000_000L, "NO_FIX"))
        dao.insert(gapEvent("b", "GAP_ENDED", 5_000_000_000L, "SIGNAL_RESTORED"))

        assertEquals(null, openGapReason())
    }

    /** A fix that ends one gap and is itself the last before the next shares a timestamp with it - the tie must not hide the new gap. */
    @Test
    fun aSecondGapStartingAtTheInstantTheFirstEndedIsStillSeenAsOpen() = runTest {
        val dao = db.diagnosticEventDao()
        dao.insert(gapEvent("a", "GAP_STARTED", 1_000_000_000L, "NO_FIX"))
        dao.insert(gapEvent("b", "GAP_ENDED", 5_000_000_000L, "SIGNAL_RESTORED"))
        dao.insert(gapEvent("c", "GAP_STARTED", 5_000_000_000L, "LOCATION_SERVICES_OFF"))

        assertEquals("LOCATION_SERVICES_OFF", openGapReason())
    }

    @Test
    fun anotherCapturesGapDoesNotLeakIn() = runTest {
        db.diagnosticEventDao().insert(gapEvent("a", "GAP_STARTED", 1_000_000_000L, "NO_FIX", captureId = "capture-2"))

        assertEquals(null, openGapReason("capture-1"))
    }
}
