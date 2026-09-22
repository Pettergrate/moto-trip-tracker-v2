package com.mototriptracker.app.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.UuidIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.testing.TestDatabaseFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** TRS-001/PRIV-013. Proves the worker's own eligibility window and diagnostic logging - [com.mototriptracker.app.domain.TripPurgerTest] proves the deletion logic itself. */
@RunWith(RobolectricTestRunner::class)
class TrashPurgeWorkerTest {

    private lateinit var db: MotoTripDatabase
    private val clock = FakeClock(wallMillis = 100 * DAY_MS)

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun trip(id: String, deletedAt: Long?, status: TripStatus = TripStatus.TRASHED) = TripEntity(
        id = id, status = status, name = null, isFavorite = false,
        motorcycleId = null, routeId = null, notes = null, createdAt = 0L, updatedAt = 0L, deletedAt = deletedAt
    )

    private fun buildWorker(): TrashPurgeWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                TrashPurgeWorker(
                    context = appContext,
                    params = workerParameters,
                    tripDao = db.tripDao(),
                    tripPurger = TripPurger(db, db.tripDao(), db.tripPartDao(), db.tripCaptureDao()),
                    diagnosticEventDao = db.diagnosticEventDao(),
                    clock = clock,
                    idGenerator = UuidIdGenerator()
                )
        }
        return TestListenableWorkerBuilder<TrashPurgeWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(factory)
            .build()
    }

    @Test
    fun purgesOnlyTrashedTripsPastTheRetentionWindow() = runTest {
        val now = clock.wallClockMillis()
        db.tripDao().insert(trip("too-recent", deletedAt = now - TrashPurgeWorker.RETENTION_MS + DAY_MS))
        db.tripDao().insert(trip("eligible", deletedAt = now - TrashPurgeWorker.RETENTION_MS - DAY_MS))
        db.tripDao().insert(trip("not-trashed", deletedAt = null, status = TripStatus.COMPLETED))

        val result = buildWorker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertNotNull("still within the retention window", db.tripDao().findById("too-recent"))
        assertNull("past the retention window", db.tripDao().findById("eligible"))
        assertNotNull("never trashed", db.tripDao().findById("not-trashed"))
    }

    @Test
    fun logsADiagnosticEventWithTheRealCounts() = runTest {
        val now = clock.wallClockMillis()
        db.tripDao().insert(trip("eligible-1", deletedAt = now - TrashPurgeWorker.RETENTION_MS - DAY_MS))
        db.tripDao().insert(trip("eligible-2", deletedAt = now - TrashPurgeWorker.RETENTION_MS - DAY_MS))

        buildWorker().doWork()

        val event = db.diagnosticEventDao().findAll().single { it.eventType == "TRASH_PURGE_COMPLETED" }
        assertEquals("2", event.metadata["tripsPurged"])
    }

    @Test
    fun isANoOpWhenNothingIsEligible() = runTest {
        val result = buildWorker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, db.diagnosticEventDao().findAll().size)
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1_000L
    }
}
