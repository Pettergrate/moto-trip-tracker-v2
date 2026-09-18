package com.mototriptracker.app.core.database

import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.testing.TestDatabaseFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** HIS-001: the DAO methods History/Trip Detail added on top of UI-001's `observeRecent`. */
@RunWith(RobolectricTestRunner::class)
class TripDaoTest {

    private lateinit var db: MotoTripDatabase

    @Before
    fun createDb() {
        db = TestDatabaseFactory.createInMemory()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun trip(id: String, createdAt: Long, name: String? = null, status: TripStatus = TripStatus.COMPLETED, deletedAt: Long? = null) =
        TripEntity(
            id = id,
            status = status,
            name = name,
            isFavorite = false,
            motorcycleId = null,
            routeId = null,
            notes = null,
            createdAt = createdAt,
            updatedAt = createdAt,
            deletedAt = deletedAt
        )

    @Test
    fun observeAllDescendingOrdersNewestFirst() = runTest {
        val dao = db.tripDao()
        dao.insert(trip("a", createdAt = 1_000L))
        dao.insert(trip("b", createdAt = 3_000L))
        dao.insert(trip("c", createdAt = 2_000L))

        val ids = dao.observeAllDescending().first().map { it.id }

        assertEquals(listOf("b", "c", "a"), ids)
    }

    @Test
    fun observeAllAscendingOrdersOldestFirst() = runTest {
        val dao = db.tripDao()
        dao.insert(trip("a", createdAt = 1_000L))
        dao.insert(trip("b", createdAt = 3_000L))
        dao.insert(trip("c", createdAt = 2_000L))

        val ids = dao.observeAllAscending().first().map { it.id }

        assertEquals(listOf("a", "c", "b"), ids)
    }

    @Test
    fun observeAllExcludesNonCompletedAndSoftDeletedTrips() = runTest {
        val dao = db.tripDao()
        dao.insert(trip("completed", createdAt = 1_000L))
        dao.insert(trip("superseded", createdAt = 2_000L, status = TripStatus.SUPERSEDED))
        dao.insert(trip("deleted", createdAt = 3_000L, deletedAt = 4_000L))

        val ids = dao.observeAllDescending().first().map { it.id }

        assertEquals(listOf("completed"), ids)
    }

    @Test
    fun observeByIdReflectsTheCurrentRowOrNullWhenMissing() = runTest {
        val dao = db.tripDao()
        dao.insert(trip("a", createdAt = 1_000L))

        assertEquals("a", dao.observeById("a").first()?.id)
        assertNull(dao.observeById("does-not-exist").first())
    }

    @Test
    fun renameUpdatesNameAndUpdatedAt() = runTest {
        val dao = db.tripDao()
        dao.insert(trip("a", createdAt = 1_000L))

        dao.rename("a", "Coastal loop", updatedAt = 5_000L)

        val renamed = dao.observeById("a").first()
        assertEquals("Coastal loop", renamed?.name)
        assertEquals(5_000L, renamed?.updatedAt)
    }

    @Test
    fun renameToNullRevertsToNoCustomName() = runTest {
        val dao = db.tripDao()
        dao.insert(trip("a", createdAt = 1_000L, name = "Coastal loop"))

        dao.rename("a", null, updatedAt = 5_000L)

        assertNull(dao.observeById("a").first()?.name)
    }
}
