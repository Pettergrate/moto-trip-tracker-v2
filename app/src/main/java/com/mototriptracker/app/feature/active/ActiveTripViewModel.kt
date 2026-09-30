package com.mototriptracker.app.feature.active

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.dao.ManualPauseIntervalDao
import com.mototriptracker.app.core.database.dao.RawTrackPointDao
import com.mototriptracker.app.core.database.dao.TripCaptureDao
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.domain.GeoPoint
import com.mototriptracker.app.domain.LiveRouteState
import com.mototriptracker.app.domain.buildLiveRoute
import com.mototriptracker.app.domain.liveDistanceMeters
import com.mototriptracker.app.tracking.capability.CapabilityInputsProvider
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import com.mototriptracker.app.tracking.persistence.PersistenceHealthBus
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
    private val persistenceHealthBus: PersistenceHealthBus,
    private val capabilityInputsProvider: CapabilityInputsProvider,
    private val clock: Clock
) : ViewModel() {

    private val ticker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(1_000L)
        }
    }

    /** What the phone's settings say right now that the trip screen has to reflect (PERM-003). */
    private data class SettingsReading(val notificationsHidden: Boolean, val locationServicesOff: Boolean)

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
                    // combine has typed overloads only up to five flows: the recording-health flows travel together.
                    combine(
                        persistenceHealthBus.state,
                        settingsReading,
                        diagnosticEventDao.observeOpenGapReason(
                            capture.id,
                            TrackingSessionCoordinator.EVENT_LOCATION_ACCURACY_DEGRADED,
                            TrackingSessionCoordinator.EVENT_LOCATION_ACCURACY_RESTORED
                        )
                    ) { persistence, settings, accuracyReason -> Triple(persistence, settings, accuracyReason) }
                ) { points, openPause, openGapReason, _, (persistence, settings, accuracyReason) ->
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
                            approximateOnly = accuracyReason != null,
                            locationServicesOff = settings.locationServicesOff
                        ),
                        persistence = persistence,
                        notificationsHidden = settings.notificationsHidden,
                        routePoints = liveRoute.displayPoints
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
