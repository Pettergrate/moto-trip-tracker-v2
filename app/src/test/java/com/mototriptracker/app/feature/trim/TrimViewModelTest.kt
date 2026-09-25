package com.mototriptracker.app.feature.trim

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
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import com.mototriptracker.app.worker.TripBoundaryEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** EDT-003: the Trim screen's preview of the trimmed Trip before anything is committed. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TrimViewModelTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var viewModel: TrimViewModel
    private val version = TripProcessingWorker.CURRENT_PROCESSING_VERSION
    private val metersPerStep = 111.19 // 0.001 degrees of latitude

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = TestDatabaseFactory.createInMemory()
        val editor = TripBoundaryEditor(
            database = db, tripDao = db.tripDao(), tripPartDao = db.tripPartDao(), rawTrackPointDao = db.rawTrackPointDao(),
            processedTrackPointDao = db.processedTrackPointDao(), tripEditOperationDao = db.tripEditOperationDao(),
            tripLineageLinkDao = db.tripLineageLinkDao(), processingScheduler = FakeProcessingScheduler(),
            clock = FakeClock(wallMillis = 9_000_000L), idGenerator = FakeIdGenerator("trim")
        )
        viewModel = TrimViewModel(db.tripDao(), db.tripPartDao(), db.rawTrackPointDao(), db.processedTrackPointDao(), editor)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun seedTrip(pointCount: Int, partEndNanos: Long? = 100_000_000_000L) {
        db.tripDao().insert(
            TripEntity(
                id = "trip", status = TripStatus.COMPLETED, name = null, isFavorite = false, motorcycleId = null,
                routeId = null, notes = null, createdAt = 5_000_000L, updatedAt = 5_000_000L, deletedAt = null
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
                id = "part", tripId = "trip", captureId = "cap", orderIndex = 0,
                startElapsedRealtimeNanos = 0L, endElapsedRealtimeNanos = partEndNanos,
                startSequenceNumber = null, endSequenceNumber = null
            )
        )
        for (i in 0 until pointCount) {
            db.rawTrackPointDao().insert(
                RawTrackPointEntity(
                    captureId = "cap", sequenceNumber = i.toLong(), capturedAt = 2_000_000L + i * 1_000L,
                    elapsedRealtimeNanos = i * 10_000_000_000L, receivedAtElapsedRealtimeNanos = i * 10_000_000_000L,
                    latitude = 10.0 + i * 0.001, longitude = -20.0, horizontalAccuracyM = 5.0f,
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
                    tripId = "trip", processingVersion = version, orderIndex = i,
                    latitude = 10.0 + i * 0.001, longitude = -20.0,
                    sourceCaptureId = "cap", sourceSequenceNumber = i.toLong(), pointRole = null
                )
            }
        )
    }

    private suspend fun loadReady(): TrimUiState.Ready {
        viewModel.load("trip")
        return withTimeout(5_000) { viewModel.uiState.first { it !is TrimUiState.Loading } } as TrimUiState.Ready
    }

    @Test
    fun startsCoveringTheWholeTripWithNothingToSave() = runBlocking {
        seedTrip(10)

        val ready = loadReady()

        assertEquals(0, ready.startIndex)
        assertEquals(9, ready.endIndex)
        assertFalse(ready.hasChanges)
        assertEquals(9 * metersPerStep, ready.kept.distanceMeters, 1.0)
        assertEquals(100_000L, ready.kept.durationMs)
        assertEquals(0L, ready.kept.removedDurationMs)
    }

    @Test
    fun movingTheHandlesPreviewsTheTrimmedTripAndWhatIsRemoved() = runBlocking {
        seedTrip(10)
        loadReady()

        viewModel.onRangeChanged(2, 7)

        val ready = viewModel.uiState.value as TrimUiState.Ready
        assertTrue(ready.hasChanges)
        assertEquals(5 * metersPerStep, ready.kept.distanceMeters, 1.0)
        assertEquals(50_000L, ready.kept.durationMs)
        assertEquals(50_000L, ready.kept.removedDurationMs)
    }

    @Test
    fun theHandlesCanNeverLeaveFewerThanTwoPoints() = runBlocking {
        seedTrip(10)
        loadReady()

        viewModel.onRangeChanged(8, 3)

        val ready = viewModel.uiState.value as TrimUiState.Ready
        assertTrue("end is pushed at least one point past start", ready.endIndex >= ready.startIndex + 1)
    }

    @Test
    fun reopeningTheScreenStartsFromTheFullRangeAgainNotFromTheCancelledSelection() = runBlocking {
        seedTrip(10)
        loadReady()
        viewModel.onRangeChanged(2, 7)

        val reopened = loadReady()

        assertEquals(0, reopened.startIndex)
        assertEquals(9, reopened.endIndex)
        assertFalse(reopened.hasChanges)
    }

    @Test
    fun aPartWithAnUnknownEndIsNotAvailableRatherThanShowingAZeroDuration() = runBlocking {
        seedTrip(10, partEndNanos = null)

        viewModel.load("trip")

        assertEquals(TrimUiState.NotAvailable, withTimeout(5_000) { viewModel.uiState.first { it !is TrimUiState.Loading } })
    }

    @Test
    fun aTripTooShortToTrimIsNotAvailable() = runBlocking {
        seedTrip(2)

        viewModel.load("trip")

        assertEquals(TrimUiState.NotAvailable, withTimeout(5_000) { viewModel.uiState.first { it !is TrimUiState.Loading } })
    }

    @Test
    fun savingWithoutChangesIsANoOpAndSavingWithChangesSupersedesTheSource() = runBlocking {
        seedTrip(10)
        loadReady()
        assertFalse("nothing to save yet", viewModel.save())
        assertEquals(TripStatus.COMPLETED, db.tripDao().findById("trip")?.status)

        viewModel.onRangeChanged(1, 8)

        assertTrue(viewModel.save())
        assertEquals(TripStatus.SUPERSEDED, db.tripDao().findById("trip")?.status)
    }
}
