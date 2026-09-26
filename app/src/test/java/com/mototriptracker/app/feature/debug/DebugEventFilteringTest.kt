package com.mototriptracker.app.feature.debug

import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.diagnostics.DiagnosticFormulas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DIA-002 / F0.13 §10.1 "Events": filterable by category and severity, searchable by reason code. */
class DebugEventFilteringTest {

    private fun event(
        category: DiagnosticCategory = DiagnosticCategory.LOCATION,
        type: String = "LOCATION_GAP_STARTED",
        severity: DiagnosticSeverity = DiagnosticSeverity.WARN,
        reason: String? = "NO_FIX",
        captureId: String? = null,
        metadata: Map<String, String> = emptyMap()
    ) = DiagnosticEventEntity(
        eventId = "e", occurredAt = 1L, elapsedRealtimeNanos = null, category = category, eventType = type, severity = severity,
        source = "test", captureId = captureId, tripId = null, correlationId = null, stateBefore = null, stateAfter = null,
        reasonCode = reason, metadata = metadata, appVersion = "t", schemaVersion = 1, detectorVersion = DetectorVersion(0),
        locationProfileVersion = LocationProfileVersion(0), processingVersion = ProcessingVersion(0)
    )

    @Test
    fun anEmptyFilterMatchesEverything() {
        assertTrue(DebugEventFiltering.matches(event(), EventFilter()))
    }

    @Test
    fun theCategoryFilterKeepsOnlyThatCategory() {
        val filter = EventFilter(category = DiagnosticCategory.PERSISTENCE)

        assertTrue(DebugEventFiltering.matches(event(category = DiagnosticCategory.PERSISTENCE), filter))
        assertFalse(DebugEventFiltering.matches(event(category = DiagnosticCategory.LOCATION), filter))
    }

    @Test
    fun theSeverityFilterIsAFloorNotAnExactMatch() {
        val warnUp = EventFilter(minSeverity = DiagnosticSeverity.WARN)

        assertTrue(DebugEventFiltering.matches(event(severity = DiagnosticSeverity.WARN), warnUp))
        assertTrue("ERROR is at least WARN", DebugEventFiltering.matches(event(severity = DiagnosticSeverity.ERROR), warnUp))
        assertFalse(DebugEventFiltering.matches(event(severity = DiagnosticSeverity.INFO), warnUp))
    }

    @Test
    fun theSearchFindsByReasonCodeOrEventTypeIgnoringCase() {
        val byReason = EventFilter(query = "no_fix")
        val byType = EventFilter(query = "gap_started")

        assertTrue(DebugEventFiltering.matches(event(reason = "NO_FIX"), byReason))
        assertTrue(DebugEventFiltering.matches(event(type = "LOCATION_GAP_STARTED"), byType))
        assertFalse(DebugEventFiltering.matches(event(reason = "SIGNAL_RESTORED", type = "LOCATION_GAP_ENDED"), byReason))
    }

    @Test
    fun anEventWithNoReasonCodeIsNotFoundByAReasonSearchButStillByItsType() {
        assertFalse(DebugEventFiltering.matches(event(reason = null, type = "START"), EventFilter(query = "NO_FIX")))
        assertTrue(DebugEventFiltering.matches(event(reason = null, type = "START"), EventFilter(query = "start")))
    }

    @Test
    fun filtersCombine() {
        val filter = EventFilter(category = DiagnosticCategory.LOCATION, minSeverity = DiagnosticSeverity.WARN, query = "gap")

        assertTrue(DebugEventFiltering.matches(event(), filter))
        assertFalse(DebugEventFiltering.matches(event(severity = DiagnosticSeverity.INFO), filter))
        assertFalse(DebugEventFiltering.matches(event(category = DiagnosticCategory.RECOVERY_SYSTEM), filter))
    }

    @Test
    fun aRowShowsAShortNonReversibleCaptureLabelNeverTheRealId() {
        val realId = "3f2a91c4-77aa-4b6e-9d21-0c5e8e1d9b10"

        val row = DebugEventFiltering.toRow(event(captureId = realId, metadata = mapOf("durationMs" to "91000", "detection" to "LIVE")))

        assertEquals(DiagnosticFormulas.shortId(realId), row.shortCaptureId)
        assertFalse("the real id must not appear anywhere in the row", row.toString().contains(realId))
        assertEquals("durationMs=91000 detection=LIVE", row.metadata)
    }

    @Test
    fun aRowWithoutACaptureOrMetadataHasNeither() {
        val row = DebugEventFiltering.toRow(event(captureId = null))

        assertNull(row.shortCaptureId)
        assertNull(row.metadata)
    }
}
