package com.mototriptracker.app.feature.fieldtest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.database.dao.TripCaptureDao
import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.domain.capability.CapabilityResolver
import com.mototriptracker.app.experiment.FieldTestDeviceInfoProvider
import com.mototriptracker.app.experiment.FieldTestDeviceSnapshot
import com.mototriptracker.app.experiment.FieldTestSessionExporter
import com.mototriptracker.app.experiment.FieldTestSessionMetadata
import com.mototriptracker.app.experiment.GroundTruthMarker
import com.mototriptracker.app.experiment.GroundTruthMarkerLog
import com.mototriptracker.app.experiment.GroundTruthMarkerType
import com.mototriptracker.app.tracking.capability.CapabilityInputsProvider
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * EXP-002/F0.6 §5's minimum harness: start/stop an experimental session,
 * pick its `experimentProfileId`, show a live glance at detector-relevant
 * state, record F0.6 §7 ground-truth markers, and export via EXP-001's
 * `FieldTestSessionExporter`. "Show detector state" here means the F0.11
 * capability mode plus whatever `TrackingSessionCoordinator` already exposes
 * about an active capture (source/paused/elapsed/distance, the same figures
 * NOT-001's notification shows) - not a live view into DET-002/003's
 * internal candidate-start/stop state machine, which is private/pure by
 * design and already captured to `DiagnosticEvent` for post-hoc analysis
 * (F0.13); duplicating it here live would be new scope this task doesn't need.
 */
@HiltViewModel
class FieldTestHarnessViewModel @Inject constructor(
    private val deviceInfoProvider: FieldTestDeviceInfoProvider,
    private val capabilityInputsProvider: CapabilityInputsProvider,
    private val exporter: FieldTestSessionExporter,
    private val tripCaptureDao: TripCaptureDao,
    private val trackingSessionCoordinator: TrackingSessionCoordinator,
    private val clock: Clock,
    private val idGenerator: IdGenerator
) : ViewModel() {

    private val _uiState = MutableStateFlow<FieldTestHarnessUiState>(FieldTestHarnessUiState.Configuring())
    val uiState: StateFlow<FieldTestHarnessUiState> = _uiState.asStateFlow()

    private val markerLog = GroundTruthMarkerLog()
    private var activeSession: ActiveSession? = null
    private var tickerJob: Job? = null
    private var isStarting = false

    private data class ActiveSession(
        val sessionId: String,
        val experimentProfileId: String,
        val startedAtWallMs: Long,
        val startedAtElapsedNanos: Long,
        val phonePlacement: String,
        val routeType: String,
        val weatherNotes: String,
        val notes: String,
        val deviceSnapshot: FieldTestDeviceSnapshot,
        val capabilityInputsAtStart: CapabilityInputs
    )

    fun onProfileIdChanged(value: String) = updateConfiguring { it.copy(experimentProfileId = value) }
    fun onPhonePlacementChanged(value: String) = updateConfiguring { it.copy(phonePlacement = value) }
    fun onRouteTypeChanged(value: String) = updateConfiguring { it.copy(routeType = value) }
    fun onWeatherNotesChanged(value: String) = updateConfiguring { it.copy(weatherNotes = value) }
    fun onNotesChanged(value: String) = updateConfiguring { it.copy(notes = value) }

    private inline fun updateConfiguring(transform: (FieldTestHarnessUiState.Configuring) -> FieldTestHarnessUiState.Configuring) {
        _uiState.update { current -> if (current is FieldTestHarnessUiState.Configuring) transform(current) else current }
    }

    fun startSession() {
        val configuring = _uiState.value as? FieldTestHarnessUiState.Configuring ?: return
        if (configuring.experimentProfileId.isBlank() || activeSession != null || isStarting) return
        isStarting = true

        viewModelScope.launch {
            val capabilityInputs = capabilityInputsProvider.current()
            val session = ActiveSession(
                sessionId = idGenerator.newId(),
                experimentProfileId = configuring.experimentProfileId.trim(),
                startedAtWallMs = clock.wallClockMillis(),
                startedAtElapsedNanos = clock.elapsedRealtimeNanos(),
                phonePlacement = configuring.phonePlacement,
                routeType = configuring.routeType,
                weatherNotes = configuring.weatherNotes,
                notes = configuring.notes,
                deviceSnapshot = deviceInfoProvider.current(),
                capabilityInputsAtStart = capabilityInputs
            )
            markerLog.clear()
            activeSession = session
            isStarting = false
            startTicker()
            refreshActiveState()
        }
    }

    /** F0.6 §7: recorded while stopped - see `LIVE_GROUND_TRUTH_MARKER_TYPES` for the excluded GT_START/GT_END. */
    fun recordMarker(type: GroundTruthMarkerType) {
        if (activeSession == null) return
        markerLog.record(GroundTruthMarker(type, clock.wallClockMillis(), clock.elapsedRealtimeNanos()))
        viewModelScope.launch { refreshActiveState() }
    }

    fun stopAndExportSession() {
        val session = activeSession ?: return
        tickerJob?.cancel()

        viewModelScope.launch {
            val metadata = FieldTestSessionMetadata(
                sessionId = session.sessionId,
                startedAt = session.startedAtWallMs,
                endedAt = clock.wallClockMillis(),
                appVersion = session.deviceSnapshot.appVersion,
                diagnosticSchemaVersion = 1,
                experimentProfileId = session.experimentProfileId,
                detectorVersion = DetectorVersion(0),
                phoneManufacturer = session.deviceSnapshot.phoneManufacturer,
                phoneModel = session.deviceSnapshot.phoneModel,
                androidVersion = session.deviceSnapshot.androidVersion,
                playServicesVersion = session.deviceSnapshot.playServicesVersion,
                batterySaverState = session.deviceSnapshot.batterySaverState,
                locationSettingsState = session.deviceSnapshot.locationSettingsState,
                preciseLocationGranted = session.capabilityInputsAtStart.preciseLocationGranted,
                backgroundLocationGranted = session.capabilityInputsAtStart.backgroundLocationGranted,
                activityRecognitionGranted = session.capabilityInputsAtStart.activityRecognitionGranted,
                notificationPermissionState = session.deviceSnapshot.notificationPermissionState,
                phonePlacement = session.phonePlacement.ifBlank { "unspecified" },
                screenStateAtStart = session.deviceSnapshot.screenStateAtStart,
                routeType = session.routeType.ifBlank { "unspecified" },
                weatherNotes = session.weatherNotes.ifBlank { null },
                notes = session.notes.ifBlank { null }
            )
            val markers = markerLog.markers
            exporter.export(metadata, markers)

            activeSession = null
            _uiState.value = FieldTestHarnessUiState.Configuring(
                lastExport = ExportSummary(sessionId = session.sessionId, markerCount = markers.size)
            )
        }
    }

    private fun startTicker() {
        tickerJob = viewModelScope.launch {
            while (true) {
                refreshActiveState()
                delay(2_000)
            }
        }
    }

    private suspend fun refreshActiveState() {
        val session = activeSession ?: return
        val capabilityMode = CapabilityResolver.resolve(capabilityInputsProvider.current())
        val activeCapture = tripCaptureDao.findByStatus(CaptureStatus.ACTIVE)
        val activeCaptureInfo = activeCapture?.let { capture ->
            trackingSessionCoordinator.currentTrackingSnapshot(capture.id)?.let { snapshot ->
                ActiveCaptureInfo(
                    startSource = capture.startSource,
                    isPaused = snapshot.isPaused,
                    distanceMeters = snapshot.distanceMeters,
                    elapsedMs = snapshot.elapsedMs
                )
            }
        }

        // activeSession may have been cleared by stopAndExportSession while the
        // suspend calls above were in flight - don't resurrect Active state after a Stop.
        if (activeSession !== session) return

        _uiState.value = FieldTestHarnessUiState.Active(
            sessionId = session.sessionId,
            experimentProfileId = session.experimentProfileId,
            elapsedMs = (clock.elapsedRealtimeNanos() - session.startedAtElapsedNanos) / 1_000_000,
            capabilityMode = capabilityMode,
            activeCapture = activeCaptureInfo,
            markerCounts = markerLog.markers.groupingBy { it.type }.eachCount()
        )
    }

    public override fun onCleared() {
        tickerJob?.cancel()
    }
}
