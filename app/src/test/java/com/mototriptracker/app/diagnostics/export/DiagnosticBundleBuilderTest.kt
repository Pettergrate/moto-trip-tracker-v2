package com.mototriptracker.app.diagnostics.export

import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.model.CapabilityMode
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.diagnostics.AppSection
import com.mototriptracker.app.diagnostics.CapabilitiesSection
import com.mototriptracker.app.diagnostics.DetectorSection
import com.mototriptracker.app.diagnostics.DiagnosticFormulas
import com.mototriptracker.app.diagnostics.DiagnosticSnapshot
import com.mototriptracker.app.diagnostics.EventBrief
import com.mototriptracker.app.diagnostics.HealthState
import com.mototriptracker.app.diagnostics.LocationSection
import com.mototriptracker.app.diagnostics.ProcessingSection
import com.mototriptracker.app.diagnostics.RecoverySection
import com.mototriptracker.app.diagnostics.TrackingSection
import com.mototriptracker.app.tracking.persistence.PersistenceState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DIA-003 / F0.13 §11 and §18.1: "export estándar excluye route data; export con route data exige opción
 * explícita". The bundle is built from a realistic ride full of things that must not leave the app - a real capture
 * id, a trip name, a note, coordinates - and every entry is scanned for them.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticBundleBuilderTest {

    private val realCaptureId = "3f2a91c4-77aa-4b6e-9d21-0c5e8e1d9b10"
    private val realTripId = "9c1d7e52-0b34-4a8f-b6d0-2f4a1c7e3a99"
    private val realCorrelationId = "cmd-7d41e2aa-90c1-4f7b-8a3e-5b2d6c1f0e11"
    private val secretName = "Casa de Mama"
    private val secretNote = "meet at the red gate"
    private val latitude = "10.483211"
    private val longitude = "-84.219847"

    private val snapshot = DiagnosticSnapshot(
        app = AppSection("0.1-w0", 1, 36, "HONOR DNY-NX9", 3, 0, 0, 0),
        capabilities = CapabilitiesSection(true, true, true, true, true, true, false, false, CapabilityMode.MANUAL),
        detector = DetectorSection(recent = listOf(EventBrief(1_000L, "DETECTOR_STATE_CHANGED", "CONFIRMED", null)), lastActivityTransition = null),
        location = LocationSection(true, 42, 1_500L, 5f, true, 2_000L, false, null, false, 0, 2),
        tracking = TrackingSection(true, DiagnosticFormulas.shortId(realCaptureId), true, 1_500L, PersistenceState.HEALTHY, HealthState.HEALTHY),
        processing = ProcessingSection(0, 0, 19, 0, 1),
        recovery = RecoverySection(EventBrief(2_000L, "PROCESS_EXIT", "SIGNALED", "importance=FOREGROUND"), null, emptyList())
    )

    private fun event(
        id: String, at: Long, type: String = "LOCATION_GAP_STARTED", reason: String? = "NO_FIX",
        capture: String? = realCaptureId, trip: String? = realTripId, correlation: String? = realCorrelationId,
        metadata: Map<String, String> = mapOf("silenceMsWhenDetected" to "37645", "detection" to "LIVE")
    ) = DiagnosticEventEntity(
        eventId = id, occurredAt = at, elapsedRealtimeNanos = at * 1_000L, category = DiagnosticCategory.LOCATION, eventType = type,
        severity = DiagnosticSeverity.WARN, source = "tracking-service", captureId = capture, tripId = trip, correlationId = correlation,
        stateBefore = "TRACKING", stateAfter = "TRACKING", reasonCode = reason, metadata = metadata, appVersion = "0.1-w0", schemaVersion = 1,
        detectorVersion = DetectorVersion(0), locationProfileVersion = LocationProfileVersion(0), processingVersion = ProcessingVersion(0)
    )

    private val events = listOf(
        event("e2", 2_000L),
        event("e1", 1_000L, type = "START", reason = "NEW_CAPTURE", metadata = emptyMap()),
        event(
            "e3", 3_000L, type = "PROCESS_EXIT", reason = "SIGNALED",
            metadata = mapOf("importance" to "FOREGROUND_SERVICE", "status" to "9", "stateSummary" to "v=1;cap=Y;pause=N;sig=OK;apx=N;db=OK;app=0.1-w0")
        ),
        // What a careless future producer might write: none of it may leave the app.
        event(
            "e4", 4_000L, metadata = mapOf(
                "latitude" to latitude, "lon" to longitude, "tripName" to secretName, "note" to secretNote,
                "reason" to "it said \"$secretNote\"", "ok" to "42"
            )
        )
    )

    private val route = RouteData(
        captureShortId = DiagnosticFormulas.shortId(realCaptureId), pointCount = 2,
        csv = "sequenceNumber,latitude,longitude\n0,$latitude,$longitude\n1,$latitude,$longitude"
    )

    private fun standard() = DiagnosticBundleBuilder.build(9_000_000L, snapshot, events, route, ExportOptions())

    private fun withRoute(route: RouteData? = this.route) =
        DiagnosticBundleBuilder.build(9_000_000L, snapshot, events, route, ExportOptions(includeRouteData = true))

    private fun List<BundleEntry>.byPath(path: String) = single { it.path == path }

    @Test
    fun theStandardPackageHasExactlyTheSpecifiedFilesAndNoRouteFolder() {
        val paths = standard().map { it.path }

        assertEquals(
            listOf(
                "versions.json", "capabilities.json", "health-snapshot.json", "worker-state.json", "process-exits.json",
                "diagnostic-events.jsonl", "README.txt", "manifest.json"
            ),
            paths
        )
        assertTrue(paths.none { it.startsWith("route/") })
    }

    /** F0.13 §11.2: the standard package never contains these, whatever it was handed. */
    @Test
    fun noEntryOfTheStandardPackageContainsACoordinateARealIdANameOrANote() {
        val everything = standard().joinToString("\n") { "${it.path}\n${it.text}" }

        listOf(latitude, longitude, realCaptureId, realTripId, realCorrelationId, secretName, secretNote).forEach { forbidden ->
            assertFalse("found in the standard package: $forbidden", everything.contains(forbidden))
        }
    }

    /** F0.13 §18.1: route data needs an explicit option - handing it over is not asking for it. */
    @Test
    fun routeDataPassedInIsIgnoredUnlessTheOptionIsExplicitlyOn() {
        val entries = DiagnosticBundleBuilder.build(9_000_000L, snapshot, events, route, ExportOptions(includeRouteData = false))

        assertTrue(entries.none { it.path.startsWith("route/") })
        assertFalse(entries.joinToString { it.text }.contains(latitude))
        assertFalse(JSONObject(entries.byPath("manifest.json").text).getBoolean("includesRouteData"))
    }

    @Test
    fun theDefaultOptionsDoNotIncludeRouteData() {
        assertFalse(ExportOptions().includeRouteData)
    }

    @Test
    fun withTheExplicitOptionTheRouteIsAddedAndTheManifestAndReadmeSayLoudlyThatItIs() {
        val entries = withRoute()
        val csvPath = "route/raw-track-${DiagnosticFormulas.shortId(realCaptureId)}.csv"

        assertTrue(entries.any { it.path == csvPath })
        assertTrue("positive control: the route really carries the coordinates", entries.byPath(csvPath).text.contains(latitude))
        val manifest = JSONObject(entries.byPath("manifest.json").text)
        assertTrue(manifest.getBoolean("includesRouteData"))
        assertEquals("1 capture, 2 points", manifest.getString("routeData"))
        assertFalse("no longer promises to exclude coordinates", manifest.getJSONArray("excludes").toString().contains("GPS coordinates"))
        assertTrue(entries.byPath("README.txt").text.contains("ROUTE DATA IS INCLUDED"))
        assertTrue(entries.byPath("route/README.txt").text.contains("PRECISE LOCATIONS"))
    }

    /** Even with the option on, only the route folder may carry coordinates - not the events, snapshot or manifest. */
    @Test
    fun evenWithRouteDataOnlyTheRouteFolderCarriesCoordinates() {
        val leaks = withRoute().filterNot { it.path.startsWith("route/") }.filter { it.text.contains(latitude) || it.text.contains(longitude) }

        assertTrue("outside route/: ${leaks.map { it.path }}", leaks.isEmpty())
    }

    @Test
    fun askingForRouteDataWhenThereIsNoRecordingAddsNothingAndSaysSo() {
        val entries = withRoute(route = null)

        assertTrue(entries.none { it.path.startsWith("route/") })
        assertEquals("requested but no capture was available", JSONObject(entries.byPath("manifest.json").text).getString("routeData"))
        assertTrue(entries.byPath("README.txt").text.contains("no recording was available"))
    }

    @Test
    fun idsBecomeShortLabelsThatStillCorrelateInsideThePackage() {
        val lines = standard().byPath("diagnostic-events.jsonl").text.trim().lines().map(::JSONObject)

        val expected = DiagnosticFormulas.shortId(realCaptureId)
        assertTrue(lines.all { it.getString("captureLabel") == expected })
        assertEquals(DiagnosticFormulas.shortId(realTripId), lines.first().getString("tripLabel"))
        assertEquals(DiagnosticFormulas.shortId(realCorrelationId), lines.first().getString("correlationLabel"))
    }

    @Test
    fun eventsAreOneValidJsonObjectPerLineOldestFirst() {
        val lines = standard().byPath("diagnostic-events.jsonl").text.trim().lines()

        assertEquals(4, lines.size)
        assertEquals(listOf("e1", "e2", "e3", "e4"), lines.map { JSONObject(it).getString("eventId") })
    }

    @Test
    fun metadataThatCouldNameAPlaceOrAPersonIsDroppedAndCounted() {
        val e4 = standard().byPath("diagnostic-events.jsonl").text.trim().lines().map(::JSONObject).single { it.getString("eventId") == "e4" }
        val metadata = e4.getJSONObject("metadata")

        assertEquals(listOf("ok"), metadata.keys().asSequence().toList())
        assertEquals("42", metadata.getString("ok"))
        assertEquals("the free-text value and the four risky keys", 5, JSONObject(standard().byPath("manifest.json").text).getInt("metadataEntriesScrubbed"))
    }

    @Test
    fun theProcessExitsFileHasOnlyExitsWithTheirStateAtTheTime() {
        val exits = JSONArray(standard().byPath("process-exits.json").text)

        assertEquals(1, exits.length())
        val exit = exits.getJSONObject(0)
        assertEquals("SIGNALED", exit.getString("reason"))
        assertEquals("FOREGROUND_SERVICE", exit.getString("importance"))
        assertEquals("v=1;cap=Y;pause=N;sig=OK;apx=N;db=OK;app=0.1-w0", exit.getString("stateSummary"))
    }

    @Test
    fun everyJsonFileParsesAndTheManifestListsEveryFileIncludingItself() {
        val entries = standard()

        entries.filter { it.path.endsWith(".json") }.forEach { assertNotNull(it.path, runCatching { JSONObject(it.text) }.getOrNull() ?: JSONArray(it.text)) }
        val listed = JSONObject(entries.byPath("manifest.json").text).getJSONArray("files").let { arr -> (0 until arr.length()).map(arr::getString) }
        assertEquals(entries.map { it.path }.sorted(), listed.sorted())
    }

    @Test
    fun theManifestStatesTheFormatTheDateAndThatNothingWasUploaded() {
        val manifest = JSONObject(standard().byPath("manifest.json").text)

        assertEquals(DiagnosticBundleBuilder.FORMAT_VERSION, manifest.getInt("formatVersion"))
        assertEquals("1970-01-01T02:30:00Z", manifest.getString("generatedAt"))
        assertFalse(manifest.getBoolean("uploaded"))
        assertEquals(4, manifest.getInt("eventCount"))
        assertTrue(standard().byPath("README.txt").text.contains("Nothing here was uploaded"))
    }

    @Test
    fun aSnapshotFactTheAppCouldNotKnowStaysNullRatherThanBecomingAGuess() {
        val unknown = snapshot.copy(capabilities = snapshot.capabilities.copy(batterySaver = null), tracking = snapshot.tracking.copy(foregroundNotificationShown = null))
        val entries = DiagnosticBundleBuilder.build(0L, unknown, emptyList(), null, ExportOptions())

        assertTrue(JSONObject(entries.byPath("capabilities.json").text).isNull("batterySaver"))
        assertTrue(JSONObject(entries.byPath("health-snapshot.json").text).getJSONObject("tracking").isNull("foregroundNotificationShown"))
        assertEquals("", entries.byPath("diagnostic-events.jsonl").text)
    }
}
