package com.mototriptracker.app.tracking.persistence

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.testing.FakeLocationGateway
import com.mototriptracker.app.testing.FakeProcessingScheduler
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** REC-006: the decorator the debug injector plugs in through must not change behaviour when nothing is injected. */
@RunWith(RobolectricTestRunner::class)
class FaultInjectingRawTrackPointDaoTest {

    private class Switch : RawWriteFaultInjector {
        var failing = false
        override fun beforeRawInsert() {
            if (failing) throw IllegalStateException("injected")
        }
        override fun bufferCapacity(): Int? = null
    }

    private lateinit var db: MotoTripDatabase
    private lateinit var captureId: String

    @Before
    fun setUp() = runTest {
        db = TestDatabaseFactory.createInMemory()
        val coordinator = TrackingSessionCoordinator(
            database = db, tripCaptureDao = db.tripCaptureDao(), diagnosticEventDao = db.diagnosticEventDao(),
            rawTrackPointDao = db.rawTrackPointDao(), captureEventDao = db.captureEventDao(), tripDao = db.tripDao(),
            tripPartDao = db.tripPartDao(), manualPauseIntervalDao = db.manualPauseIntervalDao(),
            locationGateway = FakeLocationGateway(emptyList()), processingScheduler = FakeProcessingScheduler(),
            clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L), idGenerator = FakeIdGenerator(prefix = "capture")
        )
        captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun point(seq: Long) = RawTrackPointEntity(
        captureId = captureId, sequenceNumber = seq, capturedAt = seq, elapsedRealtimeNanos = seq * 1_000L,
        receivedAtElapsedRealtimeNanos = null, latitude = 10.0, longitude = -20.0, horizontalAccuracyM = 5f,
        altitudeEllipsoidM = null, altitudeMslM = null, verticalAccuracyM = null, speedMps = null, speedAccuracyMps = null,
        bearingDeg = null, bearingAccuracyDeg = null, provider = null, isMock = false, requestProfileId = "p",
        callbackBatchId = null, detectorStateSnapshot = "TRACKING"
    )

    @Test
    fun withNoFaultInjectedItWritesAndReadsExactlyLikeTheRealDao() = runTest {
        val dao = FaultInjectingRawTrackPointDao(db.rawTrackPointDao(), Switch())

        dao.insert(point(0))
        dao.insert(point(1))

        assertEquals("reads are delegated untouched", 2, dao.countByCapture(captureId))
        assertEquals(1L, dao.maxSequenceNumber(captureId))
    }

    @Test
    fun anInjectedFaultStopsTheInsertBeforeItReachesTheDatabase() = runTest {
        val faults = Switch()
        val dao = FaultInjectingRawTrackPointDao(db.rawTrackPointDao(), faults)
        dao.insert(point(0))
        faults.failing = true

        val result = runCatching { dao.insert(point(1)) }

        assertTrue(result.isFailure)
        assertEquals("nothing was written by the failed insert", 1, db.rawTrackPointDao().countByCapture(captureId))
    }
}
