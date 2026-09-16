package com.mototriptracker.app.core.database

import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.EndSource
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.testing.TestDatabaseFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TST-DB-002 (F0.12 §6): verifies the single-ACTIVE-capture invariant
 * (ADR-020 / REL-INV-001). Runs as a fast JVM unit test — no device/emulator
 * needed, via [TestDatabaseFactory] (TST-001) — see that class for why
 * Robolectric is involved and why `.setDriver(BundledSQLiteDriver())` is
 * deliberately not used.
 */
@RunWith(RobolectricTestRunner::class)
class TripCaptureDaoTest {

    private lateinit var db: MotoTripDatabase

    @Before
    fun createDb() {
        db = TestDatabaseFactory.createInMemory()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun capture(id: String) = TripCaptureEntity(
        id = id,
        status = CaptureStatus.ACTIVE,
        startedAt = 1_000L,
        endedAt = null,
        startElapsedRealtimeNanos = 1_000L,
        endElapsedRealtimeNanos = null,
        localTimeZoneId = "UTC",
        startSource = StartSource.MANUAL,
        endSource = null,
        detectorVersion = DetectorVersion(1),
        locationProfileVersion = LocationProfileVersion(1),
        createdAt = 1_000L,
        updatedAt = 1_000L
    )

    @Test
    fun secondActiveCaptureIsRejectedWhileFirstIsActive() = runTest {
        val dao = db.tripCaptureDao()

        assertTrue(dao.startCaptureIfNoneActive(capture("capture-1")))
        assertFalse(dao.startCaptureIfNoneActive(capture("capture-2")))

        assertNotNull(dao.findById("capture-1"))
        assertEquals(null, dao.findById("capture-2"))
    }

    @Test
    fun newActiveCaptureIsAcceptedOnceThePreviousOneIsProperlyCompleted() = runTest {
        val dao = db.tripCaptureDao()

        assertTrue(dao.startCaptureIfNoneActive(capture("capture-1")))
        dao.completeActiveCapture(
            id = "capture-1",
            endedAt = 2_000L,
            endElapsedRealtimeNanos = 2_000L,
            endSource = EndSource.MANUAL,
            updatedAt = 2_000L
        )

        val completed = dao.findById("capture-1")
        assertEquals(CaptureStatus.COMPLETED, completed?.status)
        assertNotNull("a COMPLETED capture must have endedAt set (F0.7 §6.1/§15)", completed?.endedAt)

        assertTrue(dao.startCaptureIfNoneActive(capture("capture-2")))
    }

    @Test
    fun completingAnAlreadyCompletedCaptureIsANoOp() = runTest {
        val dao = db.tripCaptureDao()
        dao.startCaptureIfNoneActive(capture("capture-1"))
        dao.completeActiveCapture("capture-1", 2_000L, 2_000L, EndSource.MANUAL, 2_000L)

        // Idempotent per ADR-015: the WHERE status = ACTIVE guard means a
        // second completion attempt changes nothing.
        dao.completeActiveCapture("capture-1", 9_999L, 9_999L, EndSource.AUTO, 9_999L)

        val capture = dao.findById("capture-1")
        assertEquals(2_000L, capture?.endedAt)
        assertEquals(EndSource.MANUAL, capture?.endSource)
    }

    /**
     * Uses real threads (Dispatchers.IO via runBlocking), not runTest's
     * virtual-time scheduler, so this is a genuine concurrency test rather
     * than two sequential calls dressed up as one — a gap a review found in
     * an earlier version of this test.
     */
    @Test
    fun onlyOneOfManyConcurrentStartsSucceeds() = runBlocking {
        val dao = db.tripCaptureDao()
        val attempts = 20

        val results = (1..attempts)
            .map { i -> async(Dispatchers.IO) { dao.startCaptureIfNoneActive(capture("capture-$i")) } }
            .awaitAll()

        assertEquals(1, results.count { it })
        assertEquals(1, dao.countByStatus(CaptureStatus.ACTIVE))
    }
}
