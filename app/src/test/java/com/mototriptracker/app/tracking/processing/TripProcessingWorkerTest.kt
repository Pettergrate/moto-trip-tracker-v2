package com.mototriptracker.app.tracking.processing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.common.UuidIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.database.entity.TripPartEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.domain.processing.ProcessingEngine
import com.mototriptracker.app.testing.TestDatabaseFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves the worker's own coordination (read Trip/parts/raw points, run
 * [ProcessingEngine], publish the results) against a real Room database —
 * [com.mototriptracker.app.domain.processing.ProcessingEngineTest] already
 * covers the algorithm itself in isolation.
 */
@RunWith(RobolectricTestRunner::class)
class TripProcessingWorkerTest {

    private lateinit var db: MotoTripDatabase
    private val tripId = "trip-1"
    private val captureId = "capture-1"

    @Before
    fun setUp() = runTest {
        db = TestDatabaseFactory.createInMemory()
        db.tripCaptureDao().insert(
            TripCaptureEntity(
                id = captureId, status = CaptureStatus.COMPLETED, startedAt = 0L, endedAt = 0L,
                startElapsedRealtimeNanos = 0L, endElapsedRealtimeNanos = 0L, localTimeZoneId = "UTC",
                startSource = StartSource.MANUAL, endSource = null,
                detectorVersion = DetectorVersion(0), locationProfileVersion = LocationProfileVersion(0),
                createdAt = 0L, updatedAt = 0L
            )
        )
        db.tripDao().insert(
            TripEntity(
                id = tripId, status = TripStatus.COMPLETED, name = null, isFavorite = false,
                motorcycleId = null, routeId = null, notes = null, createdAt = 0L, updatedAt = 0L, deletedAt = null
            )
        )
        db.tripPartDao().insert(
            TripPartEntity(
                id = "part-1", tripId = tripId, captureId = captureId, orderIndex = 0,
                startElapsedRealtimeNanos = 0L, endElapsedRealtimeNanos = null,
                startSequenceNumber = null, endSequenceNumber = null
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun rawPoint(sequenceNumber: Long, elapsedNanos: Long) = RawTrackPointEntity(
        captureId = captureId, sequenceNumber = sequenceNumber, capturedAt = elapsedNanos / 1_000_000,
        elapsedRealtimeNanos = elapsedNanos, receivedAtElapsedRealtimeNanos = elapsedNanos,
        latitude = 10.0, longitude = -20.0, horizontalAccuracyM = 5.0f,
        altitudeEllipsoidM = null, altitudeMslM = null, verticalAccuracyM = null,
        speedMps = null, speedAccuracyMps = null, bearingDeg = null, bearingAccuracyDeg = null,
        provider = "fused", isMock = false, requestProfileId = "test-profile",
        callbackBatchId = null, detectorStateSnapshot = "TRACKING"
    )

    private fun buildWorker(): TripProcessingWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ) = TripProcessingWorker(
                context = appContext,
                params = workerParameters,
                database = db,
                tripPartDao = db.tripPartDao(),
                rawTrackPointDao = db.rawTrackPointDao(),
                pointAssessmentDao = db.pointAssessmentDao(),
                processedTrackPointDao = db.processedTrackPointDao(),
                locationGapDao = db.locationGapDao(),
                diagnosticEventDao = db.diagnosticEventDao(),
                processingEngine = ProcessingEngine(FakeIdGenerator(prefix = "gap")),
                clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L),
                idGenerator = UuidIdGenerator()
            )
        }
        return TestListenableWorkerBuilder<TripProcessingWorker>(ApplicationProvider.getApplicationContext())
            .setInputData(workDataOf(TripProcessingWorker.KEY_TRIP_ID to tripId, TripProcessingWorker.KEY_CAPTURE_ID to captureId))
            .setWorkerFactory(factory)
            .build()
    }

    @Test
    fun processesRawPointsIntoAssessmentsAndProcessedTrackPoints() = runTest {
        db.rawTrackPointDao().insert(rawPoint(0, 0L))
        db.rawTrackPointDao().insert(rawPoint(1, 2_000_000_000L))

        val result = buildWorker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val version = TripProcessingWorker.CURRENT_PROCESSING_VERSION
        assertEquals(2, db.pointAssessmentDao().findAllByCaptureAndVersion(captureId, version).size)
        assertEquals(2, db.processedTrackPointDao().findAllByTripAndVersion(tripId, version).size)
    }

    @Test
    fun rerunningTheWorkerReplacesRatherThanDuplicatesThePublishedRows() = runTest {
        db.rawTrackPointDao().insert(rawPoint(0, 0L))
        db.rawTrackPointDao().insert(rawPoint(1, 2_000_000_000L))

        buildWorker().doWork()
        buildWorker().doWork()

        val version = TripProcessingWorker.CURRENT_PROCESSING_VERSION
        assertEquals(2, db.pointAssessmentDao().findAllByCaptureAndVersion(captureId, version).size)
        assertEquals(2, db.processedTrackPointDao().findAllByTripAndVersion(tripId, version).size)
    }

    @Test
    fun failsCleanlyWhenTheTripHasNoParts() = runTest {
        val orphanFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ) = TripProcessingWorker(
                context = appContext,
                params = workerParameters,
                database = db,
                tripPartDao = db.tripPartDao(),
                rawTrackPointDao = db.rawTrackPointDao(),
                pointAssessmentDao = db.pointAssessmentDao(),
                processedTrackPointDao = db.processedTrackPointDao(),
                locationGapDao = db.locationGapDao(),
                diagnosticEventDao = db.diagnosticEventDao(),
                processingEngine = ProcessingEngine(FakeIdGenerator(prefix = "gap")),
                clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L),
                idGenerator = UuidIdGenerator()
            )
        }
        val worker = TestListenableWorkerBuilder<TripProcessingWorker>(ApplicationProvider.getApplicationContext())
            .setInputData(workDataOf(TripProcessingWorker.KEY_TRIP_ID to "no-such-trip", TripProcessingWorker.KEY_CAPTURE_ID to captureId))
            .setWorkerFactory(orphanFactory)
            .build()

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
    }
}
