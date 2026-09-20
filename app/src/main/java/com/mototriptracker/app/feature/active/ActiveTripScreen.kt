package com.mototriptracker.app.feature.active

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mototriptracker.app.feature.common.formatDistanceKm
import com.mototriptracker.app.feature.common.formatDurationClock
import com.mototriptracker.app.feature.common.formatDurationCompact

/**
 * F0.9 §6: TRP-01. Back only leaves this screen (UX-05) - it's wired to a
 * plain `onBack` pop, never to Pause/Finish, and MAP-001 isn't built yet so
 * the route area is an honest placeholder rather than a fake map.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveTripScreen(
    onBack: () -> Unit,
    viewModel: ActiveTripViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The only ways here are "an active trip exists" (Home's CTA) or a
    // still-active one from a previous composition - once it's genuinely
    // gone (Finish completed, from here or elsewhere), leave automatically
    // rather than showing a stale/empty active-trip screen.
    LaunchedEffect(uiState) {
        if (uiState is ActiveTripUiState.NoActiveTrip) onBack()
    }

    ActiveTripContent(
        uiState = uiState,
        onBack = onBack,
        onPauseClick = viewModel::onPauseClick,
        onResumeClick = viewModel::onResumeClick,
        onFinishConfirmed = viewModel::onFinishConfirmed
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveTripContent(
    uiState: ActiveTripUiState,
    onBack: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onFinishConfirmed: () -> Unit
) {
    var showFinishConfirmation by remember { mutableStateOf(false) }
    val isPaused = (uiState as? ActiveTripUiState.Active)?.isPaused == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isPaused) "Trip paused" else "Trip in progress") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        when (uiState) {
            is ActiveTripUiState.Loading, ActiveTripUiState.NoActiveTrip -> {
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            is ActiveTripUiState.Active -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatDistanceKm(uiState.distanceMeters), style = MaterialTheme.typography.headlineMedium)
                        Text(formatDurationClock(uiState.elapsedMs), style = MaterialTheme.typography.headlineMedium)
                    }
                    if (isPaused && uiState.pauseElapsedMs != null) {
                        Text(
                            "Paused for ${formatDurationCompact(uiState.pauseElapsedMs)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    // MAP-001 isn't built yet - an honest placeholder, not a fake route.
                    Card(modifier = Modifier.fillMaxWidth().aspectRatio(1.2f)) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Map not available yet", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = if (isPaused) onResumeClick else onPauseClick,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isPaused) "RESUME" else "PAUSE")
                        }
                        Button(
                            onClick = { showFinishConfirmation = true },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("FINISH")
                        }
                    }
                }
            }
        }
    }

    if (showFinishConfirmation) {
        AlertDialog(
            onDismissRequest = { showFinishConfirmation = false },
            title = { Text("Finish this trip?") },
            confirmButton = {
                TextButton(onClick = {
                    showFinishConfirmation = false
                    onFinishConfirmed()
                }) { Text("Finish") }
            },
            dismissButton = {
                TextButton(onClick = { showFinishConfirmation = false }) { Text("Back") }
            }
        )
    }
}
