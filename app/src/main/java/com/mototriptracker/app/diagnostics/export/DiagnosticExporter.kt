package com.mototriptracker.app.diagnostics.export

import android.content.Context
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.dao.RawTrackPointDao
import com.mototriptracker.app.core.database.dao.TripCaptureDao
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.diagnostics.DiagnosticFormulas
import com.mototriptracker.app.diagnostics.DiagnosticSnapshotProvider
import com.mototriptracker.app.experiment.RawTrackCsvWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** DIA-003: a diagnostic package on disk, ready to be handed to the system share sheet. */
data class DiagnosticExport(val file: File, val entries: List<String>, val includesRouteData: Boolean)

/**
 * DIA-003 / F0.13 §11: assembles the package from the same snapshot the debug screen shows plus the stored events,
 * and writes it as `diagnostic-YYYYMMDD-HHMMSS.zip` into the app's *cache* directory (a `FileProvider` path), never
 * to shared storage: the person decides where it goes next through the system share sheet (§11.4 "sin upload
 * automático"). The app itself makes no network call and asks for no storage permission (`privacy-permissions.md` §10.1).
 *
 * Old packages are pruned so the cache holds only the last few - a diagnostic file is a copy of evidence, not an archive.
 */
class DiagnosticExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val snapshotProvider: DiagnosticSnapshotProvider,
    private val diagnosticEventDao: DiagnosticEventDao,
    private val tripCaptureDao: TripCaptureDao,
    private val rawTrackPointDao: RawTrackPointDao,
    private val clock: Clock
) {
    suspend fun export(options: ExportOptions): DiagnosticExport = withContext(Dispatchers.IO) {
        val now = clock.wallClockMillis()
        val snapshot = snapshotProvider.snapshot()
        val events = diagnosticEventDao.findAll()
        val route = if (options.includeRouteData) latestRoute() else null

        val entries = DiagnosticBundleBuilder.build(now, snapshot, events, route, options)

        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        prune(directory)
        val file = File(directory, "diagnostic-${timestamp(now)}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
            entries.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.path))
                zip.write(entry.content)
                zip.closeEntry()
            }
        }
        DiagnosticExport(file, entries.map { it.path }, includesRouteData = entries.any { it.path.startsWith("route/") })
    }

    /** The recording being made, or else the last one that ended: the one a GPS problem is most likely about. */
    private suspend fun latestRoute(): RouteData? {
        val capture = tripCaptureDao.findByStatus(CaptureStatus.ACTIVE) ?: tripCaptureDao.findMostRecentlyEnded() ?: return null
        val points = rawTrackPointDao.findAllByCapture(capture.id)
        if (points.isEmpty()) return null
        return RouteData(DiagnosticFormulas.shortId(capture.id), points.size, RawTrackCsvWriter.toCsv(points))
    }

    private fun prune(directory: File) {
        directory.listFiles { f -> f.isFile && f.name.startsWith("diagnostic-") && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_LAST - 1)
            ?.forEach { it.delete() }
    }

    private fun timestamp(millis: Long): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(millis))

    companion object {
        /** Under the cache dir; `res/xml/diagnostic_file_paths.xml` exposes exactly this directory to `FileProvider`. */
        const val DIRECTORY = "diagnostics"
        const val KEEP_LAST = 3
    }
}
