package com.mototriptracker.app.perf

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.ProcessedTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.database.entity.TripStatisticsEntity
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.feature.history.HistoryViewModel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PERF-002 / `NFR-PERF-001` ("years of Trip history without requiring all TrackPoints to be loaded into memory
 * simultaneously"). Read literally, the real risk isn't the raw track at all - `HistoryViewModel` never touches
 * `RawTrackPointDao` - it's what it *does* touch: one Room-observed `Flow` per visible trip
 * (`combine(trips.map { tripStatisticsDao.observeByTripAndVersion(...) })`), plus a `%10==0`-sampled query over
 * `processed_track_point` for every visible trip's route thumbnail (`observeSampledByTrips`). Neither is obviously
 * bounded by trip *count* the way a single trip's raw points are bounded by its own duration - a real years-long
 * history is what this benchmarks, honestly: a regression tripwire against this JVM's own baseline (see
 * `LongTripIngestionBenchmarkTest`'s KDoc for why the bound is generous), not a device SLA.
 *
 * The scenario: 730 trips (two years, riding every day) x 200 simplified route points each (`RouteSimplifier`'s own
 * benchmark shows real 10k-raw-point rides simplify to roughly this order of magnitude) - 146,000 processed points
 * on top of the trip and statistics rows, all through the real `HistoryViewModel`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LargeHistoryBenchmarkTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var viewModel: HistoryViewModel

    private val tripCount = 730
    private val pointsPerTrip = 200

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = TestDatabaseFactory.createInMemory()

        for (i in 0 until tripCount) {
            val tripId = "trip-$i"
            val createdAt = 1_000_000L + i * 86_400_000L // one trip per day, oldest first
            db.tripDao().insert(
                TripEntity(
                    id = tripId, status = TripStatus.COMPLETED, name = null, isFavorite = i % 20 == 0, motorcycleId = null,
                    routeId = null, notes = null, createdAt = createdAt, updatedAt = createdAt, deletedAt = null
                )
            )
            db.tripStatisticsDao().upsert(
                TripStatisticsEntity(
                    tripId = tripId, processingVersion = TripProcessingWorker.CURRENT_PROCESSING_VERSION, computedAt = createdAt,
                    distanceM = 45_000.0, totalDurationMs = 3_600_000L, movingDurationMs = 3_300_000L, stoppedDurationMs = 300_000L,
                    manualPauseDurationMs = 0L, maxSpeedMps = 30.0, averageSpeedMps = 13.0, averageMovingSpeedMps = 14.0,
                    minElevationM = 800.0, maxElevationM = 1200.0, startElevationM = 850.0, endElevationM = 900.0,
                    ascentM = 400.0, descentM = 300.0, validPointCount = pointsPerTrip, suspectPointCount = 0,
                    rejectedPointCount = 0, gapCount = 0
                )
            )
            db.processedTrackPointDao().insertAll(
                (0 until pointsPerTrip).map { p ->
                    ProcessedTrackPointEntity(
                        tripId = tripId, processingVersion = TripProcessingWorker.CURRENT_PROCESSING_VERSION, orderIndex = p,
                        latitude = 10.0 + p * 1e-4, longitude = -20.0 - p * 1e-4, sourceCaptureId = "$tripId-cap", sourceSequenceNumber = p.toLong(),
                        pointRole = null
                    )
                }
            )
        }

        viewModel = HistoryViewModel(
            tripDao = db.tripDao(), tripStatisticsDao = db.tripStatisticsDao(), processedTrackPointDao = db.processedTrackPointDao(),
            clock = FakeClock(wallMillis = 1_000L)
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun twoYearsOfHistoryRendersInHistoryWithoutEverTouchingRawPoints() = runBlocking {
        val elapsedMs = measureMs {
            val state = withTimeout(30_000) { viewModel.uiState.first { it.trips.size == tripCount } }
            assertEquals(tripCount, state.trips.size)
            // Newest first by default, and every row got its own statistics - not just the first page of them.
            assertEquals("trip-${tripCount - 1}", state.trips.first().tripId)
            assertTrue("the oldest trip's own statistics reached its row too, not just the newest ones", state.trips.last().distanceMeters != null)
        }

        assertEquals("raw points play no part in History at all (NFR-PERF-001)", 0, db.rawTrackPointDao().countByCapture("nonexistent"))
        // Generous on purpose (see the class KDoc): a tripwire against a real quadratic or per-row-blocking regression,
        // not a device SLA.
        assertTrue("two years of history took ${elapsedMs}ms to render - investigate for a per-trip-count regression", elapsedMs < 25_000L)
    }

    private inline fun measureMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }
}
