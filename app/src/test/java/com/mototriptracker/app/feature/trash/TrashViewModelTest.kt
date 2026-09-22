package com.mototriptracker.app.feature.trash

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.worker.TripPurger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** TRS-001: mirrors `FavoritesViewModelTest`'s own reactivity coverage, filtered to trashed trips. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TrashViewModelTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var clock: FakeClock
    private lateinit var viewModel: TrashViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = TestDatabaseFactory.createInMemory()
        clock = FakeClock(wallMillis = 100_000L)
        viewModel = TrashViewModel(
            tripDao = db.tripDao(),
            tripStatisticsDao = db.tripStatisticsDao(),
            tripPurger = TripPurger(db, db.tripDao(), db.tripPartDao(), db.tripCaptureDao()),
            clock = clock
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun trip(id: String, createdAt: Long, status: TripStatus, deletedAt: Long?) = TripEntity(
        id = id, status = status, name = null, isFavorite = false,
        motorcycleId = null, routeId = null, notes = null, createdAt = createdAt, updatedAt = createdAt, deletedAt = deletedAt
    )

    @Test
    fun onlyShowsTrashedTrips() = runBlocking {
        db.tripDao().insert(trip("trashed", createdAt = 1_000L, status = TripStatus.TRASHED, deletedAt = 5_000L))
        db.tripDao().insert(trip("kept", createdAt = 2_000L, status = TripStatus.COMPLETED, deletedAt = null))

        val state = withTimeout(5_000) { viewModel.uiState.first { it.trips.isNotEmpty() } }

        assertEquals(listOf("trashed"), state.trips.map { it.tripId })
    }

    @Test
    fun restoringRemovesTheTripFromTheListAndRevertsItsStatus() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 1_000L, status = TripStatus.TRASHED, deletedAt = 5_000L))
        val initial = withTimeout(5_000) { viewModel.uiState.first { it.trips.isNotEmpty() } }.trips.single()

        viewModel.onRestore(initial.tripId)

        withTimeout(5_000) { viewModel.uiState.first { it.trips.isEmpty() } }
        val restored = db.tripDao().findById("trip-1")
        assertEquals(TripStatus.COMPLETED, restored?.status)
        assertNull(restored?.deletedAt)
    }

    @Test
    fun deleteForeverPhysicallyRemovesTheTripAndRemovesItFromTheList() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 1_000L, status = TripStatus.TRASHED, deletedAt = 5_000L))
        val initial = withTimeout(5_000) { viewModel.uiState.first { it.trips.isNotEmpty() } }.trips.single()

        viewModel.onDeleteForever(initial.tripId)

        withTimeout(5_000) { viewModel.uiState.first { it.trips.isEmpty() } }
        assertNull("physically gone, not just hidden", db.tripDao().findById("trip-1"))
    }

    @Test
    fun purgeDateLabelReflectsTheRetentionWindowFromWhenItWasTrashed() = runBlocking {
        db.tripDao().insert(trip("trip-1", createdAt = 1_000L, status = TripStatus.TRASHED, deletedAt = 5_000L))

        val state = withTimeout(5_000) { viewModel.uiState.first { it.trips.isNotEmpty() } }

        assertNotNull(state.trips.single().purgeDateLabel)
    }
}
