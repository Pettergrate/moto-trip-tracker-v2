package com.mototriptracker.app.experiment

import com.mototriptracker.app.core.model.CapabilityInputs
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unlike `FieldTestSessionJson` (write-only), this round-trips: it exists
 * specifically so a recreated `FieldTestHarnessViewModel` can rebuild its
 * in-progress session after a real process death mid-ride.
 */
@RunWith(RobolectricTestRunner::class)
class FieldTestHarnessStateJsonTest {

    private fun sampleState() = PersistedHarnessState(
        sessionId = "session-1",
        experimentProfileId = "S1-A",
        startedAtWallMs = 10_000L,
        startedAtElapsedNanos = 20_000L,
        phonePlacement = "PLACEMENT-NORMAL",
        routeType = "urban",
        weatherNotes = "clear",
        notes = "",
        deviceSnapshot = FieldTestDeviceSnapshot(
            appVersion = "0.1-w0",
            phoneManufacturer = "HONOR",
            phoneModel = "DNY-NX9",
            androidVersion = "16",
            playServicesVersion = null,
            batterySaverState = "disabled",
            locationSettingsState = "enabled",
            notificationPermissionState = "enabled",
            screenStateAtStart = "on"
        ),
        capabilityInputsAtStart = CapabilityInputs(
            preciseLocationGranted = true,
            approximateLocationGranted = true,
            activityRecognitionGranted = true,
            backgroundLocationGranted = false,
            notificationsEnabled = true,
            locationServicesEnabled = true,
            autoTrackingEnabledByUser = false
        ),
        markers = listOf(
            GroundTruthMarker(GroundTruthMarkerType.READY_TO_START, 21_000L, 21_000L),
            GroundTruthMarker(GroundTruthMarkerType.KNOWN_TUNNEL, 22_000L, 22_000L, note = "under the bridge")
        )
    )

    @Test
    fun toJsonThenFromJsonReproducesTheOriginalState() {
        val original = sampleState()

        val restored = FieldTestHarnessStateJson.fromJson(FieldTestHarnessStateJson.toJson(original))

        assertEquals(original, restored)
    }

    @Test
    fun nullPlayServicesVersionAndEmptyOptionalFieldsSurviveTheRoundTrip() {
        val original = sampleState().copy(weatherNotes = "", notes = "", markers = emptyList())

        val restored = FieldTestHarnessStateJson.fromJson(FieldTestHarnessStateJson.toJson(original))

        assertEquals(original, restored)
        assertEquals(emptyList<GroundTruthMarker>(), restored.markers)
    }
}
