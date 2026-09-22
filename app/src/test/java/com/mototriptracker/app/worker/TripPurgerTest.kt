package com.mototriptracker.app.worker

import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.database.entity.TripPartEntity
import com.mototriptracker.app.core.database.entity.TripStatisticsEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TRS-001. Proves domain-data-model.md §15/§16's reference-counting rule for
 * real: a purged Trip's own rows go away, its TripCapture (and that
 * capture's raw evidence, ADR-006) only goes with it once nothing else
 * points to that capture - `TripPartEntity`'s own `RESTRICT` FK to
 * `trip_capture` would throw rather than silently corrupt anything if this
 * got the order wrong.
 */
@RunWith(RobolectricTestRunner::class)
class TripPurgerTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var purger: TripPurger

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        purger = TripPurger(db, db.tripDao(), db.tripPartDao(), db.tripCaptureDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun trip(id: String) = TripEntity(
        id = id, status = TripStatus.TRASHED, name = null, isFavorite = false,
        motorcycleId = null, routeId = null, notes = null, createdAt = 1_000L, updatedAt = 1_000L, deletedAt = 1_000L
    )

    private fun capture(id: String) = TripCaptureEntity(
        id = id, status = CaptureStatus.COMPLETED, startedAt = 0L, endedAt = 1_000L,
        startElapsedRealtimeNanos = 0L, endElapsedRealtimeNanos = 1_000L, localTimeZoneId = "UTC",
        startSource = StartSource.MANUAL, endSource = null,
        detectorVersion = DetectorVersion(0), locationProfileVersion = LocationProfileVersion(0),
        createdAt = 0L, updatedAt = 0L
    )

    private fun part(id: String, tripId: String, captureId: String) = TripPartEntity(
        id = id, tripId = tripId, captureId = captureId, orderIndex = 0,
        startElapsedRealtimeNanos = 0L, endElapsedRealtimeNanos = 1_000L,
        startSequenceNumber = null, endSequenceNumber = null
    )

    private fun rawPoint(captureId: String, sequenceNumber: Long) = RawTrackPointEntity(
        captureId = captureId, sequenceNumber = sequenceNumber, capturedAt = 0L,
        elapsedRealtimeNanos = 0L, receivedAtElapsedRealtimeNanos = 0L,
        latitude = 10.0, longitude = -20.0, horizontalAccuracyM = 5.0f,
        altitudeEllipsoidM = null, altitudeMslM = null, verticalAccuracyM = null,
        speedMps = null, speedAccuracyMps = null, bearingDeg = null, bearingAccuracyDeg = null,
        provider = "fused", isMock = false, requestProfileId = "test-profile",
        callbackBatchId = null, detectorStateSnapshot = "TRACKING"
    )

    private fun statistics(tripId: String) = TripStatisticsEntity(
        tripId = tripId, processingVersion = TripProcessingWorker.CURRENT_PROCESSING_VERSION, computedAt = 1_000L,
        distanceM = 100.0, totalDurationMs = 1_000L, movingDurationMs = null, stoppedDurationMs = null,
        manualPauseDurationMs = 0L, maxSpeedMps = null, averageSpeedMps = null, averageMovingSpeedMps = null,
        minElevationM = null, maxElevationM = null, ascentM = null, descentM = null,
        validPointCount = 1, suspectPointCount = 0, rejectedPointCount = 0, gapCount = 0
    )

    @Test
    fun purgingATripDeletesItsOwnRowsAndItsNowUnreferencedCaptureAndRawData() = runTest {
        db.tripDao().insert(trip("trip-1"))
        db.tripCaptureDao().insert(capture("capture-1"))
        db.tripPartDao().insert(part("part-1", "trip-1", "capture-1"))
        db.tripStatisticsDao().upsert(statistics("trip-1"))
        db.rawTrackPointDao().insert(rawPoint("capture-1", 0))

        val purgedCaptures = purger.purge("trip-1")

        assertEquals(1, purgedCaptures)
        assertNull(db.tripDao().findById("trip-1"))
        assertNull(db.tripPartDao().findByCaptureId("capture-1"))
        assertNull(db.tripStatisticsDao().findByTripAndVersion("trip-1", TripProcessingWorker.CURRENT_PROCESSING_VERSION))
        assertNull("capture must be gone once unreferenced", db.tripCaptureDao().findById("capture-1"))
        assertEquals("raw evidence cascades away with its capture, not before", 0, db.rawTrackPointDao().countByCapture("capture-1"))
    }

    @Test
    fun purgingATripLeavesAStillReferencedCaptureAndItsRawDataIntact() = runTest {
        db.tripDao().insert(trip("trip-a"))
        db.tripDao().insert(trip("trip-b"))
        db.tripCaptureDao().insert(capture("shared-capture"))
        db.tripPartDao().insert(part("part-a", "trip-a", "shared-capture"))
        db.tripPartDao().insert(part("part-b", "trip-b", "shared-capture"))
        db.rawTrackPointDao().insert(rawPoint("shared-capture", 0))

        val purgedCaptures = purger.purge("trip-a")

        assertEquals("trip-b's TripPart still references it", 0, purgedCaptures)
        assertNull(db.tripDao().findById("trip-a"))
        assertNotNull("still referenced by trip-b, must survive", db.tripCaptureDao().findById("shared-capture"))
        assertEquals(1, db.rawTrackPointDao().countByCapture("shared-capture"))

        val secondPurge = purger.purge("trip-b")

        assertEquals("now genuinely unreferenced", 1, secondPurge)
        assertNull(db.tripCaptureDao().findById("shared-capture"))
        assertEquals(0, db.rawTrackPointDao().countByCapture("shared-capture"))
    }
}
