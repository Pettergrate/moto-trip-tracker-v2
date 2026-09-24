package com.mototriptracker.app.worker

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.database.entity.TripPartEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.EditOperationType
import com.mototriptracker.app.core.model.LineageRole
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.testing.FakeProcessingScheduler
import com.mototriptracker.app.testing.TestDatabaseFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * EDT-001. Proves domain-data-model.md §8.3's merge rule for real: a new
 * Trip is created, the source Trips' TripParts are copied onto it in
 * chronological order (never by call-argument order), both sources become
 * SUPERSEDED without losing their own rows, and lineage is recorded.
 */
@RunWith(RobolectricTestRunner::class)
class TripMergerTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var merger: TripMerger

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        scheduler = FakeProcessingScheduler()
        merger = TripMerger(
            database = db,
            tripDao = db.tripDao(),
            tripPartDao = db.tripPartDao(),
            tripCaptureDao = db.tripCaptureDao(),
            tripEditOperationDao = db.tripEditOperationDao(),
            tripLineageLinkDao = db.tripLineageLinkDao(),
            processingScheduler = scheduler,
            clock = FakeClock(wallMillis = 9_000L),
            idGenerator = FakeIdGenerator("merge")
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun trip(id: String, name: String? = null, isFavorite: Boolean = false, createdAt: Long, status: TripStatus = TripStatus.COMPLETED) = TripEntity(
        id = id, status = status, name = name, isFavorite = isFavorite,
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
    fun mergingTwoTripsCreatesANewTripAndSupersedesTheSources() = runTest {
        db.tripDao().insert(trip("earlier", name = "Coastal loop", createdAt = 1_000L))
        db.tripDao().insert(trip("later", createdAt = 2_000L))
        db.tripCaptureDao().insert(capture("capture-earlier", startedAt = 100L))
        db.tripCaptureDao().insert(capture("capture-later", startedAt = 200L))
        db.tripPartDao().insert(part("part-earlier", "earlier", "capture-earlier"))
        db.tripPartDao().insert(part("part-later", "later", "capture-later"))

        // Called with the LATER trip first - ordering must come from
        // TripCapture.startedAt, never from argument order.
        val result = merger.merge("later", "earlier")

        val mergedId = (result as TripMerger.Result.Success).mergedTripId
        val merged = db.tripDao().findById(mergedId)!!
        assertEquals(TripStatus.COMPLETED, merged.status)
        assertEquals("earlier's custom name is inherited over later's null", "Coastal loop", merged.name)
        assertEquals("createdAt is the later source's own finish time", 2_000L, merged.createdAt)

        val mergedParts = db.tripPartDao().findAllByTrip(mergedId)
        assertEquals(2, mergedParts.size)
        assertEquals("earlier capture's part comes first regardless of call order", "capture-earlier", mergedParts[0].captureId)
        assertEquals(0, mergedParts[0].orderIndex)
        assertEquals("capture-later", mergedParts[1].captureId)
        assertEquals(1, mergedParts[1].orderIndex)

        assertEquals(TripStatus.SUPERSEDED, db.tripDao().findById("earlier")!!.status)
        assertEquals(TripStatus.SUPERSEDED, db.tripDao().findById("later")!!.status)
        assertNotNull("source's own TripPart survives - not deleted, just superseded via its owning Trip", db.tripPartDao().findByCaptureId("capture-earlier"))

        assertEquals(1, scheduler.enqueuedRequests.size)
        assertEquals(mergedId, scheduler.enqueuedRequests.single().tripId)
    }

    @Test
    fun mergingTwoHalvesOfOneSplitOrdersThemByPositionInTheSharedCaptureNotByCaptureStartTime() = runTest {
        // EDT-002: both halves reference the same capture, so their captures'
        // startedAt ties - the order must come from where in that capture
        // each half's first part begins.
        db.tripDao().insert(trip("half-a", createdAt = 1_000L))
        db.tripDao().insert(trip("half-b", createdAt = 2_000L))
        db.tripCaptureDao().insert(capture("shared", startedAt = 100L))
        db.tripPartDao().insert(part("part-a", "half-a", "shared").copy(endElapsedRealtimeNanos = 50_000_000_000L, endSequenceNumber = 4))
        db.tripPartDao().insert(
            part("part-b", "half-b", "shared").copy(startElapsedRealtimeNanos = 50_000_000_000L, startSequenceNumber = 5)
        )

        // Deliberately the later half first.
        val result = merger.merge("half-b", "half-a") as TripMerger.Result.Success

        val merged = db.tripPartDao().findAllByTrip(result.mergedTripId)
        assertEquals(listOf(4L), merged.take(1).map { it.endSequenceNumber })
        assertEquals(listOf(5L), merged.drop(1).map { it.startSequenceNumber })
        assertEquals(listOf(0, 1), merged.map { it.orderIndex })
    }

    @Test
    fun mergeRecordsAnEditOperationAndLineageForBothInputsAndTheOutput() = runTest {
        db.tripDao().insert(trip("a", createdAt = 1_000L))
        db.tripDao().insert(trip("b", createdAt = 2_000L))
        db.tripCaptureDao().insert(capture("cap-a", startedAt = 100L))
        db.tripCaptureDao().insert(capture("cap-b", startedAt = 200L))
        db.tripPartDao().insert(part("part-a", "a", "cap-a"))
        db.tripPartDao().insert(part("part-b", "b", "cap-b"))

        val result = merger.merge("a", "b") as TripMerger.Result.Success

        // Find the operation from the merged Trip's own OUTPUT link rather
        // than assuming a specific FakeIdGenerator sequence.
        val outputLink = db.tripLineageLinkDao().findByTrip(result.mergedTripId).single()
        assertEquals(LineageRole.OUTPUT, outputLink.role)
        val inputs = db.tripLineageLinkDao().findByOperationAndRole(outputLink.operationId, LineageRole.INPUT)
        assertEquals(setOf("a", "b"), inputs.map { it.tripId }.toSet())
    }

    @Test
    fun favoriteIsInheritedIfEitherSourceWasFavorited() = runTest {
        db.tripDao().insert(trip("a", isFavorite = false, createdAt = 1_000L))
        db.tripDao().insert(trip("b", isFavorite = true, createdAt = 2_000L))
        db.tripCaptureDao().insert(capture("cap-a", startedAt = 100L))
        db.tripCaptureDao().insert(capture("cap-b", startedAt = 200L))
        db.tripPartDao().insert(part("part-a", "a", "cap-a"))
        db.tripPartDao().insert(part("part-b", "b", "cap-b"))

        val result = merger.merge("a", "b") as TripMerger.Result.Success

        assertTrue(db.tripDao().findById(result.mergedTripId)!!.isFavorite)
    }

    @Test
    fun mergeFailsCleanlyWhenASourceIsNotCompleted() = runTest {
        db.tripDao().insert(trip("a", createdAt = 1_000L))
        db.tripDao().insert(trip("b", createdAt = 2_000L, status = TripStatus.TRASHED))
        db.tripCaptureDao().insert(capture("cap-a", startedAt = 100L))
        db.tripCaptureDao().insert(capture("cap-b", startedAt = 200L))
        db.tripPartDao().insert(part("part-a", "a", "cap-a"))
        db.tripPartDao().insert(part("part-b", "b", "cap-b"))

        val result = merger.merge("a", "b")

        assertEquals(TripMerger.Result.PreconditionFailed, result)
        assertEquals("no partial state - both sources untouched", TripStatus.COMPLETED, db.tripDao().findById("a")!!.status)
        assertEquals(TripStatus.TRASHED, db.tripDao().findById("b")!!.status)
        assertTrue("nothing enqueued on failure", scheduler.enqueuedRequests.isEmpty())
    }
}
