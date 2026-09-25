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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** EDT-003: a boundary correction yields ONE revised Trip over a narrower range of the same capture; the source is SUPERSEDED, raw untouched. */
@RunWith(RobolectricTestRunner::class)
class TripBoundaryEditorTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var editor: TripBoundaryEditor
    private val version = TripProcessingWorker.CURRENT_PROCESSING_VERSION

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        scheduler = FakeProcessingScheduler()
        editor = TripBoundaryEditor(
            database = db, tripDao = db.tripDao(), tripPartDao = db.tripPartDao(), rawTrackPointDao = db.rawTrackPointDao(),
            processedTrackPointDao = db.processedTrackPointDao(), tripEditOperationDao = db.tripEditOperationDao(),
            tripLineageLinkDao = db.tripLineageLinkDao(), processingScheduler = scheduler,
            clock = FakeClock(wallMillis = 9_000_000L), idGenerator = FakeIdGenerator("trim")
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedTrip(status: TripStatus = TripStatus.COMPLETED) {
        db.tripDao().insert(
            TripEntity(
                id = "source", status = status, name = "Coastal loop", isFavorite = true, motorcycleId = null, routeId = null,
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
        for (i in 0 until 10) {
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
            (0 until 10).map { i ->
                ProcessedTrackPointEntity(
                    tripId = "source", processingVersion = version, orderIndex = i,
                    latitude = 10.0 + i * 0.001, longitude = -20.0,
                    sourceCaptureId = "cap", sourceSequenceNumber = i.toLong(), pointRole = null
                )
            }
        )
    }

    private fun ref(seq: Long) = TripBoundaryEditor.PointRef("cap", seq)

    @Test
    fun trimmingCreatesOneRevisedTripOverANarrowerRangeAndSupersedesTheSource() = runTest {
        seedTrip()

        val result = editor.trim("source", ref(2), ref(7)) as TripBoundaryEditor.Result.Success

        val part = db.tripPartDao().findAllByTrip(result.newTripId).single()
        assertEquals("cap", part.captureId)
        assertEquals(2L, part.startSequenceNumber)
        assertEquals(7L, part.endSequenceNumber)
        assertEquals(20_000_000_000L, part.startElapsedRealtimeNanos)
        assertEquals(70_000_000_000L, part.endElapsedRealtimeNanos)
        assertEquals(TripStatus.SUPERSEDED, db.tripDao().findById("source")!!.status)
        assertEquals("raw evidence untouched - excluded points stay in the capture", 10, db.rawTrackPointDao().countByCapture("cap"))
        assertEquals(listOf(result.newTripId), scheduler.enqueuedRequests.map { it.tripId })
    }

    @Test
    fun theRevisedTripKeepsEverythingAboutTheRideExceptItsExtent() = runTest {
        seedTrip()

        val result = editor.trim("source", ref(2), ref(7)) as TripBoundaryEditor.Result.Success

        val revised = db.tripDao().findById(result.newTripId)!!
        assertEquals("Coastal loop", revised.name)
        assertEquals("windy", revised.notes)
        assertTrue(revised.isFavorite)
        assertEquals(TripStatus.COMPLETED, revised.status)
        assertEquals("end was trimmed, so the ride now ended at the new last point", 2_007_000L, revised.createdAt)
    }

    @Test
    fun trimmingOnlyTheStartKeepsTheSourcesFinishTime() = runTest {
        seedTrip()

        val result = editor.trim("source", ref(3), ref(9)) as TripBoundaryEditor.Result.Success

        assertEquals(5_000_000L, db.tripDao().findById(result.newTripId)!!.createdAt)
        val part = db.tripPartDao().findAllByTrip(result.newTripId).single()
        assertEquals("the untouched end keeps the part's own edge, not the last GPS point's time", 100_000_000_000L, part.endElapsedRealtimeNanos)
        assertEquals(null, part.endSequenceNumber)
    }

    @Test
    fun lineageRecordsOneInputAndOneOutput() = runTest {
        seedTrip()

        val result = editor.trim("source", ref(2), ref(7)) as TripBoundaryEditor.Result.Success

        val outputLink = db.tripLineageLinkDao().findByTrip(result.newTripId).single()
        assertEquals(LineageRole.OUTPUT, outputLink.role)
        val inputs = db.tripLineageLinkDao().findByOperationAndRole(outputLink.operationId, LineageRole.INPUT)
        assertEquals(listOf("source"), inputs.map { it.tripId })
    }

    @Test
    fun invalidOrPointlessEditsAreRefusedAndNothingIsWritten() = runTest {
        seedTrip()

        assertEquals("nothing removed", TripBoundaryEditor.Result.PreconditionFailed, editor.trim("source", ref(0), ref(9)))
        assertEquals("only one point kept", TripBoundaryEditor.Result.PreconditionFailed, editor.trim("source", ref(5), ref(5)))
        assertEquals("end before start", TripBoundaryEditor.Result.PreconditionFailed, editor.trim("source", ref(7), ref(2)))
        assertEquals("not a processed point", TripBoundaryEditor.Result.PreconditionFailed, editor.trim("source", ref(2), ref(99)))

        assertEquals(TripStatus.COMPLETED, db.tripDao().findById("source")!!.status)
        assertTrue(scheduler.enqueuedRequests.isEmpty())
    }

    @Test
    fun aTripThatIsNotCompletedCannotBeTrimmed() = runTest {
        seedTrip(status = TripStatus.TRASHED)

        assertEquals(TripBoundaryEditor.Result.PreconditionFailed, editor.trim("source", ref(2), ref(7)))
        assertEquals(TripBoundaryEditor.Result.PreconditionFailed, editor.trim("nope", ref(2), ref(7)))
    }
}
