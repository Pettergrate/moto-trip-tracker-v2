package com.mototriptracker.app.experiment

import com.mototriptracker.app.core.model.DetectorVersion
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * No `fromJson` exists in production code because nothing needs to read
 * these files back into the app — F0.6's dataset is for external analysis
 * tooling. Parsing with plain `org.json.JSONObject` here is enough to prove
 * the serialization is correct without building an unused deserializer.
 *
 * Needs Robolectric: outside it, `org.json.*` resolves to the Android SDK
 * stub jar, whose methods throw `RuntimeException("Stub!")` at runtime
 * (verified by attempting this without the runner first).
 */
@RunWith(RobolectricTestRunner::class)
class FieldTestSessionJsonTest {

    private fun sampleMetadata() = FieldTestSessionMetadata(
        sessionId = "FT-2026-001",
        startedAt = 1_000L,
        endedAt = 5_000L,
        appVersion = "0.1-w0",
        diagnosticSchemaVersion = 1,
        experimentProfileId = "S1-A",
        detectorVersion = DetectorVersion(1),
        phoneManufacturer = "Google",
        phoneModel = "Pixel",
        androidVersion = "16",
        playServicesVersion = null,
        batterySaverState = "OFF",
        locationSettingsState = "ON",
        preciseLocationGranted = true,
        backgroundLocationGranted = false,
        activityRecognitionGranted = true,
        notificationPermissionState = "GRANTED",
        phonePlacement = "PLACEMENT-NORMAL",
        screenStateAtStart = "OFF",
        routeType = "urban",
        weatherNotes = null,
        notes = null
    )

    @Test
    fun sessionMetadataSerializesAllRequiredFields() {
        val json = JSONObject(FieldTestSessionJson.toJson(sampleMetadata()))

        assertEquals("FT-2026-001", json.getString("sessionId"))
        assertEquals(1_000L, json.getLong("startedAt"))
        assertEquals(5_000L, json.getLong("endedAt"))
        assertEquals(1, json.getInt("detectorVersion"))
        assertTrue(json.getBoolean("preciseLocationGranted"))
        assertTrue(json.isNull("playServicesVersion"))
        assertTrue(json.isNull("weatherNotes"))
        assertTrue(json.isNull("notes"))
    }

    @Test
    fun markersSerializeInOrderWithNullableNotePreserved() {
        val markers = listOf(
            GroundTruthMarker(GroundTruthMarkerType.READY_TO_START, 1_000L, 1_000L),
            GroundTruthMarker(GroundTruthMarkerType.GT_START, 2_000L, 2_000L, note = "left driveway")
        )

        val json = JSONObject(FieldTestSessionJson.toJson(markers))
        val array = json.getJSONArray("markers")

        assertEquals(2, array.length())
        assertEquals("READY_TO_START", array.getJSONObject(0).getString("type"))
        assertTrue(array.getJSONObject(0).isNull("note"))
        assertEquals("GT_START", array.getJSONObject(1).getString("type"))
        assertEquals("left driveway", array.getJSONObject(1).getString("note"))
    }
}
