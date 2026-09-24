package com.mototriptracker.app.feature.split

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
import com.mototriptracker.app.worker.TripSplitter
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

/** EDT-002/UX-10: the Split screen's live preview of both halves before anything is committed. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SplitViewModelTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var viewModel: SplitViewModel
    private val version = TripProcessingWorker.CURRENT_PROCESSING_VERSION

    // Latitude steps of 0.001 degrees are ~111.19 m apart along a meridian.
    private val metersPerStep = 111.19

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = TestDatabaseFactory.createInMemory()
        val splitter = TripSplitter(
            database = db, tripDao = db.tripDao(), tripPartDao = db.tripPartDao(), rawTrackPointDao = db.rawTrackPointDao(),
            processedTrackPointDao = db.processedTrackPointDao(), tripEditOperationDao = db.tripEditOperationDao(),
            tripLineageLinkDao = db.tripLineageLinkDao(), processingScheduler = FakeProcessingScheduler(),
            clock = FakeClock(wallMillis = 9_000_000L), idGenerator = FakeIdGenerator("split")
        )
        viewModel = SplitViewModel(db.tripDao(), db.tripPartDao(), db.rawTrackPointDao(), db.processedTrackPointDao(), splitter)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun seedTrip(pointCount: Int) {
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
                startElapsedRealtimeNanos = 0L, endElapsedRealtimeNanos = 100_000_000_000L,
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

    private suspend fun loadReady(): SplitUiState.Ready {
        viewModel.load("trip")
        return withTimeout(5_000) { viewModel.uiState.first { it !is SplitUiState.Loading } } as SplitUiState.Ready
    }

    @Test
    fun startsWithTheCutInTheMiddleAndBothHalvesPreviewedBeforeAnythingIsCommitted() = runBlocking {
        seedTrip(pointCount = 10)

        val ready = loadReady()

        assertEquals(5, ready.cutIndex)
        assertEquals(2, ready.minCutIndex)
        assertEquals(8, ready.maxCutIndex)
        // First half = points 0..4 (4 edges); second = points 5..9 (4 edges); the edge crossing the cut is in neither.
        assertEquals(4 * metersPerStep, ready.first.distanceMeters, 1.0)
        assertEquals(4 * metersPerStep, ready.second.distanceMeters, 1.0)
        assertEquals(50_000L, ready.first.durationMs)
        assertEquals(50_000L, ready.second.durationMs)
    }

    @Test
    fun movingTheCutUpdatesBothPreviewsAndTheirDurationsStillAddUp() = runBlocking {
        seedTrip(pointCount = 10)
        loadReady()

        viewModel.onCutIndexChanged(2)

        val ready = viewModel.uiState.value as SplitUiState.Ready
        assertEquals(2, ready.cutIndex)
        assertEquals(1 * metersPerStep, ready.first.distanceMeters, 1.0)
        assertEquals(7 * metersPerStep, ready.second.distanceMeters, 1.0)
        assertEquals(100_000L, ready.first.durationMs + ready.second.durationMs)
    }

    @Test
    fun theCutIsClampedSoEachHalfKeepsADrawableRoute() = runBlocking {
        seedTrip(pointCount = 10)
        loadReady()

        viewModel.onCutIndexChanged(0)
        assertEquals(2, (viewModel.uiState.value as SplitUiState.Ready).cutIndex)

        viewModel.onCutIndexChanged(99)
        assertEquals(8, (viewModel.uiState.value as SplitUiState.Ready).cutIndex)
    }

    @Test
    fun aTripTooShortForTwoDrawableHalvesIsNotAvailableToSplit() = runBlocking {
        seedTrip(pointCount = 3)

        viewModel.load("trip")

        val state = withTimeout(5_000) { viewModel.uiState.first { it !is SplitUiState.Loading } }
        assertEquals(SplitUiState.NotAvailable, state)
    }

    @Test
    fun anUnknownTripIsNotAvailableToSplit() = runBlocking {
        viewModel.load("does-not-exist")

        val state = withTimeout(5_000) { viewModel.uiState.first { it !is SplitUiState.Loading } }
        assertEquals(SplitUiState.NotAvailable, state)
    }

    @Test
    fun splitCommitsAtTheCurrentCutAndSupersedesTheSource() = runBlocking {
        seedTrip(pointCount = 10)
        loadReady()
        viewModel.onCutIndexChanged(4)

        val success = viewModel.split()

        assertTrue(success)
        assertEquals(TripStatus.SUPERSEDED, db.tripDao().findById("trip")?.status)
    }

    @Test
    fun aSecondTapAfterASuccessfulSplitIsIgnored() = runBlocking {
        seedTrip(pointCount = 10)
        loadReady()
        assertTrue(viewModel.split())

        assertFalse("a double tap must not attempt a second split", viewModel.split())
    }

    @Test
    fun splitFailsAndReEnablesTheScreenWhenTheTripChangedUnderneath() = runBlocking {
        seedTrip(pointCount = 10)
        loadReady()
        // Trashed by some other action after the screen loaded - ADR-015's re-check must refuse it.
        db.tripDao().trash("trip", deletedAt = 1L, updatedAt = 1L)

        assertFalse(viewModel.split())

        assertFalse("the buttons must come back so the user isn't stuck", (viewModel.uiState.value as SplitUiState.Ready).isSplitting)
    }
}
