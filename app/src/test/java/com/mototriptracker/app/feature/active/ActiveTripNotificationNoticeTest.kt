package com.mototriptracker.app.feature.active

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.testing.FakeCapabilityInputsProvider
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.testing.TestMapMarkerPreferences
import com.mototriptracker.app.tracking.persistence.PersistenceHealthBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PERM-003 / `privacy-permissions.md` §9: with notifications off the trip still records, but the rider is told that
 * Pause and Finish are not in the notification, and how to get them back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ActiveTripNotificationNoticeTest {

    private val inputs = CapabilityInputs(
        preciseLocationGranted = true, approximateLocationGranted = true, activityRecognitionGranted = false,
        backgroundLocationGranted = false, notificationsEnabled = true, locationServicesEnabled = true, autoTrackingEnabledByUser = false
    )

    private lateinit var db: MotoTripDatabase
    private lateinit var provider: FakeCapabilityInputsProvider
    private lateinit var viewModel: ActiveTripViewModel

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = TestDatabaseFactory.createInMemory()
        db.tripCaptureDao().insert(
            TripCaptureEntity(
                id = "cap", status = CaptureStatus.ACTIVE, startedAt = 1L, endedAt = null, startElapsedRealtimeNanos = 1L,
                endElapsedRealtimeNanos = null, localTimeZoneId = "UTC", startSource = StartSource.MANUAL, endSource = null,
                detectorVersion = DetectorVersion(0), locationProfileVersion = LocationProfileVersion(0), createdAt = 1L, updatedAt = 1L
            )
        )
        provider = FakeCapabilityInputsProvider(inputs)
        viewModel = ActiveTripViewModel(
            context = ApplicationProvider.getApplicationContext(), tripCaptureDao = db.tripCaptureDao(),
            rawTrackPointDao = db.rawTrackPointDao(), manualPauseIntervalDao = db.manualPauseIntervalDao(),
            diagnosticEventDao = db.diagnosticEventDao(), motorcycleDao = db.motorcycleDao(),
            mapMarkerPreferences = TestMapMarkerPreferences.create(), persistenceHealthBus = PersistenceHealthBus(),
            capabilityInputsProvider = provider, clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 2_000_000_000L)
        )
    }

    @After
    fun tearDown() {
        // This view model re-reads on timers (1 s ticker, notification poll). Left running, one wakes on Main after the
        // test ends and collides with the next test's setMain ("Dispatchers.Main is used concurrently with setting it").
        viewModel.viewModelScope.coroutineContext[Job]?.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun withNotificationsAllowedThereIsNoNotice() = runBlocking {
        val active = withTimeout(5_000) { viewModel.uiState.first { it is ActiveTripUiState.Active } } as ActiveTripUiState.Active

        assertFalse(active.notificationsHidden)
    }

    @Test
    fun withNotificationsOffTheRiderIsToldTheTripControlsAreNotInTheNotification() = runBlocking {
        provider.set(inputs.copy(notificationsEnabled = false))

        val active = withTimeout(8_000) {
            viewModel.uiState.first { it is ActiveTripUiState.Active && it.notificationsHidden }
        } as ActiveTripUiState.Active

        assertTrue(active.notificationsHidden)
    }

    /** The same 3 s read that finds hidden notifications also finds Location switched off before the first fix. */
    @Test
    fun withLocationOffAndNoPointsYetTheRiderIsToldToTurnItOnNotToWait() = runBlocking {
        provider.set(inputs.copy(locationServicesEnabled = false))

        val active = withTimeout(8_000) {
            viewModel.uiState.first { it is ActiveTripUiState.Active && it.signal == ActiveTripSignal.SEARCHING_LOCATION_OFF }
        } as ActiveTripUiState.Active

        assertFalse("notifications are fine, only Location is off", active.notificationsHidden)
    }

    @Test
    fun withLocationOnTheWaitIsTheOrdinaryOneForTheFirstFix() = runBlocking {
        val active = withTimeout(5_000) { viewModel.uiState.first { it is ActiveTripUiState.Active } } as ActiveTripUiState.Active

        assertTrue(active.signal == ActiveTripSignal.SEARCHING)
    }
}
