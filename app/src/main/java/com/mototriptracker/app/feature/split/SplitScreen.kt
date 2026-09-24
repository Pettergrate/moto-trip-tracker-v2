package com.mototriptracker.app.feature.split

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
 * F0.9 §12 SPL-01: route map, one selectable cut point, and a live
 * preview of "Part 1"/"Part 2" *before* anything is confirmed (UX-10). The
 * slider is the accessible, glance-free way to pick the cut (UX-14: never
 * required while riding; no coordinates are ever typed - §12).
 *
 * [onSplitDone] rather than a plain back: a successful split supersedes the
 * Trip this whole stack (Split -> Trip Detail) was about, so the caller pops
 * both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitScreen(
    tripId: String,
    onBack: () -> Unit,
    onSplitDone: () -> Unit,
    viewModel: SplitViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(tripId) { viewModel.load(tripId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Split trip") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        when (val state = uiState) {
            SplitUiState.Loading -> CenteredMessage("Loading…", Modifier.padding(innerPadding))
            SplitUiState.NotAvailable -> CenteredMessage("This trip can't be split", Modifier.padding(innerPadding))
            is SplitUiState.Ready -> Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TripRouteMap(
                    points = state.routePoints,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.5f),
                    markerPoint = state.cutPoint
                )
                Text("Choose where to cut", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = state.cutIndex.toFloat(),
                    onValueChange = { viewModel.onCutIndexChanged(it.toInt()) },
                    valueRange = state.minCutIndex.toFloat()..state.maxCutIndex.toFloat(),
                    enabled = !state.isSplitting
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PartCard("Part 1", state.first, Modifier.weight(1f))
                    PartCard("Part 2", state.second, Modifier.weight(1f))
                }
                Text(
                    "Both parts keep every recorded point. The original trip is replaced by these two.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack, enabled = !state.isSplitting, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = { coroutineScope.launch { if (viewModel.split()) onSplitDone() } },
                        enabled = !state.isSplitting,
                        modifier = Modifier.weight(1f)
                    ) { Text("Split") }
                }
            }
        }
    }
}

@Composable
private fun PartCard(title: String, preview: SplitPartPreview, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(formatDistanceKm(preview.distanceMeters), style = MaterialTheme.typography.headlineSmall)
            Text(formatDurationCompact(preview.durationMs), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CenteredMessage(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}
