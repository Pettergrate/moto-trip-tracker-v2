package com.mototriptracker.app.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mototriptracker.app.feature.common.formatDistanceKm
import com.mototriptracker.app.feature.common.formatDurationCompact

/**
 * HIS-001/F0.9 §8: HIS-01. `favorite` here is a read-only indicator (the
 * `Trip.isFavorite` column already exists since FND-003) - the tap-to-toggle
 * action itself is `FR-FAV-001`'s job, a separate task from this one's own
 * Implements line (`FR-HIS-001..004`/`FR-HIS-008`).
 */
@Composable
fun HistoryScreen(
    onOpenTripDetail: (String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HistoryContent(
        uiState = uiState,
        onOpenTripDetail = onOpenTripDetail,
        onToggleSortOrder = viewModel::onToggleSortOrder
    )
}

@Composable
private fun HistoryContent(
    uiState: HistoryUiState,
    onOpenTripDetail: (String) -> Unit,
    onToggleSortOrder: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onToggleSortOrder) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    if (uiState.sortOrder == SortOrder.NEWEST_FIRST) "Newest first" else "Oldest first",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        if (uiState.trips.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("No trips yet", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.trips, key = { it.tripId }) { trip ->
                    HistoryTripRow(trip, onClick = { onOpenTripDetail(trip.tripId) })
                }
                item { Box(modifier = Modifier.padding(bottom = 16.dp)) }
            }
        }
    }
}

@Composable
private fun HistoryTripRow(trip: HistoryTripUi, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(trip.displayName, style = MaterialTheme.typography.titleSmall)
                Text(trip.dateTimeLabel, style = MaterialTheme.typography.bodySmall)
                val distanceText = trip.distanceMeters?.let { formatDistanceKm(it) } ?: "—"
                val durationText = trip.durationMs?.let { formatDurationCompact(it) } ?: "—"
                Text("$distanceText · $durationText", style = MaterialTheme.typography.bodyMedium)
            }
            if (trip.isFavorite) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Favorite",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
