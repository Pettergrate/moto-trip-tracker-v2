package com.mototriptracker.app.diagnostics.export

import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.diagnostics.DiagnosticFormulas
import com.mototriptracker.app.diagnostics.DiagnosticSnapshot
import com.mototriptracker.app.diagnostics.EventBrief
import com.mototriptracker.app.tracking.recovery.ProcessExitRecorder
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * DIA-003 / F0.13 §11: what may go into a diagnostic package. **Standard means sanitized**: no coordinates, no raw
 * track, no trip names or notes, no real ids, no database dump. Route data is a separate, explicit opt-in
 * (§11.3) - a caller has to ask for it by name, and it is never included otherwise, whatever it was handed.
 */
data class ExportOptions(val includeRouteData: Boolean = false)

/** One file in the package. */
class BundleEntry(val path: String, val content: ByteArray) {
    val text: String get() = content.toString(Charsets.UTF_8)
}

/** The raw track of one capture, as CSV, for the opt-in route attachment. */
data class RouteData(val captureShortId: String, val pointCount: Int, val csv: String)

/**
 * Pure: no Android, no I/O. Everything it needs is passed in, so the sanitization rules can be pinned by tests
 * against a bundle built from a realistic ride.
 *
 * What it does to what it is given:
 * - **ids** (`captureId`, `tripId`, `correlationId`) become the same short non-reversible labels the debug screen
 *   shows ([DiagnosticFormulas.shortId]), so events still correlate inside the package without exposing the ids;
 * - **event metadata** is scrubbed again on the way out (defence in depth: producers already write codes and
 *   counters only, but this is the last gate before a file leaves the app): keys that could name a place or a
 *   person are dropped, and so is any value outside a plain-token vocabulary;
 * - **route data** appears only when [ExportOptions.includeRouteData] is true.
 */
object DiagnosticBundleBuilder {

    const val FORMAT_VERSION = 1

    /** What the standard package promises never to contain (F0.13 §11.2), written into the manifest and README. */
    val EXCLUSIONS: List<String> = listOf(
        "GPS coordinates", "raw track", "custom trip names", "notes", "photos", "home/work addresses",
        "advertising or hardware ids", "tokens or credentials", "database dumps", "real capture/trip ids (short hashes only)"
    )

    fun build(
        generatedAtMillis: Long,
        snapshot: DiagnosticSnapshot,
        events: List<DiagnosticEventEntity>,
        route: RouteData?,
        options: ExportOptions
    ): List<BundleEntry> {
        val ordered = events.sortedBy { it.occurredAt }
        var scrubbed = 0
        val eventLines = ordered.map { event ->
            val (json, dropped) = eventJson(event)
            scrubbed += dropped
            json.toString()
        }

        // Route data goes in only on an explicit request - never because it happened to be passed.
        val includedRoute = if (options.includeRouteData) route else null

        val entries = mutableListOf<BundleEntry>()
        entries += entry("versions.json", versionsJson(snapshot))
        entries += entry("capabilities.json", capabilitiesJson(snapshot))
        entries += entry("health-snapshot.json", healthJson(snapshot))
        entries += entry("worker-state.json", workerJson(snapshot))
        entries += entry("process-exits.json", processExitsJson(ordered))
        entries += BundleEntry("diagnostic-events.jsonl", eventLines.joinToString("\n", postfix = if (eventLines.isEmpty()) "" else "\n").toByteArray(Charsets.UTF_8))
        if (includedRoute != null) {
            entries += BundleEntry("route/raw-track-${includedRoute.captureShortId}.csv", includedRoute.csv.toByteArray(Charsets.UTF_8))
            entries += BundleEntry("route/README.txt", routeReadme(includedRoute).toByteArray(Charsets.UTF_8))
        }
        entries += BundleEntry("README.txt", readme(options, includedRoute != null).toByteArray(Charsets.UTF_8))
        // Last, so it can list every other file.
        entries += entry("manifest.json", manifestJson(generatedAtMillis, snapshot, ordered.size, scrubbed, options, includedRoute, entries.map { it.path } + "manifest.json"))
        return entries
    }

    private fun entry(path: String, json: JSONObject) = BundleEntry(path, json.toString(2).toByteArray(Charsets.UTF_8))

    private fun entry(path: String, json: JSONArray) = BundleEntry(path, json.toString(2).toByteArray(Charsets.UTF_8))

    private fun iso(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    private fun manifestJson(
        generatedAt: Long, snapshot: DiagnosticSnapshot, eventCount: Int, scrubbedMetadata: Int,
        options: ExportOptions, route: RouteData?, files: List<String>
    ) = JSONObject().apply {
        put("formatVersion", FORMAT_VERSION)
        put("kind", "moto-trip-tracker diagnostic package")
        put("generatedAt", iso(generatedAt))
        put("appVersion", snapshot.app.versionName)
        put("includesRouteData", options.includeRouteData)
        if (options.includeRouteData) put("routeData", if (route != null) "1 capture, ${route.pointCount} points" else "requested but no capture was available")
        put("eventCount", eventCount)
        put("metadataEntriesScrubbed", scrubbedMetadata)
        put("files", JSONArray(files))
        put("excludes", JSONArray(if (options.includeRouteData) EXCLUSIONS.filterNot { it == "GPS coordinates" || it == "raw track" } else EXCLUSIONS))
        put("uploaded", false)
    }

    private fun versionsJson(s: DiagnosticSnapshot) = JSONObject().apply {
        put("appVersion", s.app.versionName)
        put("versionCode", s.app.versionCode)
        put("androidApi", s.app.androidApi)
        put("device", s.app.device)
        put("databaseSchemaVersion", s.app.databaseSchemaVersion)
        put("detectorVersion", s.app.detectorVersion)
        put("locationProfileVersion", s.app.locationProfileVersion)
        put("processingVersion", s.app.processingVersion)
    }

    private fun capabilitiesJson(s: DiagnosticSnapshot) = JSONObject().apply {
        val c = s.capabilities
        put("mode", c.mode.name)
        put("preciseLocation", c.preciseLocation)
        put("approximateLocation", c.approximateLocation)
        put("backgroundLocation", c.backgroundLocation)
        put("activityRecognition", c.activityRecognition)
        put("notifications", c.notifications)
        put("locationServices", c.locationServices)
        put("batterySaver", c.batterySaver ?: JSONObject.NULL)
        put("autoTrackingToggle", c.autoTrackingToggle)
    }

    private fun healthJson(s: DiagnosticSnapshot) = JSONObject().apply {
        put("health", s.tracking.health.name)
        put("tracking", JSONObject().apply {
            put("activeCapture", s.tracking.activeCapture)
            put("captureLabel", s.tracking.shortCaptureId ?: JSONObject.NULL)
            put("foregroundNotificationShown", s.tracking.foregroundNotificationShown ?: JSONObject.NULL)
            put("lastPersistenceAgeMs", s.tracking.lastPersistenceAgeMs ?: JSONObject.NULL)
            put("persistence", JSONObject().apply {
                put("level", s.tracking.persistence.level.name)
                put("storageFull", s.tracking.persistence.storageFull)
                put("pointsLost", s.tracking.persistence.pointsLost)
            })
        })
        // A fix is described by its age, accuracy and interval - never by where it was.
        put("location", JSONObject().apply {
            val l = s.location
            put("hasActiveCapture", l.hasActiveCapture)
            put("pointCount", l.pointCount ?: JSONObject.NULL)
            put("lastFixAgeMs", l.lastFixAgeMs ?: JSONObject.NULL)
            put("lastAccuracyM", l.lastAccuracyM?.toDouble() ?: JSONObject.NULL)
            put("lastFixHadSpeed", l.lastFixHadSpeed ?: JSONObject.NULL)
            put("effectiveIntervalMs", l.effectiveIntervalMs ?: JSONObject.NULL)
            put("gapActive", l.gapActive)
            put("gapReason", l.gapReason ?: JSONObject.NULL)
            put("approximateOnly", l.approximateOnly)
            put("lastTripRejectedPoints", l.lastTripRejectedPoints ?: JSONObject.NULL)
            put("lastTripGapCount", l.lastTripGapCount ?: JSONObject.NULL)
        })
        put("detector", JSONObject().apply {
            put("note", "current detector state is not persisted; these are the recorded events")
            put("recent", JSONArray(s.detector.recent.map(::briefJson)))
            put("lastActivityTransition", s.detector.lastActivityTransition?.let(::briefJson) ?: JSONObject.NULL)
        })
        put("recovery", JSONObject().apply {
            put("lastProcessExit", s.recovery.lastProcessExit?.let(::briefJson) ?: JSONObject.NULL)
            put("lastRecoveryAction", s.recovery.lastRecoveryAction?.let(::briefJson) ?: JSONObject.NULL)
            put("openInconsistencies", JSONArray(s.recovery.openInconsistencies))
        })
    }

    private fun briefJson(b: EventBrief) = JSONObject().apply {
        put("occurredAt", iso(b.occurredAt))
        put("eventType", b.eventType)
        put("reasonCode", b.reasonCode ?: JSONObject.NULL)
        put("detail", b.detail ?: JSONObject.NULL)
    }

    private fun workerJson(s: DiagnosticSnapshot) = JSONObject().apply {
        put("note", "what WorkManager still remembers, not a lifetime total")
        put("pending", s.processing.pending)
        put("running", s.processing.running)
        put("succeeded", s.processing.succeeded)
        put("failed", s.processing.failed)
        put("maxAttemptCount", s.processing.maxAttemptCount)
    }

    private fun processExitsJson(events: List<DiagnosticEventEntity>) = JSONArray(
        events.filter { it.eventType == ProcessExitRecorder.EVENT_PROCESS_EXIT }.map { e ->
            JSONObject().apply {
                put("occurredAt", iso(e.occurredAt))
                put("reason", e.reasonCode ?: JSONObject.NULL)
                put("severity", e.severity.name)
                val safe = scrubMetadata(e.metadata).first
                put("importance", safe["importance"] ?: JSONObject.NULL)
                put("status", safe["status"] ?: JSONObject.NULL)
                put("stateSummary", safe["stateSummary"] ?: JSONObject.NULL)
            }
        }
    )

    private fun eventJson(e: DiagnosticEventEntity): Pair<JSONObject, Int> {
        val (metadata, dropped) = scrubMetadata(e.metadata)
        val json = JSONObject().apply {
            put("eventId", e.eventId)
            put("occurredAt", iso(e.occurredAt))
            put("elapsedRealtimeNanos", e.elapsedRealtimeNanos ?: JSONObject.NULL)
            put("category", e.category.name)
            put("eventType", e.eventType)
            put("severity", e.severity.name)
            put("source", e.source)
            put("captureLabel", e.captureId?.let(DiagnosticFormulas::shortId) ?: JSONObject.NULL)
            put("tripLabel", e.tripId?.let(DiagnosticFormulas::shortId) ?: JSONObject.NULL)
            put("correlationLabel", e.correlationId?.let(DiagnosticFormulas::shortId) ?: JSONObject.NULL)
            put("stateBefore", e.stateBefore ?: JSONObject.NULL)
            put("stateAfter", e.stateAfter ?: JSONObject.NULL)
            put("reasonCode", e.reasonCode ?: JSONObject.NULL)
            put("metadata", JSONObject(metadata))
            put("appVersion", e.appVersion)
            put("schemaVersion", e.schemaVersion)
            put("detectorVersion", e.detectorVersion.value)
            put("locationProfileVersion", e.locationProfileVersion.value)
            put("processingVersion", e.processingVersion.value)
        }
        return json to dropped
    }

    /**
     * The last gate on event metadata. Producers write codes and counters only, so in practice nothing is
     * dropped; this exists so that a future producer's mistake cannot leave the app in a file. Returns the
     * surviving entries and how many were dropped.
     */
    fun scrubMetadata(metadata: Map<String, String>): Pair<Map<String, String>, Int> {
        val kept = LinkedHashMap<String, String>()
        var dropped = 0
        for ((key, value) in metadata) {
            if (riskyKey(key) || !SAFE_VALUE.matches(value)) dropped++ else kept[key] = value
        }
        return kept to dropped
    }

    private fun riskyKey(key: String): Boolean {
        val k = key.lowercase()
        return k == "lat" || k == "lon" || k == "lng" || k.contains("latitude") || k.contains("longitude") || k.contains("coord") ||
            k.contains("name") || k.contains("note") || k.contains("address") || k.contains("position") || k.contains("location")
    }

    /** Plain tokens and numbers: letters, digits and the punctuation of `key=value;key=value` and of a number. No quotes, no free text. */
    private val SAFE_VALUE = Regex("^[A-Za-z0-9_=;.:,+\\- ]{0,200}$")

    private fun readme(options: ExportOptions, routeIncluded: Boolean): String = buildString {
        appendLine("Moto Trip Tracker - diagnostic package")
        appendLine()
        appendLine("Made on request from the app's internal diagnostics screen. Nothing here was uploaded: this file only")
        appendLine("goes where the person who made it chooses to share it.")
        appendLine()
        appendLine("Contents")
        appendLine("  manifest.json           what is in this package and how it was made")
        appendLine("  versions.json           app, Android, device model, schema and algorithm versions")
        appendLine("  capabilities.json       permissions and services the app saw (no data)")
        appendLine("  health-snapshot.json    health state, persistence, signal and recovery at the moment of export")
        appendLine("  worker-state.json       trip-processing work counts")
        appendLine("  process-exits.json      how earlier processes ended (reason, importance, state at the time)")
        appendLine("  diagnostic-events.jsonl one event per line, oldest first")
        appendLine()
        if (routeIncluded) {
            appendLine("ROUTE DATA IS INCLUDED. The 'route' folder holds the raw track of one recording: precise locations.")
            appendLine("Share this file only with someone you trust with where you were.")
        } else if (options.includeRouteData) {
            appendLine("Route data was requested, but no recording was available to attach.")
        } else {
            appendLine("Not included, by design: ${EXCLUSIONS.joinToString(", ")}.")
            appendLine("Capture and trip ids appear only as short one-way labels, so events can be correlated without")
            appendLine("revealing the real ids.")
        }
    }

    private fun routeReadme(route: RouteData): String = buildString {
        appendLine("Route data - PRECISE LOCATIONS")
        appendLine()
        appendLine("raw-track-${route.captureShortId}.csv is the raw GPS track of one recording (${route.pointCount} points):")
        appendLine("latitude, longitude, accuracy, speed and timestamps of where the phone was.")
        appendLine("It was added because the person exporting this package explicitly asked for route data.")
    }
}
