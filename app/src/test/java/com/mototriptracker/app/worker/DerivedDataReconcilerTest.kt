package com.mototriptracker.app.worker

import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.database.entity.TripPartEntity
import com.mototriptracker.app.core.database.entity.TripStatisticsEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.ProcessingVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.testing.FakeProcessingScheduler
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * EDT-004: after a crash between a structural edit's commit and its
 * processing being enqueued (reliability-recovery.md §20), or a failed
 * worker, or a `processingVersion` bump, visible Trips with no derived data
 * for the current version are found and their processing re-enqueued.
 */
@RunWith(RobolectricTestRunner::class)
class DerivedDataReconcilerTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var scheduler: FakeProcessingScheduler
    private lateinit var reconciler: DerivedDataReconciler
    private val current = TripProcessingWorker.CURRENT_PROCESSING_VERSION

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        scheduler = FakeProcessingScheduler()
        reconciler = DerivedDataReconciler(db.tripDao(), db.tripPartDao(), scheduler)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun trip(id: String, status: TripStatus = TripStatus.COMPLETED, withPart: Boolean = true) {
        db.tripDao().insert(
            TripEntity(
                id = id, status = status, name = null, isFavorite = false, motorcycleId = null, routeId = null, notes = null,
                createdAt = 1_000L, updatedAt = 1_000L, deletedAt = if (status == TripStatus.TRASHED) 1_000L else null
            )
        )
        if (!withPart) return
        val captureId = "cap-$id"
        db.tripCaptureDao().insert(
            TripCaptureEntity(
                id = captureId, status = CaptureStatus.COMPLETED, startedAt = 0L, endedAt = 1_000L,
                startElapsedRealtimeNanos = 0L, endElapsedRealtimeNanos = 1_000_000_000L, localTimeZoneId = "UTC",
                startSource = StartSource.MANUAL, endSource = null,
                detectorVersion = DetectorVersion(0), locationProfileVersion = LocationProfileVersion(0),
                createdAt = 0L, updatedAt = 0L
            )
        )
        db.tripPartDao().insert(
            TripPartEntity(
                id = "part-$id", tripId = id, captureId = captureId, orderIndex = 0,
                startElapsedRealtimeNanos = 0L, endElapsedRealtimeNanos = 1_000_000_000L,
                startSequenceNumber = null, endSequenceNumber = null
            )
        )
    }

    private suspend fun statistics(tripId: String, version: ProcessingVersion) {
        db.tripStatisticsDao().upsert(
            TripStatisticsEntity(
                tripId = tripId, processingVersion = version, computedAt = 1_000L,
                distanceM = 100.0, totalDurationMs = 1_000L, movingDurationMs = null, stoppedDurationMs = null,
                manualPauseDurationMs = 0L, maxSpeedMps = null, averageSpeedMps = null, averageMovingSpeedMps = null,
                minElevationM = null, maxElevationM = null, startElevationM = null, endElevationM = null,
                ascentM = null, descentM = null, validPointCount = 1, suspectPointCount = 0, rejectedPointCount = 0, gapCount = 0
            )
        )
    }

    @Test
    fun aVisibleTripWithNoStatisticsIsReEnqueuedWithItsFirstPartsCapture() = runTest {
        trip("lost-enqueue")

        val enqueued = reconciler.reconcile()

        assertEquals(1, enqueued)
        assertEquals(listOf(FakeProcessingScheduler.EnqueuedRequest("lost-enqueue", "cap-lost-enqueue")), scheduler.enqueuedRequests)
    }

    @Test
    fun aTripThatAlreadyHasCurrentVersionStatisticsIsLeftAlone() = runTest {
        trip("healthy")
        statistics("healthy", current)

        assertEquals(0, reconciler.reconcile())
        assertEquals(emptyList<FakeProcessingScheduler.EnqueuedRequest>(), scheduler.enqueuedRequests)
    }

    @Test
    fun aTripWithOnlyAnOlderVersionsStatisticsCountsAsMissingTheCurrentOne() = runTest {
        // ADR-014: versions coexist; a processingVersion bump must recompute, not trust the old numbers.
        trip("old-version")
        statistics("old-version", ProcessingVersion(current.value + 1))

        assertEquals(1, reconciler.reconcile())
    }

    @Test
    fun supersededAndTrashedTripsAreNeverReprocessedBecauseNothingShowsThem() = runTest {
        trip("superseded", TripStatus.SUPERSEDED)
        trip("trashed", TripStatus.TRASHED)

        assertEquals(0, reconciler.reconcile())
    }

    @Test
    fun aTripWithNoPartsIsSkippedRatherThanEnqueuedToFail() = runTest {
        trip("no-parts", withPart = false)

        assertEquals(0, reconciler.reconcile())
    }

    @Test
    fun onlyTheTripsThatNeedItAreEnqueuedWhenTheyAreMixed() = runTest {
        trip("needs-it")
        trip("fine")
        statistics("fine", current)
        trip("also-needs-it")

        reconciler.reconcile()

        assertEquals(setOf("needs-it", "also-needs-it"), scheduler.enqueuedRequests.map { it.tripId }.toSet())
    }
}
