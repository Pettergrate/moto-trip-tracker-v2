package com.mototriptracker.app.feature.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mototriptracker.app.feature.common.TripSummaryRow

/** FAV-001/F0.9: FR-FAV-002's dedicated favorites area, replacing UI-001's placeholder. */
@Composable
fun FavoritesScreen(
    onOpenTripDetail: (String) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    FavoritesContent(
        uiState = uiState,
        onOpenTripDetail = onOpenTripDetail,
        onToggleFavorite = viewModel::onToggleFavorite
    )
}

@Composable
private fun FavoritesContent(
    uiState: FavoritesUiState,
    onOpenTripDetail: (String) -> Unit,
    onToggleFavorite: (String, Boolean) -> Unit
) {
    if (uiState.trips.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text("No favorite trips yet", style = MaterialTheme.typography.bodyLarge)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(uiState.trips, key = { it.tripId }) { trip ->
                TripSummaryRow(
                    trip,
                    onClick = { onOpenTripDetail(trip.tripId) },
                    onToggleFavorite = { onToggleFavorite(trip.tripId, trip.isFavorite) }
                )
            }
            item { Box(modifier = Modifier.padding(bottom = 16.dp)) }
        }
    }
}
