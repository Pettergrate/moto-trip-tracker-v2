package com.mototriptracker.app.experiment

import com.mototriptracker.app.core.database.entity.RawTrackPointEntity

/**
 * F0.6 §20's `raw-track.csv` - deferred by EXP-001 since TRK-002's raw
 * ingestion didn't exist yet, and left with nothing to correlate a harness
 * session to a real capture until EXP-002's same-day coupling fix. Always
 * writes the header, even for zero points (an honest "no raw data" file,
 * not a missing one - matches this session's own field-test day: a
 * too-quick stop can leave a session with no correlated capture yet).
 *
 * `captureId` is omitted from the rows on purpose - it's already constant
 * for a whole file (F0.6 §20's own one-file-per-session layout), and is
 * already in that session's `session.json`.
 */
object RawTrackCsvWriter {

    private val HEADER = listOf(
        "sequenceNumber", "capturedAt", "elapsedRealtimeNanos", "receivedAtElapsedRealtimeNanos",
        "latitude", "longitude", "horizontalAccuracyM", "altitudeEllipsoidM", "altitudeMslM",
        "verticalAccuracyM", "speedMps", "speedAccuracyMps", "bearingDeg", "bearingAccuracyDeg",
        "provider", "isMock", "requestProfileId", "callbackBatchId", "detectorStateSnapshot"
    ).joinToString(",")

    fun toCsv(points: List<RawTrackPointEntity>): String {
        val builder = StringBuilder(HEADER)
        points.forEach { point ->
            builder.append('\n')
            builder.append(
                listOf(
                    point.sequenceNumber,
                    point.capturedAt,
                    point.elapsedRealtimeNanos,
                    point.receivedAtElapsedRealtimeNanos ?: "",
                    point.latitude,
                    point.longitude,
                    point.horizontalAccuracyM,
                    point.altitudeEllipsoidM ?: "",
                    point.altitudeMslM ?: "",
                    point.verticalAccuracyM ?: "",
                    point.speedMps ?: "",
                    point.speedAccuracyMps ?: "",
                    point.bearingDeg ?: "",
                    point.bearingAccuracyDeg ?: "",
                    point.provider.orEmpty().csvEscaped(),
                    point.isMock ?: "",
                    point.requestProfileId.csvEscaped(),
                    point.callbackBatchId.orEmpty().csvEscaped(),
                    point.detectorStateSnapshot.csvEscaped()
                ).joinToString(",")
            )
        }
        return builder.toString()
    }

    private fun String.csvEscaped(): String =
        if (contains(',') || contains('"') || contains('\n')) {
            "\"${replace("\"", "\"\"")}\""
        } else {
            this
        }
}
