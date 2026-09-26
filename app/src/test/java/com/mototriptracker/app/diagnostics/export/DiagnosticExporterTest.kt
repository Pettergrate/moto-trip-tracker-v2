package com.mototriptracker.app.diagnostics.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.diagnostics.DiagnosticFormulas
import com.mototriptracker.app.diagnostics.DiagnosticSnapshotProvider
import com.mototriptracker.app.diagnostics.ProcessingSection
import com.mototriptracker.app.diagnostics.ProcessingWorkReader
import com.mototriptracker.app.diagnostics.SystemDiagnosticsInfo
import com.mototriptracker.app.testing.FakeCapabilityInputsProvider
import com.mototriptracker.app.testing.FakeCapabilityProvider
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.persistence.PersistenceHealthBus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

/** DIA-003 / F0.13 §11: a real ZIP on disk, in the cache only, with the route in it only when explicitly asked. */
@RunWith(RobolectricTestRunner::class)
class DiagnosticExporterTest {

    private class Quiet : SystemDiagnosticsInfo {
        override fun deviceSummary() = "Acme Phone 9"
        override fun androidApi() = 36
        override fun batterySaverOn() = false
        override fun trackingNotificationShown() = true
    }

    private class NoWork : ProcessingWorkReader {
        override suspend fun counts() = ProcessingSection(0, 0, 0, 0, 0)
    }

    private lateinit var context: Context
    private lateinit var db: MotoTripDatabase
    private lateinit var clock: FakeClock
    private lateinit var exporter: DiagnosticExporter

    private val captureId = "3f2a91c4-77aa-4b6e-9d21-0c5e8e1d9b10"
    private val latitude = "10.483211"
    private val longitude = "-84.219847"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabaseFactory.createInMemory()
        clock = FakeClock(wallMillis = 1_800_000_000_000L, elapsedNanos = 100_000_000_000L)
        val snapshotProvider = DiagnosticSnapshotProvider(
            database = db, tripCaptureDao = db.tripCaptureDao(), rawTrackPointDao = db.rawTrackPointDao(),
            diagnosticEventDao = db.diagnosticEventDao(), tripDao = db.tripDao(), tripStatisticsDao = db.tripStatisticsDao(),
            capabilityInputsProvider = FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()),
            system = Quiet(), processingWork = NoWork(), persistenceHealthBus = PersistenceHealthBus(), clock = clock
        )
        exporter = DiagnosticExporter(context, snapshotProvider, db.diagnosticEventDao(), db.tripCaptureDao(), db.rawTrackPointDao(), clock)
        File(context.cacheDir, DiagnosticExporter.DIRECTORY).deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
        File(context.cacheDir, DiagnosticExporter.DIRECTORY).deleteRecursively()
    }

    private suspend fun capture(status: CaptureStatus) = db.tripCaptureDao().insert(
        TripCaptureEntity(
            id = captureId, status = status, startedAt = 1L, endedAt = if (status == CaptureStatus.ACTIVE) null else 2L,
            startElapsedRealtimeNanos = 1L, endElapsedRealtimeNanos = if (status == CaptureStatus.ACTIVE) null else 2L,
            localTimeZoneId = "UTC", startSource = StartSource.MANUAL, endSource = null, detectorVersion = DetectorVersion(0),
            locationProfileVersion = LocationProfileVersion(0), createdAt = 1L, updatedAt = 1L
        )
    )

    private suspend fun points(n: Int) = (0 until n).forEach { seq ->
        db.rawTrackPointDao().insert(
            RawTrackPointEntity(
                captureId = captureId, sequenceNumber = seq.toLong(), capturedAt = seq.toLong(), elapsedRealtimeNanos = 90_000_000_000L + seq * 2_000_000_000L,
                receivedAtElapsedRealtimeNanos = null, latitude = latitude.toDouble(), longitude = longitude.toDouble(), horizontalAccuracyM = 5f,
                altitudeEllipsoidM = null, altitudeMslM = null, verticalAccuracyM = null, speedMps = null, speedAccuracyMps = null,
                bearingDeg = null, bearingAccuracyDeg = null, provider = "fused", isMock = false, requestProfileId = "p",
                callbackBatchId = null, detectorStateSnapshot = "TRACKING"
            )
        )
    }

    private fun contents(file: File): Map<String, String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun aStandardExportIsARealZipInTheCacheNamedByTheMomentItWasMade() = runTest {
        capture(CaptureStatus.ACTIVE)
        points(5)

        val export = exporter.export(ExportOptions())

        assertTrue(export.file.exists())
        assertTrue("cache only, never shared storage", export.file.absolutePath.startsWith(context.cacheDir.absolutePath))
        assertTrue(export.file.name.matches(Regex("diagnostic-\\d{8}-\\d{6}\\.zip")))
        assertFalse(export.includesRouteData)
        assertEquals(export.entries.sorted(), contents(export.file).keys.toList().sorted())
    }

    @Test
    fun theStandardZipContainsNoCoordinateAndNoRealCaptureIdEvenWithARecordingInProgress() = runTest {
        capture(CaptureStatus.ACTIVE)
        points(5)

        val everything = contents(exporter.export(ExportOptions()).file).values.joinToString("\n")

        assertFalse(everything.contains(latitude))
        assertFalse(everything.contains(longitude))
        assertFalse(everything.contains(captureId))
        assertTrue("the short label is there instead", everything.contains(DiagnosticFormulas.shortId(captureId)))
    }

    @Test
    fun withTheExplicitOptionTheLatestRecordingsRawTrackIsAttached() = runTest {
        capture(CaptureStatus.ACTIVE)
        points(5)

        val export = exporter.export(ExportOptions(includeRouteData = true))
        val files = contents(export.file)

        assertTrue(export.includesRouteData)
        val csv = files.entries.single { it.key.startsWith("route/raw-track-") }.value
        assertEquals("header plus five points", 6, csv.trim().lines().size)
        assertTrue(csv.contains(latitude))
    }

    @Test
    fun withNoRecordingActiveTheMostRecentOneThatEndedIsUsed() = runTest {
        capture(CaptureStatus.COMPLETED)
        points(3)

        val export = exporter.export(ExportOptions(includeRouteData = true))

        assertTrue(export.includesRouteData)
        assertEquals(4, contents(export.file).entries.single { it.key.startsWith("route/raw-track-") }.value.trim().lines().size)
    }

    @Test
    fun routeDataRequestedWithNothingRecordedAddsNoRouteFolder() = runTest {
        val export = exporter.export(ExportOptions(includeRouteData = true))

        assertFalse(export.includesRouteData)
        assertTrue(contents(export.file).keys.none { it.startsWith("route/") })
    }

    @Test
    fun onlyTheLastThreePackagesAreKeptSoTheCacheDoesNotFillUp() = runTest {
        repeat(6) { i ->
            clock.advanceMillis(2_000L) // a different name each time
            exporter.export(ExportOptions()).file.setLastModified(clock.wallClockMillis())
        }

        val kept = File(context.cacheDir, DiagnosticExporter.DIRECTORY).listFiles()!!.filter { it.name.endsWith(".zip") }

        assertEquals(DiagnosticExporter.KEEP_LAST, kept.size)
    }
}
