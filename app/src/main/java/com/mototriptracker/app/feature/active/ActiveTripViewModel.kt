package com.mototriptracker.app.feature.active

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.database.dao.ManualPauseIntervalDao
import com.mototriptracker.app.core.database.dao.RawTrackPointDao
import com.mototriptracker.app.core.database.dao.TripCaptureDao
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.domain.liveDistanceMeters
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
    private val clock: Clock
) : ViewModel() {

    private val ticker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(1_000L)
        }
    }

    val uiState: StateFlow<ActiveTripUiState> = tripCaptureDao.observeByStatus(CaptureStatus.ACTIVE)
        .flatMapLatest { capture ->
            if (capture == null) {
                flowOf(ActiveTripUiState.NoActiveTrip)
            } else {
                combine(
                    rawTrackPointDao.observeAllByCapture(capture.id),
                    manualPauseIntervalDao.observeOpenByCapture(capture.id),
                    ticker
                ) { points, openPause, _ ->
                    ActiveTripUiState.Active(
                        isPaused = openPause != null,
                        distanceMeters = liveDistanceMeters(points),
                        elapsedMs = (clock.elapsedRealtimeNanos() - capture.startElapsedRealtimeNanos) / 1_000_000,
                        pauseElapsedMs = openPause?.let { (clock.elapsedRealtimeNanos() - it.startElapsedRealtimeNanos) / 1_000_000 }
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
    }
}
