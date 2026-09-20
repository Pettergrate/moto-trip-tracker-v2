package com.mototriptracker.app.feature.fieldtest

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.core.model.CapabilityMode
import com.mototriptracker.app.experiment.FakeFieldTestDatasetWriter
import com.mototriptracker.app.experiment.FakeFieldTestDeviceInfoProvider
import com.mototriptracker.app.experiment.FieldTestSessionExporter
import com.mototriptracker.app.experiment.GroundTruthMarkerType
import com.mototriptracker.app.testing.FakeCapabilityInputsProvider
import com.mototriptracker.app.testing.FakeLocationGateway
import com.mototriptracker.app.testing.FakeProcessingScheduler
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FieldTestHarnessViewModelTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var writer: FakeFieldTestDatasetWriter
    private lateinit var capabilityInputsProvider: FakeCapabilityInputsProvider
    private lateinit var deviceInfoProvider: FakeFieldTestDeviceInfoProvider
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
        viewModel = FieldTestHarnessViewModel(
            deviceInfoProvider = deviceInfoProvider,
            capabilityInputsProvider = capabilityInputsProvider,
            exporter = FieldTestSessionExporter(writer),
            tripCaptureDao = db.tripCaptureDao(),
            trackingSessionCoordinator = coordinator,
            clock = clock,
            idGenerator = FakeIdGenerator(prefix = "session")
        )
    }

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
        viewModel.onProfileIdChanged("S1-interval-1s")
        viewModel.startSession()

        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is FieldTestHarnessUiState.Active }
        } as FieldTestHarnessUiState.Active

        assertEquals("S1-interval-1s", state.experimentProfileId)
        assertEquals(CapabilityMode.FULL_AUTO, state.capabilityMode)
        assertNull("no capture was started, so there's nothing to show", state.activeCapture)
    }

    @Test
    fun activeCaptureInfoReflectsARealActiveCapture() = runBlocking {
        coordinator.startManualCapture()
        viewModel.onProfileIdChanged("S1-interval-1s")
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
        viewModel.onProfileIdChanged("S1-interval-1s")
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
        viewModel.onProfileIdChanged("S1-interval-1s")
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
        assertEquals("S1-interval-1s", parsed.getString("experimentProfileId"))

        val annotationsJson = requireNotNull(writer.readFile(sessionId, "annotations.json")) { "annotations.json should have been written" }
        assertEquals(1, JSONObject(annotationsJson).getJSONArray("markers").length())
    }
}
