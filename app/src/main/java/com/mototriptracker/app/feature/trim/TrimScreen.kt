package com.mototriptracker.app.feature.trim

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mototriptracker.app.feature.common.formatDistanceKm
import com.mototriptracker.app.feature.common.formatDurationCompact
import com.mototriptracker.app.feature.map.TripRouteMap
import kotlinx.coroutines.launch

/**
 * EDT-003/`FR-EDT-005`: move a Trip's start and/or end inward with a two-thumb
 * slider, previewing the resulting distance/duration first. The excluded
 * points are never deleted (§8.5) - the Trip just stops using them. No UX
 * wireframe exists for this (F0.9 only specifies Split's), so this reuses
 * Split's proven layout on purpose.
 *
 * [onTrimDone] pops both this screen and the Trip Detail beneath it: a
 * successful trim supersedes the Trip they were about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(
    tripId: String,
    onBack: () -> Unit,
    onTrimDone: () -> Unit,
    viewModel: TrimViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(tripId) { viewModel.load(tripId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trim trip") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { innerPadding ->
        when (val state = uiState) {
            TrimUiState.Loading -> CenteredMessage("Loading…", Modifier.padding(innerPadding))
            TrimUiState.NotAvailable -> CenteredMessage("This trip cannot be trimmed", Modifier.padding(innerPadding))
            is TrimUiState.Ready -> Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TripRouteMap(
                    points = state.routePoints,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.5f),
                    markerPoints = listOf(state.startPoint, state.endPoint)
                )
                Text("Choose where the trip starts and ends", style = MaterialTheme.typography.titleMedium)
                RangeSlider(
                    value = state.startIndex.toFloat()..state.endIndex.toFloat(),
                    onValueChange = { range -> viewModel.onRangeChanged(range.start.toInt(), range.endInclusive.toInt()) },
                    valueRange = 0f..state.maxIndex.toFloat(),
                    enabled = !state.isSaving
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Trimmed trip", style = MaterialTheme.typography.titleSmall)
                        Text(formatDistanceKm(state.kept.distanceMeters), style = MaterialTheme.typography.headlineSmall)
                        Text(formatDurationCompact(state.kept.durationMs), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (state.hasChanges) {
                                "Removes ${formatDurationCompact(state.kept.removedDurationMs)} from the recording"
                            } else {
                                "Drag the handles to remove time from the start or end"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "Nothing is deleted: the excluded points stay in the recording. The original trip is replaced by the trimmed one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack, enabled = !state.isSaving, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = { coroutineScope.launch { if (viewModel.save()) onTrimDone() } },
                        enabled = state.hasChanges && !state.isSaving,
                        modifier = Modifier.weight(1f)
                    ) { Text("Trim") }
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}
