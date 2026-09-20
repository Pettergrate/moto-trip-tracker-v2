package com.mototriptracker.app.feature.fieldtest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mototriptracker.app.core.model.CapabilityMode
import com.mototriptracker.app.experiment.GroundTruthMarkerType
import com.mototriptracker.app.feature.common.formatDistanceKm
import com.mototriptracker.app.feature.common.formatDurationClock

/** EXP-002/F0.6 §5: not a bottom-nav destination (EXP-001 acceptance: "without becoming user-facing Core UX") - reached from Settings only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldTestHarnessScreen(
    onBack: () -> Unit,
    viewModel: FieldTestHarnessViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Field test harness") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        when (val state = uiState) {
            is FieldTestHarnessUiState.Configuring -> ConfiguringContent(
                modifier = Modifier.padding(innerPadding),
                state = state,
                onProfileIdChanged = viewModel::onProfileIdChanged,
                onPhonePlacementChanged = viewModel::onPhonePlacementChanged,
                onRouteTypeChanged = viewModel::onRouteTypeChanged,
                onWeatherNotesChanged = viewModel::onWeatherNotesChanged,
                onNotesChanged = viewModel::onNotesChanged,
                onStart = viewModel::startSession
            )

            is FieldTestHarnessUiState.Active -> ActiveContent(
                modifier = Modifier.padding(innerPadding),
                state = state,
                onRecordMarker = viewModel::recordMarker,
                onStop = viewModel::stopAndExportSession
            )
        }
    }
}

@Composable
private fun ConfiguringContent(
    modifier: Modifier,
    state: FieldTestHarnessUiState.Configuring,
    onProfileIdChanged: (String) -> Unit,
    onPhonePlacementChanged: (String) -> Unit,
    onRouteTypeChanged: (String) -> Unit,
    onWeatherNotesChanged: (String) -> Unit,
    onNotesChanged: (String) -> Unit,
    onStart: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.lastExport != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Last session exported", style = MaterialTheme.typography.titleSmall)
                        Text("Session ${state.lastExport.sessionId} - ${state.lastExport.markerCount} marker(s)")
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = state.experimentProfileId,
                onValueChange = onProfileIdChanged,
                label = { Text("Experiment profile ID (tracking-manual-v0, S1-A, S1-B or S1-C)") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = state.phonePlacement,
                onValueChange = onPhonePlacementChanged,
                label = { Text("Phone placement") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = state.routeType,
                onValueChange = onRouteTypeChanged,
                label = { Text("Route type") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = state.weatherNotes,
                onValueChange = onWeatherNotesChanged,
                label = { Text("Weather notes (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = state.notes,
                onValueChange = onNotesChanged,
                label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Button(
                onClick = onStart,
                enabled = state.experimentProfileId.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start session")
            }
        }
    }
}

@Composable
private fun ActiveContent(
    modifier: Modifier,
    state: FieldTestHarnessUiState.Active,
    onRecordMarker: (GroundTruthMarkerType) -> Unit,
    onStop: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Session ${state.sessionId}", style = MaterialTheme.typography.titleSmall)
                    Text("Profile: ${state.experimentProfileId}")
                    Text("Elapsed: ${formatDurationClock(state.elapsedMs)}")
                    val profile = state.resolvedLocationProfile
                    Text("GPS: ${profile.id} - ${profile.intervalMillis}ms interval, ${profile.minUpdateDistanceMeters}m min-distance")
                    if (profile.id != state.experimentProfileId) {
                        Text(
                            "\"${state.experimentProfileId}\" isn't a known profile - using the default GPS config instead",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Detector state", style = MaterialTheme.typography.titleSmall)
                    Text("Capability mode: ${state.capabilityMode.label()}")
                    val capture = state.activeCapture
                    if (capture == null) {
                        Text("No active capture")
                    } else {
                        Text("Capture: ${capture.startSource} ${if (capture.isPaused) "(paused)" else "(recording)"}")
                        Text("Distance so far: ${formatDistanceKm(capture.distanceMeters)}")
                        Text("Capture elapsed: ${formatDurationClock(capture.elapsedMs)}")
                    }
                }
            }
        }
        item {
            Text("Ground-truth markers (record while stopped)", style = MaterialTheme.typography.titleSmall)
        }
        // A plain chunked Column/Row grid, not LazyVerticalGrid: the marker
        // vocabulary is small and fixed (7 entries), and a lazy grid nested
        // inside this screen's own LazyColumn crashes at measure time
        // ("scrollable component measured with infinity height constraints")
        // - confirmed via a real on-device crash (logcat FATAL EXCEPTION)
        // the first time this screen actually rendered, not caught by the
        // ViewModel-only unit tests since they never exercise Compose layout.
        items(LIVE_GROUND_TRUTH_MARKER_TYPES.chunked(2)) { rowTypes ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowTypes.forEach { type ->
                    OutlinedButton(
                        onClick = { onRecordMarker(type) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("${type.label()} (${state.markerCounts[type] ?: 0})")
                    }
                }
                if (rowTypes.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        item {
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text("Stop & export session")
            }
        }
    }
}

private fun CapabilityMode.label(): String = when (this) {
    CapabilityMode.FULL_AUTO -> "Full Auto"
    CapabilityMode.ASSISTED_AUTO -> "Assisted Auto"
    CapabilityMode.MANUAL -> "Manual"
    CapabilityMode.LOCATION_DEGRADED -> "Location degraded"
}

private fun GroundTruthMarkerType.label(): String = when (this) {
    GroundTruthMarkerType.GT_START -> "GT start"
    GroundTruthMarkerType.GT_END -> "GT end"
    GroundTruthMarkerType.READY_TO_START -> "Ready to start"
    GroundTruthMarkerType.ARRIVED -> "Arrived"
    GroundTruthMarkerType.MANUAL_PAUSE_BEGIN -> "Manual pause begin"
    GroundTruthMarkerType.MANUAL_PAUSE_END -> "Manual pause end"
    GroundTruthMarkerType.KNOWN_TRAFFIC_STOP -> "Known traffic stop"
    GroundTruthMarkerType.KNOWN_TUNNEL -> "Known tunnel"
    GroundTruthMarkerType.KNOWN_GPS_OBSTRUCTION -> "Known GPS obstruction"
}
