package com.mototriptracker.app.feature.history

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
        viewModel = HistoryViewModel(tripDao = db.tripDao(), tripStatisticsDao = db.tripStatisticsDao())
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
    fun toggleSortOrderSwitchesBetweenNewestAndOldestFirst() = runBlocking {
        db.tripDao().insert(trip("older", createdAt = 1_000L))
        db.tripDao().insert(trip("newer", createdAt = 2_000L))

        val initial = withTimeout(5_000) { viewModel.uiState.first { it.trips.size == 2 } }
        assertEquals(SortOrder.NEWEST_FIRST, initial.sortOrder)
        assertEquals(listOf("newer", "older"), initial.trips.map { it.tripId })

        viewModel.onToggleSortOrder()

        val toggled = withTimeout(5_000) { viewModel.uiState.first { it.sortOrder == SortOrder.OLDEST_FIRST } }
        assertEquals(listOf("older", "newer"), toggled.trips.map { it.tripId })
    }
}
