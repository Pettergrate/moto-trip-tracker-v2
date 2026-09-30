package com.mototriptracker.app.feature.active

import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.domain.GeoPoint
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
import androidx.lifecycle.viewModelScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MAP-003/`ADR-023`: [ActiveTripUiState.Active.routePoints] is the live route the map draws while recording - this
 * proves the wiring (DAO raw points -> `GeoPoint`, `ADR-022`'s approximate-fix exclusion), not the throttled-
 * simplification policy itself (`LiveRouteBuilderTest` owns that).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ActiveTripRouteTest {

    private val inputs = CapabilityInputs(
        preciseLocationGranted = true, approximateLocationGranted = true, activityRecognitionGranted = false,
        backgroundLocationGranted = false, notificationsEnabled = true, locationServicesEnabled = true, autoTrackingEnabledByUser = false
    )

    private lateinit var db: MotoTripDatabase
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
        viewModel = ActiveTripViewModel(
            context = ApplicationProvider.getApplicationContext(), tripCaptureDao = db.tripCaptureDao(),
            rawTrackPointDao = db.rawTrackPointDao(), manualPauseIntervalDao = db.manualPauseIntervalDao(),
            diagnosticEventDao = db.diagnosticEventDao(), motorcycleDao = db.motorcycleDao(),
            mapMarkerPreferences = TestMapMarkerPreferences.create(), persistenceHealthBus = PersistenceHealthBus(),
            capabilityInputsProvider = FakeCapabilityInputsProvider(inputs), clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 2_000_000_000L)
        )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.coroutineContext[Job]?.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun rawPoint(sequenceNumber: Long, latitude: Double, longitude: Double, isApproximateLocation: Boolean? = null) = RawTrackPointEntity(
        captureId = "cap", sequenceNumber = sequenceNumber, capturedAt = sequenceNumber, elapsedRealtimeNanos = sequenceNumber * 1_000_000_000L,
        receivedAtElapsedRealtimeNanos = sequenceNumber * 1_000_000_000L, latitude = latitude, longitude = longitude, horizontalAccuracyM = 5f,
        altitudeEllipsoidM = null, altitudeMslM = null, verticalAccuracyM = null, speedMps = null, speedAccuracyMps = null,
        bearingDeg = null, bearingAccuracyDeg = null, provider = "fused", isMock = false, requestProfileId = "test-profile",
        callbackBatchId = null, detectorStateSnapshot = "TRACKING", isApproximateLocation = isApproximateLocation
    )

    @Test
    fun routePointsReflectsTheRawPointsRecordedSoFar() = runBlocking {
        db.rawTrackPointDao().insert(rawPoint(0, 10.0, -20.0))
        db.rawTrackPointDao().insert(rawPoint(1, 10.001, -20.001))

        val active = withTimeout(5_000) { viewModel.uiState.first { it is ActiveTripUiState.Active && it.routePoints.size == 2 } } as ActiveTripUiState.Active

        assertEquals(listOf(GeoPoint(10.0, -20.0), GeoPoint(10.001, -20.001)), active.routePoints)
    }

    @Test
    fun anApproximateOnlyFixIsExcludedFromTheLiveRouteExactlyLikeLiveDistance() = runBlocking {
        db.rawTrackPointDao().insert(rawPoint(0, 10.0, -20.0))
        db.rawTrackPointDao().insert(rawPoint(1, 10.5, -20.5, isApproximateLocation = true))
        db.rawTrackPointDao().insert(rawPoint(2, 10.001, -20.001))

        val active = withTimeout(5_000) { viewModel.uiState.first { it is ActiveTripUiState.Active && it.routePoints.size == 2 } } as ActiveTripUiState.Active

        assertEquals(listOf(GeoPoint(10.0, -20.0), GeoPoint(10.001, -20.001)), active.routePoints)
        assertFalse("the ~2km-block approximate fix must never appear in the live route", active.routePoints.contains(GeoPoint(10.5, -20.5)))
    }

    @Test
    fun aSingleRawPointIsNotEnoughForARouteYet() = runBlocking {
        db.rawTrackPointDao().insert(rawPoint(0, 10.0, -20.0))

        val active = withTimeout(5_000) { viewModel.uiState.first { it is ActiveTripUiState.Active } } as ActiveTripUiState.Active

        assertEquals(1, active.routePoints.size)
    }
}
