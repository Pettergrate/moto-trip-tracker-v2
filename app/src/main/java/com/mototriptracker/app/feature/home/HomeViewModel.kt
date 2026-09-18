package com.mototriptracker.app.feature.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.database.dao.ManualPauseIntervalDao
import com.mototriptracker.app.core.database.dao.RawTrackPointDao
import com.mototriptracker.app.core.database.dao.TripCaptureDao
import com.mototriptracker.app.core.database.dao.TripDao
import com.mototriptracker.app.core.database.dao.TripStatisticsDao
import com.mototriptracker.app.core.model.CapabilityMode
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.domain.capability.CapabilityResolver
import com.mototriptracker.app.domain.liveDistanceMeters
import com.mototriptracker.app.feature.common.fallbackTripName
import com.mototriptracker.app.tracking.capability.CapabilityInputsProvider
import com.mototriptracker.app.tracking.processing.TripProcessingWorker
import com.mototriptracker.app.tracking.service.TrackingForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * F0.9 §5: HOME-01. Deliberately reads DAOs directly rather than through a
 * repository layer - no other class in this codebase (TrackingSessionCoordinator
 * included) uses one, and there's no concrete need yet (no multiple sources
 * to merge, no caching) that would justify introducing one just for the UI
 * layer.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tripCaptureDao: TripCaptureDao,
    private val rawTrackPointDao: RawTrackPointDao,
    private val manualPauseIntervalDao: ManualPauseIntervalDao,
    private val tripDao: TripDao,
    private val tripStatisticsDao: TripStatisticsDao,
    private val capabilityInputsProvider: CapabilityInputsProvider,
    private val clock: Clock
) : ViewModel() {

    /**
     * Ticks the active-trip summary forward once a second so duration stays
     * live even between location samples - the same "no separate timer
     * subsystem" reasoning `TrackingSessionCoordinator`'s own ticker used.
     */
    private val ticker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(1_000L)
        }
    }

    private val activeTripFlow: Flow<ActiveTripSummary?> =
        tripCaptureDao.observeByStatus(CaptureStatus.ACTIVE).flatMapLatest { capture ->
            if (capture == null) {
                flowOf(null)
            } else {
                combine(
                    rawTrackPointDao.observeAllByCapture(capture.id),
                    manualPauseIntervalDao.observeOpenByCapture(capture.id),
                    ticker
                ) { points, openPause, _ ->
                    ActiveTripSummary(
                        captureId = capture.id,
                        isPaused = openPause != null,
                        distanceMeters = liveDistanceMeters(points),
                        elapsedMs = (clock.elapsedRealtimeNanos() - capture.startElapsedRealtimeNanos) / 1_000_000
                    )
                }
            }
        }

    private val recentTripsFlow: Flow<List<RecentTripUi>> =
        tripDao.observeRecent(RECENT_TRIPS_LIMIT).flatMapLatest { trips ->
            if (trips.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    trips.map { trip ->
                        tripStatisticsDao.observeByTripAndVersion(trip.id, TripProcessingWorker.CURRENT_PROCESSING_VERSION)
                    }
                ) { statisticsByTrip ->
                    trips.mapIndexed { index, trip ->
                        val statistics = statisticsByTrip[index]
                        RecentTripUi(
                            tripId = trip.id,
                            displayName = trip.name ?: fallbackTripName(trip.createdAt),
                            distanceMeters = statistics?.distanceM,
                            durationMs = statistics?.totalDurationMs
                        )
                    }
                }
            }
        }

    /**
     * F0.9 §17: a permission revoked/granted outside the app is only
     * expected to be noticed "cuando el usuario abra la app" - no continuous
     * polling, just an explicit re-check [refreshCapabilityMode] triggers,
     * meant to be called from the screen's own resume lifecycle.
     */
    private val capabilityModeFlow = MutableStateFlow<CapabilityMode?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        capabilityModeFlow,
        activeTripFlow,
        recentTripsFlow
    ) { capabilityMode, activeTrip, recentTrips ->
        HomeUiState(capabilityMode = capabilityMode, activeTrip = activeTrip, recentTrips = recentTrips)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    init {
        refreshCapabilityMode()
    }

    fun refreshCapabilityMode() {
        viewModelScope.launch {
            capabilityModeFlow.value = CapabilityResolver.resolve(capabilityInputsProvider.current())
        }
    }

    fun onStartTripClick() {
        context.startForegroundService(TrackingForegroundService.createStartIntent(context))
    }

    fun onPauseClick() {
        context.startForegroundService(TrackingForegroundService.createPauseIntent(context))
    }

    fun onResumeClick() {
        context.startForegroundService(TrackingForegroundService.createResumeIntent(context))
    }

    companion object {
        private const val RECENT_TRIPS_LIMIT = 3
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
