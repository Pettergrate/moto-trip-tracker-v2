package com.mototriptracker.app.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mototriptracker.app.feature.common.TripSummaryRow

/** HIS-001/HIS-002/F0.9 §8: HIS-01 (`FR-HIS-001..004`/`006`/`007`/`008`). Favorite toggling is `TripSummaryRow`'s own job, shared with Favorites (`FAV-001`). */
@Composable
fun HistoryScreen(
    onOpenTripDetail: (String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HistoryContent(
        uiState = uiState,
        onOpenTripDetail = onOpenTripDetail,
        onSortOrderSelected = viewModel::onSortOrderSelected,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onDateFilterSelected = viewModel::onDateFilterSelected,
        onFavoritesOnlyToggled = viewModel::onFavoritesOnlyToggled,
        onToggleFavorite = viewModel::onToggleFavorite
    )
}

@Composable
private fun HistoryContent(
    uiState: HistoryUiState,
    onOpenTripDetail: (String) -> Unit,
    onSortOrderSelected: (SortOrder) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onDateFilterSelected: (DateFilter) -> Unit,
    onFavoritesOnlyToggled: () -> Unit,
    onToggleFavorite: (String, Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChanged,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search trips") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (uiState.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChanged("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = uiState.favoritesOnly,
                onClick = onFavoritesOnlyToggled,
                label = { Text("Favorites") }
            )
            DateFilter.entries.forEach { dateFilter ->
                FilterChip(
                    selected = uiState.dateFilter == dateFilter,
                    onClick = { onDateFilterSelected(dateFilter) },
                    label = { Text(dateFilter.label()) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            SortMenuButton(selected = uiState.sortOrder, onSortOrderSelected = onSortOrderSelected)
        }

        if (uiState.trips.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                val message = if (uiState.searchQuery.isNotBlank() || uiState.favoritesOnly || uiState.dateFilter != DateFilter.ALL_TIME) {
                    "No trips match"
                } else {
                    "No trips yet"
                }
                Text(message, style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
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
}

@Composable
private fun SortMenuButton(selected: SortOrder, onSortOrderSelected: (SortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("Sort: ${selected.label()}", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOrder.entries.forEach { sortOrder ->
                DropdownMenuItem(
                    text = { Text(sortOrder.label()) },
                    onClick = {
                        expanded = false
                        onSortOrderSelected(sortOrder)
                    }
                )
            }
        }
    }
}

private fun SortOrder.label(): String = when (this) {
    SortOrder.NEWEST_FIRST -> "Newest first"
    SortOrder.OLDEST_FIRST -> "Oldest first"
    SortOrder.LONGEST_DISTANCE -> "Longest distance"
    SortOrder.SHORTEST_DISTANCE -> "Shortest distance"
    SortOrder.LONGEST_DURATION -> "Longest duration"
    SortOrder.SHORTEST_DURATION -> "Shortest duration"
}

private fun DateFilter.label(): String = when (this) {
    DateFilter.ALL_TIME -> "All time"
    DateFilter.THIS_WEEK -> "This week"
    DateFilter.THIS_MONTH -> "This month"
}
