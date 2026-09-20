package com.mototriptracker.app.feature.fieldtest

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.core.model.CapabilityMode
import com.mototriptracker.app.experiment.ExperimentLocationProfiles
import com.mototriptracker.app.experiment.FakeFieldTestDatasetWriter
import com.mototriptracker.app.experiment.FakeFieldTestDeviceInfoProvider
import com.mototriptracker.app.experiment.FakeFieldTestHarnessStateStore
import com.mototriptracker.app.experiment.FieldTestDeviceSnapshot
import com.mototriptracker.app.experiment.FieldTestSessionExporter
import com.mototriptracker.app.experiment.GroundTruthMarker
import com.mototriptracker.app.experiment.GroundTruthMarkerType
import com.mototriptracker.app.experiment.PersistedHarnessState
import com.mototriptracker.app.testing.FakeCapabilityInputsProvider
import com.mototriptracker.app.testing.FakeLocationGateway
import com.mototriptracker.app.testing.FakeProcessingScheduler
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import com.mototriptracker.app.tracking.location.InMemoryLocationProfileSelector
import com.mototriptracker.app.tracking.service.TrackingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FieldTestHarnessViewModelTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var writer: FakeFieldTestDatasetWriter
    private lateinit var capabilityInputsProvider: FakeCapabilityInputsProvider
    private lateinit var deviceInfoProvider: FakeFieldTestDeviceInfoProvider
    private lateinit var stateStore: FakeFieldTestHarnessStateStore
    private lateinit var locationProfileSelector: InMemoryLocationProfileSelector
    private lateinit var coordinator: TrackingSessionCoordinator
    private lateinit var viewModel: FieldTestHarnessViewModel
    private val clock = FakeClock(wallMillis = 100_000L, elapsedNanos = 100_000L)

    private val fullyGrantedInputs = CapabilityInputs(
        preciseLocationGranted = true,
        approximateLocationGranted = true,
        activityRecognitionGranted = true,
        backgroundLocationGranted = true,
        notificationsEnabled = true,
        locationServicesEnabled = true,
        autoTrackingEnabledByUser = true
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = TestDatabaseFactory.createInMemory()
        writer = FakeFieldTestDatasetWriter()
        capabilityInputsProvider = FakeCapabilityInputsProvider(fullyGrantedInputs)
        deviceInfoProvider = FakeFieldTestDeviceInfoProvider()
        stateStore = FakeFieldTestHarnessStateStore()
        locationProfileSelector = InMemoryLocationProfileSelector()
        coordinator = TrackingSessionCoordinator(
            database = db,
            tripCaptureDao = db.tripCaptureDao(),
            diagnosticEventDao = db.diagnosticEventDao(),
            rawTrackPointDao = db.rawTrackPointDao(),
            captureEventDao = db.captureEventDao(),
            tripDao = db.tripDao(),
            tripPartDao = db.tripPartDao(),
            manualPauseIntervalDao = db.manualPauseIntervalDao(),
            locationGateway = FakeLocationGateway(emptyList()),
            processingScheduler = FakeProcessingScheduler(),
            clock = clock,
            idGenerator = FakeIdGenerator(prefix = "capture")
        )
        viewModel = newViewModel()
    }

    private fun newViewModel() = FieldTestHarnessViewModel(
        context = ApplicationProvider.getApplicationContext(),
        deviceInfoProvider = deviceInfoProvider,
        capabilityInputsProvider = capabilityInputsProvider,
        exporter = FieldTestSessionExporter(writer),
        stateStore = stateStore,
        locationProfileSelector = locationProfileSelector,
        tripCaptureDao = db.tripCaptureDao(),
        trackingSessionCoordinator = coordinator,
        clock = clock,
        idGenerator = FakeIdGenerator(prefix = "session")
    )

    @After
    fun tearDown() {
        viewModel.onCleared()
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun initialStateIsConfiguringWithBlankFields() = runBlocking {
        val state = viewModel.uiState.value
        assertTrue(state is FieldTestHarnessUiState.Configuring)
        state as FieldTestHarnessUiState.Configuring
        assertEquals("", state.experimentProfileId)
        assertNull(state.lastExport)
    }

    @Test
    fun startSessionWithBlankProfileIdDoesNothing() = runBlocking {
        viewModel.startSession()
        assertTrue(viewModel.uiState.value is FieldTestHarnessUiState.Configuring)
    }

    @Test
    fun startSessionMovesToActiveAndResolvesCapabilityModeFromRealInputs() = runBlocking {
        viewModel.onProfileIdChanged("S1-A")
        viewModel.startSession()

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is FieldTestHarnessUiState.Active }
        } as FieldTestHarnessUiState.Active

        assertEquals("S1-A", state.experimentProfileId)
        assertEquals(CapabilityMode.FULL_AUTO, state.capabilityMode)
        assertNull("no capture was started, so there's nothing to show", state.activeCapture)
    }

    @Test
    fun activeCaptureInfoReflectsARealActiveCapture() = runBlocking {
        coordinator.startManualCapture()
        viewModel.onProfileIdChanged("S1-A")
        viewModel.startSession()

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is FieldTestHarnessUiState.Active && it.activeCapture != null }
        } as FieldTestHarnessUiState.Active

        val capture = requireNotNull(state.activeCapture)
        assertEquals(com.mototriptracker.app.core.model.StartSource.MANUAL, capture.startSource)
        assertTrue(!capture.isPaused)
    }

    @Test
    fun recordMarkerBeforeSessionStartedIsANoOp() = runBlocking {
        viewModel.recordMarker(GroundTruthMarkerType.ARRIVED)
        assertTrue(viewModel.uiState.value is FieldTestHarnessUiState.Configuring)
    }

    @Test
    fun recordMarkerIncrementsItsCountWhileActive() = runBlocking {
        viewModel.onProfileIdChanged("S1-A")
        viewModel.startSession()
        withTimeout(5_000) { viewModel.uiState.first { it is FieldTestHarnessUiState.Active } }

        viewModel.recordMarker(GroundTruthMarkerType.ARRIVED)
        viewModel.recordMarker(GroundTruthMarkerType.ARRIVED)
        viewModel.recordMarker(GroundTruthMarkerType.KNOWN_TUNNEL)

        val state = withTimeout(5_000) {
            viewModel.uiState.first {
                it is FieldTestHarnessUiState.Active && it.markerCounts[GroundTruthMarkerType.ARRIVED] == 2
            }
        } as FieldTestHarnessUiState.Active
        assertEquals(2, state.markerCounts[GroundTruthMarkerType.ARRIVED])
        assertEquals(1, state.markerCounts[GroundTruthMarkerType.KNOWN_TUNNEL])
    }

    @Test
    fun stopAndExportSessionWritesBothDatasetFilesAndReturnsToConfiguringWithASummary() = runBlocking {
        viewModel.onProfileIdChanged("S1-A")
        viewModel.startSession()
        withTimeout(5_000) { viewModel.uiState.first { it is FieldTestHarnessUiState.Active } }
        val sessionId = (viewModel.uiState.value as FieldTestHarnessUiState.Active).sessionId

        viewModel.recordMarker(GroundTruthMarkerType.READY_TO_START)
        viewModel.stopAndExportSession()

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is FieldTestHarnessUiState.Configuring && it.lastExport != null }
        } as FieldTestHarnessUiState.Configuring

        val lastExport = requireNotNull(state.lastExport)
        assertEquals(sessionId, lastExport.sessionId)
        assertEquals(1, lastExport.markerCount)

        val sessionJson = requireNotNull(writer.readFile(sessionId, "session.json")) { "session.json should have been written" }
        val parsed = JSONObject(sessionJson)
        assertEquals("S1-A", parsed.getString("experimentProfileId"))

        val annotationsJson = requireNotNull(writer.readFile(sessionId, "annotations.json")) { "annotations.json should have been written" }
        assertEquals(1, JSONObject(annotationsJson).getJSONArray("markers").length())
    }

    @Test
    fun startSessionPersistsStateSoAProcessKillWouldNotLoseIt() = runBlocking {
        viewModel.onProfileIdChanged("S1-A")
        viewModel.startSession()
        withTimeout(5_000) { viewModel.uiState.first { it is FieldTestHarnessUiState.Active } }

        val persisted = requireNotNull(stateStore.load()) { "starting a session should persist it immediately" }
        assertEquals("S1-A", persisted.experimentProfileId)
        assertTrue(persisted.markers.isEmpty())
    }

    @Test
    fun recordMarkerUpdatesThePersistedStateImmediately() = runBlocking {
        viewModel.onProfileIdChanged("S1-A")
        viewModel.startSession()
        withTimeout(5_000) { viewModel.uiState.first { it is FieldTestHarnessUiState.Active } }

        viewModel.recordMarker(GroundTruthMarkerType.ARRIVED)

        val persisted = requireNotNull(stateStore.load())
        assertEquals(1, persisted.markers.size)
        assertEquals(GroundTruthMarkerType.ARRIVED, persisted.markers.first().type)
    }

    @Test
    fun stopAndExportSessionClearsThePersistedState() = runBlocking {
        viewModel.onProfileIdChanged("S1-A")
        viewModel.startSession()
        withTimeout(5_000) { viewModel.uiState.first { it is FieldTestHarnessUiState.Active } }

        viewModel.stopAndExportSession()
        withTimeout(5_000) { viewModel.uiState.first { it is FieldTestHarnessUiState.Configuring && it.lastExport != null } }

        assertNull("a genuinely exported session shouldn't leave resumable state behind", stateStore.load())
    }

    @Test
    fun aNewViewModelRehydratesAnInProgressSessionAfterASimulatedProcessDeath() = runBlocking {
        stateStore.seed(
            PersistedHarnessState(
                sessionId = "session-before-kill",
                experimentProfileId = "S1-A",
                startedAtWallMs = 50_000L,
                startedAtElapsedNanos = 50_000L,
                phonePlacement = "PLACEMENT-MOUNTED",
                routeType = "urban",
                weatherNotes = "",
                notes = "",
                deviceSnapshot = FieldTestDeviceSnapshot(
                    appVersion = "test-version",
                    phoneManufacturer = "TestManufacturer",
                    phoneModel = "TestModel",
                    androidVersion = "16",
                    playServicesVersion = null,
                    batterySaverState = "disabled",
                    locationSettingsState = "enabled",
                    notificationPermissionState = "enabled",
                    screenStateAtStart = "on"
                ),
                capabilityInputsAtStart = fullyGrantedInputs,
                markers = listOf(GroundTruthMarker(GroundTruthMarkerType.READY_TO_START, 51_000L, 51_000L))
            )
        )

        // A fresh ViewModel instance, as Hilt would create after the process
        // restarts - this is deliberately NOT `viewModel` from setUp().
        val recreatedViewModel = newViewModel()

        val state = withTimeout(5_000) {
            recreatedViewModel.uiState.first { it is FieldTestHarnessUiState.Active }
        } as FieldTestHarnessUiState.Active

        assertEquals("session-before-kill", state.sessionId)
        assertEquals("S1-A", state.experimentProfileId)
        assertEquals(1, state.markerCounts[GroundTruthMarkerType.READY_TO_START])
        assertEquals(
            "the in-memory selector itself doesn't survive a process death - the ViewModel must re-arm it on rehydration",
            ExperimentLocationProfiles.S1_A,
            locationProfileSelector.current()
        )
        recreatedViewModel.onCleared()
    }

    @Test
    fun startSessionArmsTheRealLocationProfileMatchingTheTypedId() = runBlocking {
        viewModel.onProfileIdChanged("S1-A")
        viewModel.startSession()

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is FieldTestHarnessUiState.Active }
        } as FieldTestHarnessUiState.Active

        assertEquals(ExperimentLocationProfiles.S1_A, state.resolvedLocationProfile)
        assertEquals(ExperimentLocationProfiles.S1_A, locationProfileSelector.current())
    }

    @Test
    fun startSessionWithAnUnrecognizedProfileIdFallsBackToTheDefaultLocationProfile() = runBlocking {
        viewModel.onProfileIdChanged("not-a-real-profile")
        viewModel.startSession()

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is FieldTestHarnessUiState.Active }
        } as FieldTestHarnessUiState.Active

        assertEquals("not-a-real-profile", state.experimentProfileId)
        assertEquals(ExperimentLocationProfiles.DEFAULT, state.resolvedLocationProfile)
        assertEquals(ExperimentLocationProfiles.DEFAULT, locationProfileSelector.current())
    }

    @Test
    fun stopAndExportSessionResetsTheLocationProfileSelectorToDefault() = runBlocking {
        viewModel.onProfileIdChanged("S1-C")
        viewModel.startSession()
        withTimeout(5_000) { viewModel.uiState.first { it is FieldTestHarnessUiState.Active } }
        assertEquals(ExperimentLocationProfiles.S1_C, locationProfileSelector.current())

        viewModel.stopAndExportSession()
        withTimeout(5_000) { viewModel.uiState.first { it is FieldTestHarnessUiState.Configuring && it.lastExport != null } }

        assertEquals(
            "production tracking must never be left on an experimental profile after the field test ends",
            ExperimentLocationProfiles.DEFAULT,
            locationProfileSelector.current()
        )
    }

    @Test
    fun startSessionAlsoStartsTheRealTrackingServiceSoARiderOnlyNeedsOneButton() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()

        viewModel.onProfileIdChanged("S1-A")
        viewModel.startSession()

        val startedIntent = shadowOf(application).nextStartedService
        assertEquals(
            "the harness must start the real trip capture itself, not just its own diagnostic session",
            TrackingForegroundService.ACTION_START,
            startedIntent?.action
        )
    }

    @Test
    fun stopAndExportSessionAlsoFinishesTheRealTrackingService() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        viewModel.onProfileIdChanged("S1-A")
        viewModel.startSession()
        withTimeout(5_000) { viewModel.uiState.first { it is FieldTestHarnessUiState.Active } }
        shadowOf(application).nextStartedService // discard the ACTION_START intent from startSession() above

        viewModel.stopAndExportSession()

        val finishedIntent = shadowOf(application).nextStartedService
        assertEquals(
            "stopping the field-test session must also stop the real ride recording, not leave it running",
            TrackingForegroundService.ACTION_FINISH,
            finishedIntent?.action
        )
    }
}
