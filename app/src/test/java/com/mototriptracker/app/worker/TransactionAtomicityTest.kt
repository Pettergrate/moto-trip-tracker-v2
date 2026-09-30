package com.mototriptracker.app.worker

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.ProcessedTrackPointEntity
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.database.entity.TripPartEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.testing.FakeProcessingScheduler
import com.mototriptracker.app.testing.FlakyTripLineageLinkDao
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * REL-001 (reliability fault-injection matrix): proves REL-INV-008 ("Merge, Split, boundary edit y cierre de captura
 * deben aplicar sus mutaciones estructurales dentro de transacciones de base de datos apropiadas... una
 * excepción/cancelación provoca rollback") for real, against a genuine mid-transaction fault - not just the
 * already-well-tested "precondition re-check fails, nothing was ever written" early-return path each of
 * `TripMergerTest`/`TripSplitterTest`/`TripBoundaryEditorTest` already covers.
 *
 * Each operation's transaction ends with `tripLineageLinkDao.insertAll(...)` - the last of several writes (the new
 * Trip row, its TripParts, the source(s) marked SUPERSEDED, the TripEditOperation row) inside the same
 * `database.withTransaction` block. [FlakyTripLineageLinkDao] fails exactly that last write, so if Room's real
 * transaction rollback did not genuinely undo everything before it, this test would find a half-written new Trip or
 * a source wrongly left SUPERSEDED. IDs are deterministic ([FakeIdGenerator]), so the *specific* row a naive
 * "insert directly, no transaction" implementation would have left behind can be checked for directly, not just
 * inferred from a row count.
 */
@RunWith(RobolectricTestRunner::class)
class TransactionAtomicityTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var flakyLineage: FlakyTripLineageLinkDao
    private val version = TripProcessingWorker.CURRENT_PROCESSING_VERSION

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        scheduler = FakeProcessingScheduler()
        flakyLineage = FlakyTripLineageLinkDao(db.tripLineageLinkDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun trip(id: String, createdAt: Long, status: TripStatus = TripStatus.COMPLETED) = TripEntity(
        id = id, status = status, name = null, isFavorite = false,
        motorcycleId = null, routeId = null, notes = null, createdAt = createdAt, updatedAt = createdAt, deletedAt = null
    )

    private fun capture(id: String, startedAt: Long) = TripCaptureEntity(
        id = id, status = CaptureStatus.COMPLETED, startedAt = startedAt, endedAt = startedAt + 1_000L,
        startElapsedRealtimeNanos = 0L, endElapsedRealtimeNanos = 1_000_000_000L, localTimeZoneId = "UTC",
        startSource = StartSource.MANUAL, endSource = null,
        detectorVersion = DetectorVersion(0), locationProfileVersion = LocationProfileVersion(0),
        createdAt = startedAt, updatedAt = startedAt
    )

    private fun part(id: String, tripId: String, captureId: String) = TripPartEntity(
        id = id, tripId = tripId, captureId = captureId, orderIndex = 0,
        startElapsedRealtimeNanos = 0L, endElapsedRealtimeNanos = 1_000_000_000L,
        startSequenceNumber = null, endSequenceNumber = null
    )

    @Test
    fun aMergeThatFailsOnItsLastWriteLeavesBothSourcesUntouchedAndCreatesNoNewTrip() = runTest {
        db.tripDao().insert(trip("earlier", createdAt = 1_000L))
        db.tripDao().insert(trip("later", createdAt = 2_000L))
        db.tripCaptureDao().insert(capture("capture-earlier", startedAt = 100L))
        db.tripCaptureDao().insert(capture("capture-later", startedAt = 200L))
        db.tripPartDao().insert(part("part-earlier", "earlier", "capture-earlier"))
        db.tripPartDao().insert(part("part-later", "later", "capture-later"))
        flakyLineage.failing = true
        val merger = TripMerger(
            database = db, tripDao = db.tripDao(), tripPartDao = db.tripPartDao(), tripCaptureDao = db.tripCaptureDao(),
            tripEditOperationDao = db.tripEditOperationDao(), tripLineageLinkDao = flakyLineage,
            processingScheduler = scheduler, clock = FakeClock(wallMillis = 9_000L), idGenerator = FakeIdGenerator("merge")
        )

        try {
            merger.merge("earlier", "later")
            fail("expected the injected lineage-write failure to propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("lineage write unavailable", expected.message)
        }

        assertNull("the new Trip (merge-1) was never left half-committed", db.tripDao().findById("merge-1"))
        assertEquals("earlier was not left SUPERSEDED by a rolled-back transaction", TripStatus.COMPLETED, db.tripDao().findById("earlier")!!.status)
        assertEquals("later was not left SUPERSEDED by a rolled-back transaction", TripStatus.COMPLETED, db.tripDao().findById("later")!!.status)
        assertTrue("nothing was enqueued for processing - the transaction never reached commit", scheduler.enqueuedRequests.isEmpty())
    }

    private suspend fun seedSplittableTrip(pointCount: Int = 10) {
        db.tripDao().insert(trip("source", createdAt = 5_000_000L))
        db.tripCaptureDao().insert(
            TripCaptureEntity(
                id = "cap", status = CaptureStatus.COMPLETED, startedAt = 1_000_000L, endedAt = 5_000_000L,
                startElapsedRealtimeNanos = 0L, endElapsedRealtimeNanos = 100_000_000_000L, localTimeZoneId = "UTC",
                startSource = StartSource.MANUAL, endSource = null,
                detectorVersion = DetectorVersion(0), locationProfileVersion = LocationProfileVersion(0),
                createdAt = 1_000_000L, updatedAt = 5_000_000L
            )
        )
        db.tripPartDao().insert(
            TripPartEntity(
                id = "source-part", tripId = "source", captureId = "cap", orderIndex = 0,
                startElapsedRealtimeNanos = 0L, endElapsedRealtimeNanos = 100_000_000_000L,
                startSequenceNumber = null, endSequenceNumber = null
            )
        )
        for (i in 0 until pointCount) {
            db.rawTrackPointDao().insert(
                RawTrackPointEntity(
                    captureId = "cap", sequenceNumber = i.toLong(), capturedAt = 2_000_000L + i * 1_000L,
                    elapsedRealtimeNanos = i * 10_000_000_000L, receivedAtElapsedRealtimeNanos = i * 10_000_000_000L,
                    latitude = 10.0, longitude = -20.0, horizontalAccuracyM = 5.0f,
                    altitudeEllipsoidM = null, altitudeMslM = null, verticalAccuracyM = null,
                    speedMps = null, speedAccuracyMps = null, bearingDeg = null, bearingAccuracyDeg = null,
                    provider = "fused", isMock = false, requestProfileId = "test-profile",
                    callbackBatchId = null, detectorStateSnapshot = "TRACKING"
                )
            )
        }
        db.processedTrackPointDao().insertAll(
            (0 until pointCount).map { i ->
                ProcessedTrackPointEntity(
                    tripId = "source", processingVersion = version, orderIndex = i,
                    latitude = 10.0 + i * 0.001, longitude = -20.0,
                    sourceCaptureId = "cap", sourceSequenceNumber = i.toLong(), pointRole = null
                )
            }
        )
    }

    @Test
    fun aSplitThatFailsOnItsLastWriteLeavesTheSourceUntouchedAndCreatesNoNewTrips() = runTest {
        seedSplittableTrip()
        flakyLineage.failing = true
        val splitter = TripSplitter(
            database = db, tripDao = db.tripDao(), tripPartDao = db.tripPartDao(), rawTrackPointDao = db.rawTrackPointDao(),
            processedTrackPointDao = db.processedTrackPointDao(), tripEditOperationDao = db.tripEditOperationDao(),
            tripLineageLinkDao = flakyLineage, processingScheduler = scheduler,
            clock = FakeClock(wallMillis = 9_000_000L), idGenerator = FakeIdGenerator("split")
        )

        try {
            splitter.split("source", "cap", 5)
            fail("expected the injected lineage-write failure to propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("lineage write unavailable", expected.message)
        }

        assertNull("the first half (split-1) was never left half-committed", db.tripDao().findById("split-1"))
        assertNull("the second half (split-2) was never left half-committed", db.tripDao().findById("split-2"))
        assertEquals("source was not left SUPERSEDED by a rolled-back transaction", TripStatus.COMPLETED, db.tripDao().findById("source")!!.status)
        assertEquals("no raw evidence was touched even on the failing attempt", 10, db.rawTrackPointDao().countByCapture("cap"))
        assertTrue("nothing was enqueued for processing - the transaction never reached commit", scheduler.enqueuedRequests.isEmpty())
    }

    @Test
    fun aBoundaryEditThatFailsOnItsLastWriteLeavesTheSourceUntouchedAndCreatesNoNewTrip() = runTest {
        seedSplittableTrip()
        flakyLineage.failing = true
        val editor = TripBoundaryEditor(
            database = db, tripDao = db.tripDao(), tripPartDao = db.tripPartDao(), rawTrackPointDao = db.rawTrackPointDao(),
            processedTrackPointDao = db.processedTrackPointDao(), tripEditOperationDao = db.tripEditOperationDao(),
            tripLineageLinkDao = flakyLineage, processingScheduler = scheduler,
            clock = FakeClock(wallMillis = 9_000_000L), idGenerator = FakeIdGenerator("trim")
        )

        try {
            editor.trim("source", TripBoundaryEditor.PointRef("cap", 2), TripBoundaryEditor.PointRef("cap", 7))
            fail("expected the injected lineage-write failure to propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("lineage write unavailable", expected.message)
        }

        assertNull("the revised Trip (trim-1) was never left half-committed", db.tripDao().findById("trim-1"))
        assertEquals("source was not left SUPERSEDED by a rolled-back transaction", TripStatus.COMPLETED, db.tripDao().findById("source")!!.status)
        assertEquals("no raw evidence was touched even on the failing attempt", 10, db.rawTrackPointDao().countByCapture("cap"))
        assertTrue("nothing was enqueued for processing - the transaction never reached commit", scheduler.enqueuedRequests.isEmpty())
    }
}
