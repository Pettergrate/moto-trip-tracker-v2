package com.mototriptracker.app.experiment

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes to the `session.json`/`annotations.json` shapes F0.6 §20
 * expects, using `org.json` (bundled in the Android SDK) rather than adding
 * a new JSON dependency for two small, flat structures.
 */
object FieldTestSessionJson {

    fun toJson(metadata: FieldTestSessionMetadata): String {
        val json = JSONObject()
        json.put("sessionId", metadata.sessionId)
        json.put("startedAt", metadata.startedAt)
        json.put("endedAt", metadata.endedAt)
        json.put("appVersion", metadata.appVersion)
        json.put("diagnosticSchemaVersion", metadata.diagnosticSchemaVersion)
        json.put("experimentProfileId", metadata.experimentProfileId)
        json.put("detectorVersion", metadata.detectorVersion.value)
        json.put("phoneManufacturer", metadata.phoneManufacturer)
        json.put("phoneModel", metadata.phoneModel)
        json.put("androidVersion", metadata.androidVersion)
        json.put("playServicesVersion", metadata.playServicesVersion ?: JSONObject.NULL)
        json.put("batterySaverState", metadata.batterySaverState)
        json.put("locationSettingsState", metadata.locationSettingsState)
        json.put("preciseLocationGranted", metadata.preciseLocationGranted)
        json.put("backgroundLocationGranted", metadata.backgroundLocationGranted)
        json.put("activityRecognitionGranted", metadata.activityRecognitionGranted)
        json.put("notificationPermissionState", metadata.notificationPermissionState)
        json.put("phonePlacement", metadata.phonePlacement)
        json.put("screenStateAtStart", metadata.screenStateAtStart)
        json.put("routeType", metadata.routeType)
        json.put("weatherNotes", metadata.weatherNotes ?: JSONObject.NULL)
        json.put("notes", metadata.notes ?: JSONObject.NULL)
        return json.toString()
    }

    fun toJson(markers: List<GroundTruthMarker>): String {
        val array = JSONArray()
        for (marker in markers) {
            val entry = JSONObject()
            entry.put("type", marker.type.name)
            entry.put("wallTimeEpochMs", marker.wallTimeEpochMs)
            entry.put("elapsedRealtimeNanos", marker.elapsedRealtimeNanos)
            entry.put("note", marker.note ?: JSONObject.NULL)
            array.put(entry)
        }
        val root = JSONObject()
        root.put("markers", array)
        return root.toString()
    }
}
