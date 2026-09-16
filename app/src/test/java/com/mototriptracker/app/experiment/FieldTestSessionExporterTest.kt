package com.mototriptracker.app.experiment

import com.mototriptracker.app.core.model.DetectorVersion
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Needs Robolectric — see FieldTestSessionJsonTest for why. */
@RunWith(RobolectricTestRunner::class)
class FieldTestSessionExporterTest {

    @Test
    fun exportWritesSessionAndAnnotationsFiles() {
        val writer = FakeFieldTestDatasetWriter()
        val exporter = FieldTestSessionExporter(writer)
        val metadata = FieldTestSessionMetadata(
            sessionId = "FT-2026-002",
            startedAt = 1_000L,
            endedAt = 9_000L,
            appVersion = "0.1-w0",
            diagnosticSchemaVersion = 1,
            experimentProfileId = "S1-A",
            detectorVersion = DetectorVersion(1),
            phoneManufacturer = "Google",
            phoneModel = "Pixel",
            androidVersion = "16",
            playServicesVersion = "24.0.0",
            batterySaverState = "OFF",
            locationSettingsState = "ON",
            preciseLocationGranted = true,
            backgroundLocationGranted = true,
            activityRecognitionGranted = true,
            notificationPermissionState = "GRANTED",
            phonePlacement = "PLACEMENT-NORMAL",
            screenStateAtStart = "OFF",
            routeType = "mixed"
        )
        val markers = listOf(GroundTruthMarker(GroundTruthMarkerType.GT_START, 1_500L, 1_500L))

        exporter.export(metadata, markers)

        val sessionJson = writer.readFile("FT-2026-002", "session.json")
        val annotationsJson = writer.readFile("FT-2026-002", "annotations.json")
        assertNotNull(sessionJson)
        assertNotNull(annotationsJson)
        assertEquals("FT-2026-002", JSONObject(sessionJson!!).getString("sessionId"))
        assertEquals(1, JSONObject(annotationsJson!!).getJSONArray("markers").length())
    }
}
