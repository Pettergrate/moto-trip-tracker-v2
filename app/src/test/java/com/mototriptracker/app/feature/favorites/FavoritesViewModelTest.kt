package com.mototriptracker.app.feature.favorites

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.database.MotoTripDatabase
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** FAV-001: mirrors `HistoryViewModelTest`'s own reactivity coverage, filtered to favorites. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FavoritesViewModelTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var clock: FakeClock
    private lateinit var viewModel: FavoritesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = TestDatabaseFactory.createInMemory()
        clock = FakeClock(wallMillis = 1_000L)
        viewModel = FavoritesViewModel(tripDao = db.tripDao(), tripStatisticsDao = db.tripStatisticsDao(), clock = clock)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun trip(id: String, createdAt: Long, isFavorite: Boolean) = TripEntity(
        id = id,
        status = TripStatus.COMPLETED,
        name = null,
        isFavorite = isFavorite,
        motorcycleId = null,
        routeId = null,
        notes = null,
        createdAt = createdAt,
        updatedAt = createdAt,
        deletedAt = null
    )

    private fun statistics(tripId: String, distanceM: Double, totalDurationMs: Long) = TripStatisticsEntity(
        tripId = tripId,
        processingVersion = TripProcessingWorker.CURRENT_PROCESSING_VERSION,
        computedAt = 1_000L,
        distanceM = distanceM,
        totalDurationMs = totalDurationMs,
        movingDurationMs = null,
        stoppedDurationMs = null,
        manualPauseDurationMs = 0L,
        maxSpeedMps = null,
        averageSpeedMps = null,
        averageMovingSpeedMps = null,
        minElevationM = null,
        maxElevationM = null,
        ascentM = null,
        descentM = null,
        validPointCount = 0,
        suspectPointCount = 0,
        rejectedPointCount = 0,
        gapCount = 0
    )

    @Test
    fun onlyShowsFavoritedTrips() = runBlocking {
        db.tripDao().insert(trip("favorite", createdAt = 2_000L, isFavorite = true))
        db.tripDao().insert(trip("not-favorite", createdAt = 1_000L, isFavorite = false))

        val state = withTimeout(5_000) { viewModel.uiState.first { it.trips.isNotEmpty() } }

        assertEquals(listOf("favorite"), state.trips.map { it.tripId })
    }

    @Test
    fun reflectsStatisticsInsertedAfterTheTripAlreadyExists() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L, isFavorite = true))

        db.tripStatisticsDao().upsert(statistics("trip-1", distanceM = 4200.0, totalDurationMs = 600_000L))

        val after = withTimeout(5_000) {
            viewModel.uiState.first { it.trips.singleOrNull()?.distanceMeters != null }
        }.trips.single()
        assertEquals(4200.0, after.distanceMeters!!, 0.0001)
        assertEquals(600_000L, after.durationMs)
    }

    @Test
    fun unfavoritingRemovesTheTripFromTheListReactively() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 1_000L, isFavorite = true))
        val initial = withTimeout(5_000) { viewModel.uiState.first { it.trips.isNotEmpty() } }.trips.single()
        assertTrue(initial.isFavorite)

        viewModel.onToggleFavorite(initial.tripId, initial.isFavorite)

        val after = withTimeout(5_000) { viewModel.uiState.first { it.trips.isEmpty() } }
        assertEquals(emptyList<String>(), after.trips.map { it.tripId })
        assertEquals(false, db.tripDao().findById("trip-1")?.isFavorite)
        assertEquals(clock.wallClockMillis(), db.tripDao().findById("trip-1")?.updatedAt)
    }
}
