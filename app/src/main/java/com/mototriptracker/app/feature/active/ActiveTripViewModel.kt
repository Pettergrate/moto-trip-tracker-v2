package com.mototriptracker.app.feature.active

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.dao.ManualPauseIntervalDao
import com.mototriptracker.app.core.database.dao.MotorcycleDao
import com.mototriptracker.app.core.database.dao.RawTrackPointDao
import com.mototriptracker.app.core.database.dao.TripCaptureDao
import com.mototriptracker.app.core.datastore.MapMarkerPreferences
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.VehicleType
import com.mototriptracker.app.domain.GeoPoint
import com.mototriptracker.app.domain.LiveRouteState
import com.mototriptracker.app.domain.buildLiveRoute
import com.mototriptracker.app.domain.liveDistanceMeters
import com.mototriptracker.app.tracking.capability.CapabilityInputsProvider
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import com.mototriptracker.app.tracking.persistence.PersistenceHealthBus
import com.mototriptracker.app.tracking.persistence.PersistenceState
import com.mototriptracker.app.tracking.service.TrackingForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** F0.9 §6: TRP-01. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ActiveTripViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tripCaptureDao: TripCaptureDao,
    private val rawTrackPointDao: RawTrackPointDao,
    private val manualPauseIntervalDao: ManualPauseIntervalDao,
    private val diagnosticEventDao: DiagnosticEventDao,
    private val motorcycleDao: MotorcycleDao,
    private val mapMarkerPreferences: MapMarkerPreferences,
    private val persistenceHealthBus: PersistenceHealthBus,
    private val capabilityInputsProvider: CapabilityInputsProvider,
    private val clock: Clock
) : ViewModel() {

    /**
     * MAP-006/`ADR-024`: no `TripEntity` exists yet for an in-progress capture, so there is nothing to look up a
     * motorcycle's own vehicle type *through* - this reads the "currently selected" motorcycle preference directly
     * instead, purely for the live map's icon. Independent of which capture is active, so it lives outside the
     * per-capture `flatMapLatest` below.
     */
    private val liveVehicleType: Flow<VehicleType> = mapMarkerPreferences.selectedMotorcycleId.flatMapLatest { motorcycleId ->
        if (motorcycleId == null) {
            mapMarkerPreferences.defaultVehicleType
        } else {
            motorcycleDao.observeById(motorcycleId).flatMapLatest { it?.vehicleType?.let { type -> flowOf(type) } ?: mapMarkerPreferences.defaultVehicleType }
        }
    }

    private val ticker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(1_000L)
        }
    }

    /** What the phone's settings say right now that the trip screen has to reflect (PERM-003). */
    private data class SettingsReading(val notificationsHidden: Boolean, val locationServicesOff: Boolean)

    /** combine's typed overloads top out at 5 flows - the recording-health signals (plus MAP-006's live icon) travel bundled together. */
    private data class HealthBundle(
        val persistence: PersistenceState,
        val settings: SettingsReading,
        val accuracyReason: String?,
        val vehicleType: VehicleType
    )

    /**
     * PERM-003: whether the trip notification (with Pause and Finish) can be shown, and whether Location Services are
     * off. Re-read every few seconds, not every second: both change only when the person edits the phone's settings.
     * A failed read shows nothing rather than a false alarm.
     */
    private val settingsReading: Flow<SettingsReading> = flow {
        while (true) {
            emit(
                runCatching {
                    val inputs = capabilityInputsProvider.current()
                    SettingsReading(notificationsHidden = !inputs.notificationsEnabled, locationServicesOff = !inputs.locationServicesEnabled)
                }.getOrDefault(SettingsReading(notificationsHidden = false, locationServicesOff = false))
            )
            delay(NOTIFICATION_CHECK_MS)
        }
    }

    val uiState: StateFlow<ActiveTripUiState> = tripCaptureDao.observeByStatus(CaptureStatus.ACTIVE)
        .flatMapLatest { capture ->
            if (capture == null) {
                flowOf(ActiveTripUiState.NoActiveTrip)
            } else {
                // Local to this capture's own flatMapLatest invocation - a fresh capture (or losing/regaining one)
                // gets a fresh LiveRouteState, never carrying over a previous capture's simplification progress.
                var liveRouteState = LiveRouteState()
                combine(
                    rawTrackPointDao.observeAllByCapture(capture.id),
                    manualPauseIntervalDao.observeOpenByCapture(capture.id),
                    diagnosticEventDao.observeOpenGapReason(
                        capture.id,
                        TrackingSessionCoordinator.EVENT_LOCATION_GAP_STARTED,
                        TrackingSessionCoordinator.EVENT_LOCATION_GAP_ENDED
                    ),
                    ticker,
                    // combine has typed overloads only up to five flows: the recording-health flows (plus the live
                    // vehicle icon, MAP-006) travel together.
                    combine(
                        persistenceHealthBus.state,
                        settingsReading,
                        diagnosticEventDao.observeOpenGapReason(
                            capture.id,
                            TrackingSessionCoordinator.EVENT_LOCATION_ACCURACY_DEGRADED,
                            TrackingSessionCoordinator.EVENT_LOCATION_ACCURACY_RESTORED
                        ),
                        liveVehicleType
                    ) { persistence, settings, accuracyReason, vehicleType -> HealthBundle(persistence, settings, accuracyReason, vehicleType) }
                ) { points, openPause, openGapReason, _, health ->
                    // ADR-022: an approximate-only fix is excluded from the live route, same as liveDistanceMeters.
                    val geoPoints = points.filter { it.isApproximateLocation != true }.map { GeoPoint(it.latitude, it.longitude) }
                    val liveRoute = buildLiveRoute(liveRouteState, geoPoints)
                    liveRouteState = liveRoute.nextState
                    ActiveTripUiState.Active(
                        isPaused = openPause != null,
                        distanceMeters = liveDistanceMeters(points),
                        elapsedMs = (clock.elapsedRealtimeNanos() - capture.startElapsedRealtimeNanos) / 1_000_000,
                        pauseElapsedMs = openPause?.let { (clock.elapsedRealtimeNanos() - it.startElapsedRealtimeNanos) / 1_000_000 },
                        signal = activeTripSignal(
                            isPaused = openPause != null,
                            pointCount = points.size,
                            openGapReason = openGapReason,
                            approximateOnly = health.accuracyReason != null,
                            locationServicesOff = health.settings.locationServicesOff
                        ),
                        persistence = health.persistence,
                        notificationsHidden = health.settings.notificationsHidden,
                        routePoints = liveRoute.displayPoints,
                        vehicleType = health.vehicleType
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ActiveTripUiState.Loading)

    fun onPauseClick() {
        context.startForegroundService(TrackingForegroundService.createPauseIntent(context))
    }

    fun onResumeClick() {
        context.startForegroundService(TrackingForegroundService.createResumeIntent(context))
    }

    /** F0.9 §6.4: the confirmation dialog itself lives in the screen - this is only called once the user has already confirmed. */
    fun onFinishConfirmed() {
        context.startForegroundService(TrackingForegroundService.createFinishIntent(context))
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val NOTIFICATION_CHECK_MS = 3_000L
    }
}
