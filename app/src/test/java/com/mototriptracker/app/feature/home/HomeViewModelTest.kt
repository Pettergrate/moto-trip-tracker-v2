package com.mototriptracker.app.feature.home

import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.TripEntity
import com.mototriptracker.app.core.database.entity.TripStatisticsEntity
import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.core.model.TripStatus
import com.mototriptracker.app.testing.FakeCapabilityInputsProvider
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
 * UI-001: regression coverage for a real bug found during on-device
 * verification. `recentTripsFlow` originally did a one-shot suspend read of
 * `trip_statistics` inside `mapLatest` over `tripDao.observeRecent(...)`.
 * Room only invalidates a query's Flow when a table IT reads from changes,
 * so that Flow never re-emitted when `TripProcessingWorker` wrote statistics
 * afterward - a freshly-finished trip showed distance/duration as unknown
 * forever, until some other Trip touched the `trip` table (confirmed live
 * on-device: the numbers only appeared after restarting the app). Fixed by
 * combining a Flow per trip's statistics instead of a suspend fetch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        // Room's own Flow queries run on its real query executor thread, not
        // on whatever TestDispatcher `Main` is set to - a StandardTestDispatcher
        // plus advanceUntilIdle() proved unable to observe that thread's
        // emissions (tried first; failed with an empty recentTrips list, since
        // advanceUntilIdle() only drives this dispatcher's own virtual queue).
        // Main only needs to exist for viewModelScope to resolve at all - the
        // test instead waits for real emissions via first{}/withTimeout below.
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = TestDatabaseFactory.createInMemory()
        viewModel = HomeViewModel(
            context = ApplicationProvider.getApplicationContext(),
            tripCaptureDao = db.tripCaptureDao(),
            rawTrackPointDao = db.rawTrackPointDao(),
            manualPauseIntervalDao = db.manualPauseIntervalDao(),
            tripDao = db.tripDao(),
            tripStatisticsDao = db.tripStatisticsDao(),
            capabilityInputsProvider = FakeCapabilityInputsProvider(
                CapabilityInputs(
                    preciseLocationGranted = false,
                    approximateLocationGranted = false,
                    activityRecognitionGranted = false,
                    backgroundLocationGranted = false,
                    notificationsEnabled = true,
                    locationServicesEnabled = false,
                    autoTrackingEnabledByUser = false
                )
            ),
            clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L)
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun recentTripsReflectsStatisticsInsertedAfterTheTripAlreadyExists() = runBlocking {
        db.tripDao().insert(
            TripEntity(
                id = "trip-1",
                status = TripStatus.COMPLETED,
                name = null,
                isFavorite = false,
                motorcycleId = null,
                routeId = null,
                notes = null,
                createdAt = 5_000L,
                updatedAt = 5_000L,
                deletedAt = null
            )
        )

        val beforeProcessing = withTimeout(5_000) {
            viewModel.uiState.first { it.recentTrips.isNotEmpty() }
        }.recentTrips.single()
        assertNull(
            "before processing finishes, the trip's stats are genuinely unknown, not zero",
            beforeProcessing.distanceMeters
        )

        db.tripStatisticsDao().upsert(
            TripStatisticsEntity(
                tripId = "trip-1",
                processingVersion = TripProcessingWorker.CURRENT_PROCESSING_VERSION,
                computedAt = 6_000L,
                distanceM = 1234.5,
                totalDurationMs = 60_000L,
                movingDurationMs = 50_000L,
                stoppedDurationMs = 10_000L,
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
        )

        val afterProcessing = withTimeout(5_000) {
            viewModel.uiState.first { it.recentTrips.singleOrNull()?.distanceMeters != null }
        }.recentTrips.single()
        assertEquals(1234.5, afterProcessing.distanceMeters!!, 0.0001)
        assertEquals(60_000L, afterProcessing.durationMs)
    }
}
