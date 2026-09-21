package com.mototriptracker.app.feature.fieldtest

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.common.Clock
import com.mototriptracker.app.core.common.IdGenerator
import com.mototriptracker.app.core.database.dao.RawTrackPointDao
import com.mototriptracker.app.core.database.dao.TripCaptureDao
import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.domain.capability.CapabilityResolver
import com.mototriptracker.app.experiment.ExperimentLocationProfiles
import com.mototriptracker.app.experiment.FieldTestDeviceInfoProvider
import com.mototriptracker.app.experiment.FieldTestDeviceSnapshot
import com.mototriptracker.app.experiment.FieldTestHarnessStateStore
import com.mototriptracker.app.experiment.FieldTestSessionExporter
import com.mototriptracker.app.experiment.FieldTestSessionMetadata
import com.mototriptracker.app.experiment.GroundTruthMarker
import com.mototriptracker.app.experiment.GroundTruthMarkerLog
import com.mototriptracker.app.experiment.GroundTruthMarkerType
import com.mototriptracker.app.experiment.PersistedHarnessState
import com.mototriptracker.app.tracking.capability.CapabilityInputsProvider
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import com.mototriptracker.app.tracking.location.LocationProfileSelector
import com.mototriptracker.app.tracking.service.TrackingForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
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
 *
 * A real ride can run for a long time with the screen off, long enough for
 * Android to kill this process in the background - [stateStore] persists
 * the in-progress session/markers to a small file so [init] can rebuild
 * `Active` state after a real process death, instead of silently losing an
 * entire field-test session before it was ever exported.
 *
 * EXP-003: [startSession] also arms [locationProfileSelector] with the real
 * `ExperimentLocationProfiles` entry matching the typed `experimentProfileId`
 * (or resets to the default if it doesn't match a known one), so
 * `FusedLocationGateway`'s actual GPS request reflects what the tester
 * picked - before this, the field was a label with nothing behind it.
 * [stopAndExportSession] always resets it back, and [init]'s resumability
 * path re-arms it too, since the in-memory selector itself doesn't survive
 * the same process death this ViewModel's own state does.
 *
 * [startSession]/[stopAndExportSession] also start/finish a real Trip
 * capture, the same way Home's own START TRIP button does
 * (`TrackingForegroundService.createStartIntent`/`createFinishIntent`) -
 * a field-test session's whole reason to exist is riding a real trip, so
 * this used to be two separate steps a tester had to remember (harness
 * "Start session", then Home "START TRIP") until a real ride was recorded
 * without one of them. One button now does both; a rider fumbling with two
 * separate screens before pulling away was never reasonable to ask for.
 *
 * [refreshActiveState] also remembers the real capture's id the first time
 * it observes one (`ActiveSession.associatedCaptureId`) so
 * [stopAndExportSession] can pull that capture's `RawTrackPointEntity` rows
 * and export a real `raw-track.csv` (F0.6 §20) - EXP-001 deferred this file
 * entirely since no capture correlated with a harness session existed yet.
 */
@HiltViewModel
class FieldTestHarnessViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceInfoProvider: FieldTestDeviceInfoProvider,
    private val capabilityInputsProvider: CapabilityInputsProvider,
    private val exporter: FieldTestSessionExporter,
    private val stateStore: FieldTestHarnessStateStore,
    private val locationProfileSelector: LocationProfileSelector,
    private val tripCaptureDao: TripCaptureDao,
    private val rawTrackPointDao: RawTrackPointDao,
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
        val capabilityInputsAtStart: CapabilityInputs,
        val associatedCaptureId: String? = null
    )

    init {
        stateStore.load()?.let { persisted ->
            activeSession = ActiveSession(
                sessionId = persisted.sessionId,
                experimentProfileId = persisted.experimentProfileId,
                startedAtWallMs = persisted.startedAtWallMs,
                startedAtElapsedNanos = persisted.startedAtElapsedNanos,
                phonePlacement = persisted.phonePlacement,
                routeType = persisted.routeType,
                weatherNotes = persisted.weatherNotes,
                notes = persisted.notes,
                deviceSnapshot = persisted.deviceSnapshot,
                capabilityInputsAtStart = persisted.capabilityInputsAtStart,
                associatedCaptureId = persisted.associatedCaptureId
            )
            persisted.markers.forEach(markerLog::record)
            locationProfileSelector.select(ExperimentLocationProfiles.findById(persisted.experimentProfileId))
            startTicker()
            viewModelScope.launch { refreshActiveState() }
        }
    }

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
            persistActiveSession()
            // Order matters: the profile must be armed before the service
            // actually starts collecting locations, or the ride would begin
            // under whatever was previously selected (the default).
            locationProfileSelector.select(ExperimentLocationProfiles.findById(session.experimentProfileId))
            context.startForegroundService(TrackingForegroundService.createStartIntent(context))
            isStarting = false
            startTicker()
            refreshActiveState()
        }
    }

    /**
     * F0.6 §7: recorded while stopped - see `LIVE_GROUND_TRUTH_MARKER_TYPES`
     * for the excluded GT_START/GT_END. Deliberately synchronous, no DB
     * re-query: a marker tap only ever changes [markerLog], never the
     * capability mode or active-capture figures the ticker already keeps
     * fresh - re-querying Room on every tap would be both unnecessary and,
     * fired-and-forgotten from a UI callback, race-prone (a real one showed
     * up as a flaky "uncaught exception" in an unrelated test whose only
     * connection was running afterward in the same JVM).
     */
    fun recordMarker(type: GroundTruthMarkerType) {
        if (activeSession == null) return
        markerLog.record(GroundTruthMarker(type, clock.wallClockMillis(), clock.elapsedRealtimeNanos()))
        persistActiveSession()
        _uiState.update { current ->
            if (current is FieldTestHarnessUiState.Active) {
                current.copy(markerCounts = markerLog.markers.groupingBy { it.type }.eachCount())
            } else {
                current
            }
        }
    }

    fun stopAndExportSession() {
        val session = activeSession ?: return
        tickerJob?.cancel()
        context.startForegroundService(TrackingForegroundService.createFinishIntent(context))

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
            // null when the real capture was never observed by refreshActiveState()
            // in time (e.g. a start-then-almost-immediately-stop sanity check) -
            // exported as an honest empty raw-track.csv rather than skipped.
            val rawTrackPoints = session.associatedCaptureId?.let { rawTrackPointDao.findAllByCapture(it) } ?: emptyList()
            exporter.export(metadata, markers, rawTrackPoints)
            stateStore.clear()
            locationProfileSelector.select(null)

            activeSession = null
            _uiState.value = FieldTestHarnessUiState.Configuring(
                lastExport = ExportSummary(sessionId = session.sessionId, markerCount = markers.size)
            )
        }
    }

    private fun persistActiveSession() {
        val session = activeSession ?: return
        stateStore.save(
            PersistedHarnessState(
                sessionId = session.sessionId,
                experimentProfileId = session.experimentProfileId,
                startedAtWallMs = session.startedAtWallMs,
                startedAtElapsedNanos = session.startedAtElapsedNanos,
                phonePlacement = session.phonePlacement,
                routeType = session.routeType,
                weatherNotes = session.weatherNotes,
                notes = session.notes,
                deviceSnapshot = session.deviceSnapshot,
                capabilityInputsAtStart = session.capabilityInputsAtStart,
                markers = markerLog.markers,
                associatedCaptureId = session.associatedCaptureId
            )
        )
    }

    /** `delay` first, not last: the caller always does its own immediate [refreshActiveState] right after calling this - refreshing here too would be a redundant concurrent DB query racing that one. */
    private fun startTicker() {
        tickerJob = viewModelScope.launch {
            while (true) {
                delay(2_000)
                refreshActiveState()
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

        // Remembered once discovered, not re-queried every tick: by export
        // time the real capture has already finished (and stopped being
        // ACTIVE), so this is the only way stopAndExportSession() later knows
        // which capture's raw-track.csv rows belong to this session.
        if (activeCapture != null && session.associatedCaptureId == null) {
            activeSession = session.copy(associatedCaptureId = activeCapture.id)
            persistActiveSession()
        }

        // Only reachable without a real reboot (elapsedRealtimeNanos is monotonic
        // within a boot) - a genuine reboot mid-ride is REC-001's territory, not
        // this internal tool's; this just keeps the ticker from showing a
        // nonsensical negative duration in that edge case.
        val elapsedMs = ((clock.elapsedRealtimeNanos() - session.startedAtElapsedNanos) / 1_000_000).coerceAtLeast(0L)

        _uiState.value = FieldTestHarnessUiState.Active(
            sessionId = session.sessionId,
            experimentProfileId = session.experimentProfileId,
            elapsedMs = elapsedMs,
            capabilityMode = capabilityMode,
            activeCapture = activeCaptureInfo,
            markerCounts = markerLog.markers.groupingBy { it.type }.eachCount(),
            resolvedLocationProfile = locationProfileSelector.current()
        )
    }

    public override fun onCleared() {
        // Cancels the ticker AND any one-off `viewModelScope.launch { refreshActiveState() }`
        // fired by recordMarker()/startSession() - tracking `tickerJob` alone left those
        // stray, which under a test's real Room dispatch could still be touching the
        // database after the test's own teardown closed it (an uncaught exception that
        // then got misattributed to whatever test ran next).
        viewModelScope.coroutineContext.cancelChildren()
    }
}
