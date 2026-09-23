package com.mototriptracker.app.feature.tripdetail

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.ProcessedTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.database.entity.TripStatisticsEntity
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.feature.common.formatDateTime
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

    private fun statistics(
        tripId: String,
        computedAt: Long = 1_000L,
        startElevationM: Double? = null,
        endElevationM: Double? = null,
        rejectedPointCount: Int = 0,
        gapCount: Int = 0
    ) = TripStatisticsEntity(
        tripId = tripId,
        processingVersion = TripProcessingWorker.CURRENT_PROCESSING_VERSION,
        computedAt = computedAt,
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
        startElevationM = startElevationM,
        endElevationM = endElevationM,
        ascentM = null,
        descentM = null,
        validPointCount = 100,
        suspectPointCount = 0,
        rejectedPointCount = rejectedPointCount,
        gapCount = gapCount
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
    fun loadedStateHasNoCalculatedAtLabelOrQualityNoteBeforeStatisticsExist() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))

        viewModel.load("trip-1")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded }
        } as TripDetailUiState.Loaded
        assertNull("nothing to date yet - the footer must not appear", state.calculatedAtLabel)
        assertNull(state.qualityNote)
    }

    @Test
    fun loadedStateExposesCalculatedAtLabelOnceStatisticsExist() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))
        db.tripStatisticsDao().upsert(statistics("trip-1", computedAt = 42_000L))

        viewModel.load("trip-1")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && it.calculatedAtLabel != null }
        } as TripDetailUiState.Loaded
        assertEquals(formatDateTime(42_000L), state.calculatedAtLabel)
    }

    @Test
    fun loadedStateHasNoQualityNoteForACleanTrip() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))
        db.tripStatisticsDao().upsert(statistics("trip-1", rejectedPointCount = 0, gapCount = 0))

        viewModel.load("trip-1")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && it.calculatedAtLabel != null }
        } as TripDetailUiState.Loaded
        assertNull("a clean trip gets no decorative quality badge", state.qualityNote)
    }

    @Test
    fun loadedStateShowsAQualityNoteWhenPointsWereExcludedOrGapsExist() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))
        db.tripStatisticsDao().upsert(statistics("trip-1", rejectedPointCount = 3, gapCount = 1))

        viewModel.load("trip-1")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && it.qualityNote != null }
        } as TripDetailUiState.Loaded
        assertEquals("3 GPS points excluded · 1 signal gap", state.qualityNote)
    }

    @Test
    fun loadedStateExposesStartAndEndElevation() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))
        db.tripStatisticsDao().upsert(statistics("trip-1", startElevationM = 100.0, endElevationM = 140.0))

        viewModel.load("trip-1")

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && it.startElevationM != null }
        } as TripDetailUiState.Loaded
        assertEquals(100.0, state.startElevationM!!, 0.0001)
        assertEquals(140.0, state.endElevationM!!, 0.0001)
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

    @Test
    fun onToggleFavoritePersistsAndReflectsBackReactively() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L).copy(isFavorite = false))
        viewModel.load("trip-1")
        withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded && !it.isFavorite } }

        viewModel.onToggleFavorite()

        val favorited = withTimeout(5_000) {
            viewModel.uiState.first { it is TripDetailUiState.Loaded && it.isFavorite }
        } as TripDetailUiState.Loaded
        assertTrue(favorited.isFavorite)
        assertEquals(true, db.tripDao().findById("trip-1")?.isFavorite)
        assertEquals(clock.wallClockMillis(), db.tripDao().findById("trip-1")?.updatedAt)

        viewModel.onToggleFavorite()

        withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded && !it.isFavorite } }
        assertEquals(false, db.tripDao().findById("trip-1")?.isFavorite)
    }

    @Test
    fun onTrashSoftDeletesTheTrip() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))
        viewModel.load("trip-1")
        withTimeout(5_000) { viewModel.uiState.first { it is TripDetailUiState.Loaded } }

        viewModel.onTrash()

        val trashed = db.tripDao().findById("trip-1")
        assertEquals(TripStatus.TRASHED, trashed?.status)
        assertEquals(clock.wallClockMillis(), trashed?.deletedAt)
        assertEquals(clock.wallClockMillis(), trashed?.updatedAt)
    }
}
