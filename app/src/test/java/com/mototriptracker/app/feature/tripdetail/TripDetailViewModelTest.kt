package com.mototriptracker.app.feature.tripdetail

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.ProcessedTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.database.entity.TripStatisticsEntity
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TripDetailViewModelTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var viewModel: TripDetailViewModel
    private val clock = FakeClock(wallMillis = 10_000L, elapsedNanos = 10_000L)

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = TestDatabaseFactory.createInMemory()
        viewModel = TripDetailViewModel(
            tripDao = db.tripDao(),
            tripStatisticsDao = db.tripStatisticsDao(),
            processedTrackPointDao = db.processedTrackPointDao(),
            clock = clock
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun trip(id: String, createdAt: Long, name: String? = null) = TripEntity(
        id = id,
        status = TripStatus.COMPLETED,
        name = name,
        isFavorite = true,
        motorcycleId = null,
        routeId = null,
        notes = null,
        createdAt = createdAt,
        updatedAt = createdAt,
        deletedAt = null
    )

    private fun statistics(tripId: String) = TripStatisticsEntity(
        tripId = tripId,
        processingVersion = TripProcessingWorker.CURRENT_PROCESSING_VERSION,
        computedAt = 1_000L,
        distanceM = 5_000.0,
        totalDurationMs = 1_800_000L,
        movingDurationMs = 1_500_000L,
        stoppedDurationMs = 300_000L,
        manualPauseDurationMs = 60_000L,
        maxSpeedMps = 27.0,
        averageSpeedMps = 8.0,
        averageMovingSpeedMps = 9.5,
        minElevationM = null,
        maxElevationM = null,
        ascentM = null,
        descentM = null,
        validPointCount = 100,
        suspectPointCount = 0,
        rejectedPointCount = 0,
        gapCount = 0
    )

    @Test
    fun loadedStateReflectsTheTripAndItsStatistics() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))
        db.tripStatisticsDao().upsert(statistics("trip-1"))

        viewModel.load("trip-1")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && it.distanceMeters != null }
        } as TripDetailUiState.Loaded

        assertEquals("trip-1", state.tripId)
        assertFalse("no custom name was set - must fall back", state.isUserNamed)
        assertTrue(state.isFavorite)
        assertEquals(5_000.0, state.distanceMeters!!, 0.0001)
        assertEquals(1_800_000L, state.totalDurationMs)
        assertEquals(60_000L, state.manualPauseDurationMs)
    }

    @Test
    fun loadedStateExposesTheSimplifiedRouteFromProcessedTrackPoints() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))
        // A straight line of collinear points - MAP-001's simplifyRoute
        // should collapse it to just the two endpoints.
        val points = (0..9).map { i ->
            ProcessedTrackPointEntity(
                tripId = "trip-1",
                processingVersion = TripProcessingWorker.CURRENT_PROCESSING_VERSION,
                orderIndex = i,
                latitude = 10.0,
                longitude = -20.0 + i * 0.0001,
                sourceCaptureId = "capture-1",
                sourceSequenceNumber = i.toLong(),
                pointRole = null
            )
        }
        db.processedTrackPointDao().insertAll(points)

        viewModel.load("trip-1")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && it.routePoints.isNotEmpty() }
        } as TripDetailUiState.Loaded

        assertEquals(2, state.routePoints.size)
        assertEquals(10.0, state.routePoints.first().latitude, 0.0001)
        assertEquals(10.0, state.routePoints.last().latitude, 0.0001)
    }

    @Test
    fun stateBecomesNotFoundForAnUnknownTripId() = runBlocking {
        viewModel.load("does-not-exist")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it !is TripDetailUiState.Loading }
        }

        assertEquals(TripDetailUiState.NotFound, state)
    }

    @Test
    fun onRenamePersistsTheNewNameAndReflectsBackReactively() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))
        viewModel.load("trip-1")
        withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded } }

        viewModel.onRename("Coastal loop")

        val renamed = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && it.isUserNamed }
        } as TripDetailUiState.Loaded
        assertEquals("Coastal loop", renamed.displayName)
        assertEquals(clock.wallClockMillis(), db.tripDao().findById("trip-1")?.updatedAt)
    }

    @Test
    fun onRenameWithBlankNameRevertsToTheFallbackName() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L, name = "Coastal loop"))
        viewModel.load("trip-1")
        withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded && it.isUserNamed } }

        viewModel.onRename("   ")

        val reverted = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && !it.isUserNamed }
        } as TripDetailUiState.Loaded
        assertNull(db.tripDao().findById("trip-1")?.name)
        assertFalse(reverted.isUserNamed)
    }
}
