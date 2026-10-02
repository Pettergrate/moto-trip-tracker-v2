package com.mototriptracker.app.core.database

import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.EndSource
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.testing.TestDatabaseFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** DET-008: where an automatic Finish ends the Trip - the last point at or before the moment the vehicle stopped. */
@RunWith(RobolectricTestRunner::class)
class RawTrackPointDaoTest {

    private lateinit var db: MotoTripDatabase

    @Before
    fun createDb() {
        db = TestDatabaseFactory.createInMemory()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun point(captureId: String, sequenceNumber: Long, elapsedNanos: Long) = RawTrackPointEntity(
        captureId = captureId,
        sequenceNumber = sequenceNumber,
        capturedAt = elapsedNanos / 1_000_000,
        elapsedRealtimeNanos = elapsedNanos,
        receivedAtElapsedRealtimeNanos = elapsedNanos,
        latitude = 10.0,
        longitude = -84.0,
        horizontalAccuracyM = 5f,
        altitudeEllipsoidM = null,
        altitudeMslM = null,
        verticalAccuracyM = null,
        speedMps = null,
        speedAccuracyMps = null,
        bearingDeg = null,
        bearingAccuracyDeg = null,
        provider = null,
        isMock = null,
        requestProfileId = "test",
        callbackBatchId = null,
        detectorStateSnapshot = "{}"
    )

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

    private suspend fun seed() {
        // raw_track_point.captureId is a foreign key: the two captures must exist, and only one may be ACTIVE (ADR-020).
        db.tripCaptureDao().insert(capture("capture-a"))
        db.tripCaptureDao().insert(capture("capture-b").copy(status = CaptureStatus.COMPLETED, endedAt = 2_000L, endElapsedRealtimeNanos = 2_000L, endSource = EndSource.MANUAL))
        val dao = db.rawTrackPointDao()
        dao.insert(point("capture-a", 0, 10_000_000_000L))
        dao.insert(point("capture-a", 1, 20_000_000_000L))
        dao.insert(point("capture-a", 2, 30_000_000_000L))
        dao.insert(point("capture-b", 0, 5_000_000_000L))
    }

    @Test
    fun returnsTheLastPointRecordedAtOrBeforeTheBound() = runTest {
        seed()

        assertEquals(1L, db.rawTrackPointDao().findLastAtOrBefore("capture-a", 25_000_000_000L)?.sequenceNumber)
    }

    @Test
    fun aPointExactlyAtTheBoundIsIncluded() = runTest {
        seed()

        assertEquals(1L, db.rawTrackPointDao().findLastAtOrBefore("capture-a", 20_000_000_000L)?.sequenceNumber)
    }

    @Test
    fun aBoundAfterEveryPointReturnsTheLastOne() = runTest {
        seed()

        assertEquals(2L, db.rawTrackPointDao().findLastAtOrBefore("capture-a", 999_000_000_000L)?.sequenceNumber)
    }

    @Test
    fun aBoundBeforeEveryPointReturnsNothingSoNothingIsTrimmed() = runTest {
        seed()

        assertNull(db.rawTrackPointDao().findLastAtOrBefore("capture-a", 1_000_000_000L)?.sequenceNumber)
    }

    @Test
    fun onlyLooksAtTheRequestedCapture() = runTest {
        seed()

        assertEquals(0L, db.rawTrackPointDao().findLastAtOrBefore("capture-b", 999_000_000_000L)?.sequenceNumber)
    }
}
