package com.mototriptracker.app.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mototriptracker.app.core.model.CapabilityMode
import com.mototriptracker.app.feature.common.formatDistanceKm
import com.mototriptracker.app.feature.common.formatDurationClock
import com.mototriptracker.app.feature.common.formatDurationCompact

/** F0.9 §5: HOME-01. */
@Composable
fun HomeScreen(
    onViewActiveTrip: () -> Unit,
    onOpenTripDetail: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // F0.9 §17: re-check readiness whenever Home is (re)composed, so a
    // permission granted/revoked in system Settings is reflected without
    // needing a continuous poll.
    LaunchedEffect(Unit) { viewModel.refreshCapabilityMode() }

    HomeContent(
        uiState = uiState,
        onStartTripClick = viewModel::onStartTripClick,
        onPauseClick = viewModel::onPauseClick,
        onResumeClick = viewModel::onResumeClick,
        onViewActiveTrip = onViewActiveTrip,
        onOpenTripDetail = onOpenTripDetail
    )
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    onStartTripClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onViewActiveTrip: () -> Unit,
    onOpenTripDetail: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            val activeTrip = uiState.activeTrip
            if (activeTrip != null) {
                ActiveTripCard(
                    isPaused = activeTrip.isPaused,
                    distanceMeters = activeTrip.distanceMeters,
                    elapsedMs = activeTrip.elapsedMs,
                    onViewActiveTrip = onViewActiveTrip,
                    onPauseClick = onPauseClick,
                    onResumeClick = onResumeClick
                )
            } else {
                ReadinessCard(capabilityMode = uiState.capabilityMode, onStartTripClick = onStartTripClick)
            }
        }

        item {
            Text("Recent", style = MaterialTheme.typography.titleMedium)
        }

        if (uiState.recentTrips.isEmpty()) {
            item { Text("No trips yet", style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(uiState.recentTrips, key = { it.tripId }) { trip ->
                RecentTripRow(trip, onClick = { onOpenTripDetail(trip.tripId) })
            }
        }
    }
}

@Composable
private fun ReadinessCard(capabilityMode: CapabilityMode?, onStartTripClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Auto Tracking", style = MaterialTheme.typography.titleMedium)
            Text(
                text = capabilityMode?.toReadinessText() ?: "Checking readiness…",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onStartTripClick, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Text("START TRIP")
            }
        }
    }
}

@Composable
private fun ActiveTripCard(
    isPaused: Boolean,
    distanceMeters: Double,
    elapsedMs: Long,
    onViewActiveTrip: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (isPaused) "● TRIP PAUSED" else "● TRIP IN PROGRESS",
                style = MaterialTheme.typography.titleMedium
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDistanceKm(distanceMeters), style = MaterialTheme.typography.headlineSmall)
                Text(formatDurationClock(elapsedMs), style = MaterialTheme.typography.headlineSmall)
            }
            Button(onClick = onViewActiveTrip, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Text("VIEW ACTIVE TRIP")
            }
            OutlinedButton(
                onClick = if (isPaused) onResumeClick else onPauseClick,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isPaused) "Resume" else "Pause")
            }
        }
    }
}

@Composable
private fun RecentTripRow(trip: RecentTripUi, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(trip.displayName, style = MaterialTheme.typography.titleSmall)
            val distanceText = trip.distanceMeters?.let { formatDistanceKm(it) } ?: "—"
            val durationText = trip.durationMs?.let { formatDurationCompact(it) } ?: "—"
            Text("$distanceText · $durationText", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
