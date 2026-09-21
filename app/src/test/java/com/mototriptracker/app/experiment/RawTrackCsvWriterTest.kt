package com.mototriptracker.app.experiment

import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class RawTrackCsvWriterTest {

    private fun point(
        sequenceNumber: Long,
        provider: String? = "fused",
        requestProfileId: String = "S1-A"
    ) = RawTrackPointEntity(
        captureId = "capture-1",
        sequenceNumber = sequenceNumber,
        capturedAt = 1_000L + sequenceNumber,
        elapsedRealtimeNanos = 2_000L + sequenceNumber,
        receivedAtElapsedRealtimeNanos = null,
        latitude = 10.0,
        longitude = -20.0,
        horizontalAccuracyM = 5f,
        altitudeEllipsoidM = null,
        altitudeMslM = null,
        verticalAccuracyM = null,
        speedMps = null,
        speedAccuracyMps = null,
        bearingDeg = null,
        bearingAccuracyDeg = null,
        provider = provider,
        isMock = false,
        requestProfileId = requestProfileId,
        callbackBatchId = null,
        detectorStateSnapshot = "TRACKING"
    )

    @Test
    fun emptyListProducesJustTheHeader() {
        val csv = RawTrackCsvWriter.toCsv(emptyList())
        assertEquals(1, csv.lines().size)
        assertEquals(
            "sequenceNumber,capturedAt,elapsedRealtimeNanos,receivedAtElapsedRealtimeNanos,latitude,longitude,horizontalAccuracyM,altitudeEllipsoidM,altitudeMslM,verticalAccuracyM,speedMps,speedAccuracyMps,bearingDeg,bearingAccuracyDeg,provider,isMock,requestProfileId,callbackBatchId,detectorStateSnapshot",
            csv
        )
    }

    @Test
    fun oneRowPerPointWithNullsAsEmptyFields() {
        val csv = RawTrackCsvWriter.toCsv(listOf(point(sequenceNumber = 0)))
        val lines = csv.lines()
        assertEquals(2, lines.size)
        assertEquals("0,1000,2000,,10.0,-20.0,5.0,,,,,,,,fused,false,S1-A,,TRACKING", lines[1])
    }

    @Test
    fun multiplePointsPreserveOrderAndEachGetsItsOwnRow() {
        val csv = RawTrackCsvWriter.toCsv(listOf(point(0), point(1), point(2)))
        val dataLines = csv.lines().drop(1)
        assertEquals(3, dataLines.size)
        assertEquals(0L, dataLines[0].substringBefore(",").toLong())
        assertEquals(1L, dataLines[1].substringBefore(",").toLong())
        assertEquals(2L, dataLines[2].substringBefore(",").toLong())
    }

    @Test
    fun stringFieldsContainingCommasAreQuotedAndEscaped() {
        val csv = RawTrackCsvWriter.toCsv(listOf(point(sequenceNumber = 0, provider = "fused,gps")))
        val dataRow = csv.lines()[1]
        assertEquals(true, dataRow.contains("\"fused,gps\""))
    }
}
