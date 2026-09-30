package com.mototriptracker.app.perf

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.persistence.RawPointWriter
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PERF-001 / `NFR-PERF-002` ("Long-distance Trips with large numbers of TrackPoints MUST remain reviewable"). A
 * regression guard, not a device benchmark: this machine's JVM speed says nothing about a phone's, so the bound below
 * is deliberately generous (this run typically finishes in well under a second) - its job is to catch an ingestion
 * path that quietly became quadratic (e.g. a per-insert full-table scan), not to certify a real device's throughput,
 * which only `RC-001`/a real long ride can do.
 *
 * The count is a real, honest scenario: `ExperimentLocationProfiles.DEFAULT`'s 2s interval, 12 hours - a long single
 * riding day, not an arbitrary round number - is 21,600 points, ingested through the real [RawPointWriter] (the same
 * bounded buffer, retry and diagnostic-event machinery a real recording uses) into a real in-memory Room database.
 */
@RunWith(RobolectricTestRunner::class)
class LongTripIngestionBenchmarkTest {

    private lateinit var db: MotoTripDatabase
    private val captureId = "bench-capture"

    // A real 12-hour ride at the app's own default sampling interval (F0.5 §8.3's midpoint, ExperimentLocationProfiles.DEFAULT).
    private val pointCount = 12 * 60 * 60 / 2

    @Before
    fun setUp() = runTest {
        db = TestDatabaseFactory.createInMemory()
        db.tripCaptureDao().startCaptureIfNoneActive(
            TripCaptureEntity(
                id = captureId, status = CaptureStatus.ACTIVE, startedAt = 1_000L, endedAt = null,
                startElapsedRealtimeNanos = 10_000_000_000L, endElapsedRealtimeNanos = null, localTimeZoneId = "UTC",
                startSource = StartSource.MANUAL, endSource = null, detectorVersion = DetectorVersion(0),
                locationProfileVersion = LocationProfileVersion(0), createdAt = 1_000L, updatedAt = 1_000L
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun point(seq: Long) = RawTrackPointEntity(
        captureId = captureId, sequenceNumber = seq, capturedAt = 1_000L + seq * 2_000L,
        elapsedRealtimeNanos = 10_000_000_000L + seq * 2_000_000_000L, receivedAtElapsedRealtimeNanos = null,
        latitude = 10.0 + seq * 1e-5, longitude = -20.0 - seq * 1e-5, horizontalAccuracyM = 5f, altitudeEllipsoidM = null,
        altitudeMslM = null, verticalAccuracyM = null, speedMps = 12f, speedAccuracyMps = null, bearingDeg = null,
        bearingAccuracyDeg = null, provider = "fused", isMock = false, requestProfileId = "bench", callbackBatchId = null,
        detectorStateSnapshot = "TRACKING", isApproximateLocation = false
    )

    @Test
    fun a12HourRideIngestsThroughTheRealWriterWithoutQuadraticSlowdown() = runTest {
        val writer = RawPointWriter(
            rawTrackPointDao = db.rawTrackPointDao(), diagnosticEventDao = db.diagnosticEventDao(),
            clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 10_000_000_000L), idGenerator = FakeIdGenerator(prefix = "diag"),
            captureId = captureId
        )

        val elapsedMs = measureMs { for (seq in 0 until pointCount) writer.write(point(seq.toLong())) }
        writer.finish()

        assertEquals("no point is dropped along the way", pointCount, db.rawTrackPointDao().countByCapture(captureId))
        // Generous on purpose (see the class KDoc) - this is a quadratic-regression tripwire, not a device SLA.
        assertTrue("ingesting $pointCount points took ${elapsedMs}ms - investigate for an accidentally quadratic path", elapsedMs < 20_000L)
    }

    /** Half the points, in half the time or less (within noise) - the tripwire a real O(n^2) regression would trip. */
    @Test
    fun ingestionTimeScalesRoughlyLinearlyNotQuadratically() = runTest {
        val half = pointCount / 2
        val writer = RawPointWriter(
            rawTrackPointDao = db.rawTrackPointDao(), diagnosticEventDao = db.diagnosticEventDao(),
            clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 10_000_000_000L), idGenerator = FakeIdGenerator(prefix = "diag"),
            captureId = captureId
        )

        val firstHalfMs = measureMs { for (seq in 0 until half) writer.write(point(seq.toLong())) }
        val secondHalfMs = measureMs { for (seq in half until pointCount) writer.write(point(seq.toLong())) }
        writer.finish()

        // A generous multiple, not "less than or equal": JVM warm-up/GC noise on a shared machine is real, and this is
        // a tripwire for a gross regression (e.g. an O(n) scan re-run on every insert), not a precise ratio.
        assertTrue(
            "the second half of an identical-size batch took ${secondHalfMs}ms against ${firstHalfMs}ms for the first - " +
                "that shape is what an accidentally quadratic insert path looks like",
            secondHalfMs < maxOf(firstHalfMs * 4, 2_000L)
        )
    }

    private inline fun measureMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }
}
