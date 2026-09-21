package com.mototriptracker.app.experiment

import com.mototriptracker.app.core.model.CapabilityInputs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Unlike [FieldTestSessionJson] (write-only - F0.6's exported dataset is for
 * external analysis tooling, nothing in-app ever reads it back), this needs
 * [fromJson] too: [FieldTestHarnessStateStore] exists specifically so a
 * recreated `FieldTestHarnessViewModel` can rebuild its in-progress session
 * after the app process was killed mid-ride.
 */
object FieldTestHarnessStateJson {

    fun toJson(state: PersistedHarnessState): String {
        val json = JSONObject()
        json.put("sessionId", state.sessionId)
        json.put("experimentProfileId", state.experimentProfileId)
        json.put("startedAtWallMs", state.startedAtWallMs)
        json.put("startedAtElapsedNanos", state.startedAtElapsedNanos)
        json.put("phonePlacement", state.phonePlacement)
        json.put("routeType", state.routeType)
        json.put("weatherNotes", state.weatherNotes)
        json.put("notes", state.notes)
        json.put("associatedCaptureId", state.associatedCaptureId ?: JSONObject.NULL)
        json.put(
            "deviceSnapshot",
            JSONObject().apply {
                put("appVersion", state.deviceSnapshot.appVersion)
                put("phoneManufacturer", state.deviceSnapshot.phoneManufacturer)
                put("phoneModel", state.deviceSnapshot.phoneModel)
                put("androidVersion", state.deviceSnapshot.androidVersion)
                put("playServicesVersion", state.deviceSnapshot.playServicesVersion ?: JSONObject.NULL)
                put("batterySaverState", state.deviceSnapshot.batterySaverState)
                put("locationSettingsState", state.deviceSnapshot.locationSettingsState)
                put("notificationPermissionState", state.deviceSnapshot.notificationPermissionState)
                put("screenStateAtStart", state.deviceSnapshot.screenStateAtStart)
            }
        )
        json.put(
            "capabilityInputsAtStart",
            JSONObject().apply {
                put("preciseLocationGranted", state.capabilityInputsAtStart.preciseLocationGranted)
                put("approximateLocationGranted", state.capabilityInputsAtStart.approximateLocationGranted)
                put("activityRecognitionGranted", state.capabilityInputsAtStart.activityRecognitionGranted)
                put("backgroundLocationGranted", state.capabilityInputsAtStart.backgroundLocationGranted)
                put("notificationsEnabled", state.capabilityInputsAtStart.notificationsEnabled)
                put("locationServicesEnabled", state.capabilityInputsAtStart.locationServicesEnabled)
                put("autoTrackingEnabledByUser", state.capabilityInputsAtStart.autoTrackingEnabledByUser)
            }
        )
        val markersArray = JSONArray()
        state.markers.forEach { marker ->
            markersArray.put(
                JSONObject().apply {
                    put("type", marker.type.name)
                    put("wallTimeEpochMs", marker.wallTimeEpochMs)
                    put("elapsedRealtimeNanos", marker.elapsedRealtimeNanos)
                    put("note", marker.note ?: JSONObject.NULL)
                }
            )
        }
        json.put("markers", markersArray)
        return json.toString()
    }

    fun fromJson(content: String): PersistedHarnessState {
        val json = JSONObject(content)

        val deviceJson = json.getJSONObject("deviceSnapshot")
        val deviceSnapshot = FieldTestDeviceSnapshot(
            appVersion = deviceJson.getString("appVersion"),
            phoneManufacturer = deviceJson.getString("phoneManufacturer"),
            phoneModel = deviceJson.getString("phoneModel"),
            androidVersion = deviceJson.getString("androidVersion"),
            playServicesVersion = if (deviceJson.isNull("playServicesVersion")) null else deviceJson.getString("playServicesVersion"),
            batterySaverState = deviceJson.getString("batterySaverState"),
            locationSettingsState = deviceJson.getString("locationSettingsState"),
            notificationPermissionState = deviceJson.getString("notificationPermissionState"),
            screenStateAtStart = deviceJson.getString("screenStateAtStart")
        )

        val capabilityJson = json.getJSONObject("capabilityInputsAtStart")
        val capabilityInputs = CapabilityInputs(
            preciseLocationGranted = capabilityJson.getBoolean("preciseLocationGranted"),
            approximateLocationGranted = capabilityJson.getBoolean("approximateLocationGranted"),
            activityRecognitionGranted = capabilityJson.getBoolean("activityRecognitionGranted"),
            backgroundLocationGranted = capabilityJson.getBoolean("backgroundLocationGranted"),
            notificationsEnabled = capabilityJson.getBoolean("notificationsEnabled"),
            locationServicesEnabled = capabilityJson.getBoolean("locationServicesEnabled"),
            autoTrackingEnabledByUser = capabilityJson.getBoolean("autoTrackingEnabledByUser")
        )

        val markersJson = json.getJSONArray("markers")
        val markers = (0 until markersJson.length()).map { index ->
            val markerJson = markersJson.getJSONObject(index)
            GroundTruthMarker(
                type = GroundTruthMarkerType.valueOf(markerJson.getString("type")),
                wallTimeEpochMs = markerJson.getLong("wallTimeEpochMs"),
                elapsedRealtimeNanos = markerJson.getLong("elapsedRealtimeNanos"),
                note = if (markerJson.isNull("note")) null else markerJson.getString("note")
            )
        }

        return PersistedHarnessState(
            sessionId = json.getString("sessionId"),
            experimentProfileId = json.getString("experimentProfileId"),
            startedAtWallMs = json.getLong("startedAtWallMs"),
            startedAtElapsedNanos = json.getLong("startedAtElapsedNanos"),
            phonePlacement = json.getString("phonePlacement"),
            routeType = json.getString("routeType"),
            weatherNotes = json.getString("weatherNotes"),
            notes = json.getString("notes"),
            associatedCaptureId = if (json.isNull("associatedCaptureId")) null else json.getString("associatedCaptureId"),
            deviceSnapshot = deviceSnapshot,
            capabilityInputsAtStart = capabilityInputs,
            markers = markers
        )
    }
}
