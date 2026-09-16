package com.mototriptracker.app.core.database

import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.testing.TestDatabaseFactory
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
}
