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
import com.mototriptracker.app.core.model.LineageRole
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.testing.FakeProcessingScheduler
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * EDT-002. Proves domain-data-model.md §8.4's split rule for real: two new
 * Trips over the *same* capture (no raw point touched or copied), disjoint
 * sequence ranges, the source SUPERSEDED, lineage recorded - and that a
 * cut which would leave a half without a drawable route is refused with
 * nothing written.
 */
@RunWith(RobolectricTestRunner::class)
class TripSplitterTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var splitter: TripSplitter
    private val version = TripProcessingWorker.CURRENT_PROCESSING_VERSION

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        scheduler = FakeProcessingScheduler()
        splitter = TripSplitter(
            database = db,
            tripDao = db.tripDao(),
            tripPartDao = db.tripPartDao(),
            rawTrackPointDao = db.rawTrackPointDao(),
            processedTrackPointDao = db.processedTrackPointDao(),
            tripEditOperationDao = db.tripEditOperationDao(),
            tripLineageLinkDao = db.tripLineageLinkDao(),
            processingScheduler = scheduler,
            clock = FakeClock(wallMillis = 9_000_000L),
            idGenerator = FakeIdGenerator("split")
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A COMPLETED Trip "source" over capture "cap": raw points 0..[pointCount]-1, one second apart, all accepted. */
    private suspend fun seedTrip(pointCount: Int = 10, status: TripStatus = TripStatus.COMPLETED, name: String? = "Coastal loop") {
        db.tripDao().insert(
            TripEntity(
                id = "source", status = status, name = name, isFavorite = true, motorcycleId = null, routeId = null,
                notes = "windy", createdAt = 5_000_000L, updatedAt = 5_000_000L, deletedAt = null
            )
        )
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
            db.rawTrackPointDao().insert(rawPoint(i.toLong()))
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

    private fun rawPoint(sequenceNumber: Long) = RawTrackPointEntity(
        captureId = "cap", sequenceNumber = sequenceNumber, capturedAt = 2_000_000L + sequenceNumber * 1_000L,
        elapsedRealtimeNanos = sequenceNumber * 10_000_000_000L, receivedAtElapsedRealtimeNanos = sequenceNumber * 10_000_000_000L,
        latitude = 10.0, longitude = -20.0, horizontalAccuracyM = 5.0f,
        altitudeEllipsoidM = null, altitudeMslM = null, verticalAccuracyM = null,
        speedMps = null, speedAccuracyMps = null, bearingDeg = null, bearingAccuracyDeg = null,
        provider = "fused", isMock = false, requestProfileId = "test-profile",
        callbackBatchId = null, detectorStateSnapshot = "TRACKING"
    )

    @Test
    fun splittingCreatesTwoTripsOverTheSameCaptureWithDisjointRangesAndSupersedesTheSource() = runTest {
        seedTrip()

        val result = splitter.split("source", "cap", 5) as TripSplitter.Result.Success

        val firstParts = db.tripPartDao().findAllByTrip(result.firstTripId)
        val secondParts = db.tripPartDao().findAllByTrip(result.secondTripId)
        assertEquals("cap", firstParts.single().captureId)
        assertEquals(null, firstParts.single().startSequenceNumber)
        assertEquals("first half stops one point before the cut", 4L, firstParts.single().endSequenceNumber)
        assertEquals("second half starts at the cut", 5L, secondParts.single().startSequenceNumber)
        assertEquals(null, secondParts.single().endSequenceNumber)
        assertEquals("both halves share the cut's time, so their durations add up", 50_000_000_000L, firstParts.single().endElapsedRealtimeNanos)
        assertEquals(50_000_000_000L, secondParts.single().startElapsedRealtimeNanos)

        assertEquals(TripStatus.SUPERSEDED, db.tripDao().findById("source")!!.status)
        assertEquals("no raw evidence touched or duplicated (ADR-005/006)", 10, db.rawTrackPointDao().countByCapture("cap"))
        assertEquals(2, scheduler.enqueuedRequests.size)
        assertEquals(setOf(result.firstTripId, result.secondTripId), scheduler.enqueuedRequests.map { it.tripId }.toSet())
    }

    @Test
    fun namesNotesAndCreatedAtFollowTheDocumentedInheritanceRules() = runTest {
        seedTrip()

        val result = splitter.split("source", "cap", 5) as TripSplitter.Result.Success

        val first = db.tripDao().findById(result.firstTripId)!!
        val second = db.tripDao().findById(result.secondTripId)!!
        assertEquals("Coastal loop", first.name)
        assertEquals("windy", first.notes)
        assertNull("free text can't be divided - it stays with the first half only", second.name)
        assertNull(second.notes)
        assertTrue("favorite is a fact about the whole ride - kept on both", first.isFavorite && second.isFavorite)
        assertEquals("first half 'ended' at the cut point's own wall-clock time", 2_005_000L, first.createdAt)
        assertEquals("second half ended when the source did", 5_000_000L, second.createdAt)
        assertEquals(TripStatus.COMPLETED, first.status)
        assertEquals(TripStatus.COMPLETED, second.status)
    }

    @Test
    fun splitRecordsOneInputAndTwoOutputLineageLinks() = runTest {
        seedTrip()

        val result = splitter.split("source", "cap", 5) as TripSplitter.Result.Success

        val outputLink = db.tripLineageLinkDao().findByTrip(result.firstTripId).single()
        val inputs = db.tripLineageLinkDao().findByOperationAndRole(outputLink.operationId, LineageRole.INPUT)
        val outputs = db.tripLineageLinkDao().findByOperationAndRole(outputLink.operationId, LineageRole.OUTPUT)
        assertEquals(listOf("source"), inputs.map { it.tripId })
        assertEquals(setOf(result.firstTripId, result.secondTripId), outputs.map { it.tripId }.toSet())
    }

    @Test
    fun aCutTooCloseToAnEndIsRefusedAndNothingIsWritten() = runTest {
        seedTrip()

        // Cutting at index 1 would leave the first half a single point.
        assertEquals(TripSplitter.Result.PreconditionFailed, splitter.split("source", "cap", 1))
        // Cutting at the last-but-one leaves the second half two points (allowed); the very last leaves one.
        assertEquals(TripSplitter.Result.PreconditionFailed, splitter.split("source", "cap", 9))

        assertEquals(TripStatus.COMPLETED, db.tripDao().findById("source")!!.status)
        assertTrue(scheduler.enqueuedRequests.isEmpty())
    }

    @Test
    fun theSmallestAllowedCutsWork() = runTest {
        seedTrip()

        assertTrue(splitter.split("source", "cap", 2) is TripSplitter.Result.Success)
    }

    @Test
    fun aCutThatIsNotAProcessedPointOfTheTripIsRefused() = runTest {
        seedTrip()

        assertEquals(TripSplitter.Result.PreconditionFailed, splitter.split("source", "cap", 42))
        assertEquals(TripSplitter.Result.PreconditionFailed, splitter.split("source", "someone-elses-capture", 5))
    }

    @Test
    fun aTripThatIsNotCompletedCannotBeSplit() = runTest {
        seedTrip(status = TripStatus.TRASHED)

        assertEquals(TripSplitter.Result.PreconditionFailed, splitter.split("source", "cap", 5))
        assertEquals(TripSplitter.Result.PreconditionFailed, splitter.split("does-not-exist", "cap", 5))
        assertTrue(scheduler.enqueuedRequests.isEmpty())
    }

    @Test
    fun aTripWithNoProcessedPointsYetCannotBeSplit() = runTest {
        seedTrip(pointCount = 0)

        assertEquals(TripSplitter.Result.PreconditionFailed, splitter.split("source", "cap", 5))
    }
}
