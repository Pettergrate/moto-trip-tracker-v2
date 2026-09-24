package com.mototriptracker.app.feature.history

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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * HIS-001: mirrors `HomeViewModelTest`'s own regression coverage for the
 * exact reactivity bug UI-001 shipped with (`recentTripsFlow`'s one-shot
 * suspend read of `trip_statistics`) - this ViewModel was written after that
 * bug was found and fixed, but a real test proves it, rather than trusting
 * "I copied the fixed pattern" by inspection alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistoryViewModelTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var clock: FakeClock
    private lateinit var viewModel: HistoryViewModel

    @Before
    fun setUp() {
        // See `HomeViewModelTest`'s own KDoc for why Main only needs to exist
        // here (for `viewModelScope` to resolve), and why waiting for real
        // Flow emissions via `first{}`/`withTimeout` is used instead of a
        // TestDispatcher's virtual time - Room's own Flow queries run on a
        // real executor a virtual scheduler can't drive.
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = TestDatabaseFactory.createInMemory()
        clock = FakeClock(wallMillis = 1_000L)
        viewModel = HistoryViewModel(
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
        isFavorite = false,
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
        startElevationM = null,
        endElevationM = null,
        ascentM = null,
        descentM = null,
        validPointCount = 0,
        suspectPointCount = 0,
        rejectedPointCount = 0,
        gapCount = 0
    )

    @Test
    fun reflectsStatisticsInsertedAfterTheTripAlreadyExists() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 5_000L))

        val before = withTimeout(5_000) {
            viewModel.uiState.first { it.trips.isNotEmpty() }
        }.trips.single()
        assertNull("stats not processed yet - must be genuinely unknown, not zero", before.distanceMeters)

        db.tripStatisticsDao().upsert(statistics("trip-1", distanceM = 4200.0, totalDurationMs = 600_000L))

        val after = withTimeout(5_000) {
            viewModel.uiState.first { it.trips.singleOrNull()?.distanceMeters != null }
        }.trips.single()
        assertEquals(4200.0, after.distanceMeters!!, 0.0001)
        assertEquals(600_000L, after.durationMs)
    }

    @Test
    fun selectingOldestFirstReversesTheOrder() = runBlocking {
        db.tripDao().insert(trip("older", createdAt = 1_000L))
        db.tripDao().insert(trip("newer", createdAt = 2_000L))

        val initial = withTimeout(5_000) { viewModel.uiState.first { it.trips.size == 2 } }
        assertEquals(SortOrder.NEWEST_FIRST, initial.sortOrder)
        assertEquals(listOf("newer", "older"), initial.trips.map { it.tripId })

        viewModel.onSortOrderSelected(SortOrder.OLDEST_FIRST)

        val sorted = withTimeout(5_000) { viewModel.uiState.first { it.sortOrder == SortOrder.OLDEST_FIRST } }
        assertEquals(listOf("older", "newer"), sorted.trips.map { it.tripId })
    }

    @Test
    fun selectingLongestDistanceSortsByDistanceDescendingWithUnknownLast() = runBlocking {
        db.tripDao().insert(trip("no-stats", createdAt = 1_000L))
        db.tripDao().insert(trip("short", createdAt = 2_000L))
        db.tripDao().insert(trip("long", createdAt = 3_000L))
        db.tripStatisticsDao().upsert(statistics("short", distanceM = 1_000.0, totalDurationMs = 60_000L))
        db.tripStatisticsDao().upsert(statistics("long", distanceM = 50_000.0, totalDurationMs = 3_600_000L))

        viewModel.onSortOrderSelected(SortOrder.LONGEST_DISTANCE)

        val state = withTimeout(5_000) { viewModel.uiState.first { it.trips.size == 3 && it.trips.all { row -> row.tripId != "no-stats" || row.distanceMeters == null } } }
        assertEquals(listOf("long", "short", "no-stats"), state.trips.map { it.tripId })
    }

    @Test
    fun searchQueryFiltersByDisplayNameIncludingTheGeneratedFallback() = runBlocking {
        db.tripDao().insert(trip("named", createdAt = 1_000L, name = "Coastal loop"))
        db.tripDao().insert(trip("unnamed", createdAt = 2_000L))

        viewModel.onSearchQueryChanged("coastal")

        val state = withTimeout(5_000) { viewModel.uiState.first { it.trips.size == 1 } }
        assertEquals(listOf("named"), state.trips.map { it.tripId })
    }

    @Test
    fun favoritesOnlyFilterShowsOnlyFavoritedTrips() = runBlocking {
        db.tripDao().insert(trip("plain", createdAt = 1_000L))
        db.tripDao().insert(trip("starred", createdAt = 2_000L))
        db.tripDao().setFavorite("starred", true, updatedAt = 2_000L)

        viewModel.onFavoritesOnlyToggled()

        val state = withTimeout(5_000) { viewModel.uiState.first { it.trips.size == 1 } }
        assertEquals(listOf("starred"), state.trips.map { it.tripId })
    }

    @Test
    fun dateFilterExcludesTripsOlderThanTheSelectedWindow() = runBlocking {
        // A realistic epoch (unlike the shared `clock`'s 1_000L) so "start of
        // this month" and "long ago" are genuinely different real dates -
        // its own FakeClock/ViewModel, so the shared `viewModel` used by
        // every other test in this class is untouched.
        val realisticClock = FakeClock(wallMillis = 1_790_000_000_000L)
        val startOfThisMonth = java.util.Calendar.getInstance().apply {
            timeInMillis = realisticClock.wallClockMillis()
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        db.tripDao().insert(trip("this-month", createdAt = startOfThisMonth + 1_000L))
        db.tripDao().insert(trip("long-ago", createdAt = 1_000L))
        val dateFilterViewModel = HistoryViewModel(
            tripDao = db.tripDao(),
            tripStatisticsDao = db.tripStatisticsDao(),
            processedTrackPointDao = db.processedTrackPointDao(),
            clock = realisticClock
        )

        dateFilterViewModel.onDateFilterSelected(DateFilter.THIS_MONTH)

        val state = withTimeout(5_000) { dateFilterViewModel.uiState.first { it.trips.size == 1 } }
        assertEquals(listOf("this-month"), state.trips.map { it.tripId })
    }

    @Test
    fun toggleFavoritePersistsAndReflectsBackReactively() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 1_000L))
        val initial = withTimeout(5_000) { viewModel.uiState.first { it.trips.isNotEmpty() } }.trips.single()
        assertEquals(false, initial.isFavorite)

        viewModel.onToggleFavorite(initial.tripId, initial.isFavorite)

        val favorited = withTimeout(5_000) { viewModel.uiState.first { it.trips.single().isFavorite } }.trips.single()
        assertEquals(true, favorited.isFavorite)
        assertEquals(true, db.tripDao().findById("trip-1")!!.isFavorite)

        viewModel.onToggleFavorite(favorited.tripId, favorited.isFavorite)

        val unfavorited = withTimeout(5_000) { viewModel.uiState.first { !it.trips.single().isFavorite } }.trips.single()
        assertEquals(false, unfavorited.isFavorite)
    }
}
