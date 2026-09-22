package com.mototriptracker.app.feature.tripdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mototriptracker.app.feature.common.formatDistanceKm
import com.mototriptracker.app.feature.common.formatDurationCompact
import com.mototriptracker.app.feature.common.formatElevationM
import com.mototriptracker.app.feature.map.TripRouteMap
import com.mototriptracker.app.feature.common.formatSpeedKmh

/** F0.9 §9: HIS-02. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    tripId: String,
    onBack: () -> Unit,
    viewModel: TripDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(tripId) { viewModel.load(tripId) }

    TripDetailContent(
        uiState = uiState,
        onBack = onBack,
        onRename = viewModel::onRename,
        onToggleFavorite = viewModel::onToggleFavorite
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripDetailContent(
    uiState: TripDetailUiState,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onToggleFavorite: () -> Unit
) {
    var showRenameDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip detail") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState is TripDetailUiState.Loaded) {
                        IconButton(onClick = onToggleFavorite) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = if (uiState.isFavorite) "Unfavorite" else "Favorite",
                                tint = if (uiState.isFavorite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                }
                            )
                        }
                        IconButton(onClick = { showRenameDialog = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Rename")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        when (uiState) {
            TripDetailUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            TripDetailUiState.NotFound -> {
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    Text("Trip not found", style = MaterialTheme.typography.bodyLarge)
                }
            }

            is TripDetailUiState.Loaded -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item { HeaderSection(uiState) }
                    item {
                        // MAP-001/ADR-021: TripRouteMap itself renders the
                        // honest "Map not available yet" placeholder when
                        // routePoints has fewer than 2 points - no separate
                        // branch needed here.
                        TripRouteMap(
                            points = uiState.routePoints,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1.5f)
                        )
                    }
                    item { CoreMetricsSection(uiState) }
                    item { TimeBreakdownSection(uiState) }
                    if (hasElevationData(uiState)) {
                        item { ElevationSection(uiState) }
                    }
                }
            }
        }
    }

    if (showRenameDialog && uiState is TripDetailUiState.Loaded) {
        RenameDialog(
            initialName = if (uiState.isUserNamed) uiState.displayName else "",
            onConfirm = { newName ->
                onRename(newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false }
        )
    }
}

@Composable
private fun HeaderSection(state: TripDetailUiState.Loaded) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(state.displayName, style = MaterialTheme.typography.headlineSmall)
        Text(state.dateTimeLabel, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CoreMetricsSection(state: TripDetailUiState.Loaded) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        MetricColumn("Distance", state.distanceMeters?.let { formatDistanceKm(it) })
        MetricColumn("Duration", state.totalDurationMs?.let { formatDurationCompact(it) })
    }
}

@Composable
private fun TimeBreakdownSection(state: TripDetailUiState.Loaded) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Details", style = MaterialTheme.typography.titleMedium)
            MetricRow("Moving time", state.movingDurationMs?.let { formatDurationCompact(it) })
            MetricRow("Stopped time", state.stoppedDurationMs?.let { formatDurationCompact(it) })
            MetricRow("Manual pause time", state.manualPauseDurationMs?.let { formatDurationCompact(it) })
            MetricRow("Max speed", state.maxSpeedMps?.let { formatSpeedKmh(it) })
            MetricRow("Average speed", state.averageSpeedMps?.let { formatSpeedKmh(it) })
            MetricRow("Average moving speed", state.averageMovingSpeedMps?.let { formatSpeedKmh(it) })
        }
    }
}

private fun hasElevationData(state: TripDetailUiState.Loaded): Boolean =
    state.minElevationM != null || state.maxElevationM != null || state.ascentM != null || state.descentM != null

@Composable
private fun ElevationSection(state: TripDetailUiState.Loaded) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Elevation", style = MaterialTheme.typography.titleMedium)
            MetricRow("Minimum", state.minElevationM?.let { formatElevationM(it) })
            MetricRow("Maximum", state.maxElevationM?.let { formatElevationM(it) })
            MetricRow("Ascent", state.ascentM?.let { formatElevationM(it) })
            MetricRow("Descent", state.descentM?.let { formatElevationM(it) })
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: String?) {
    Column {
        Text(value ?: "—", style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MetricRow(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RenameDialog(initialName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename trip") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Trip name") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
