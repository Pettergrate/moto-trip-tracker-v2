package com.mototriptracker.app.feature.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.mototriptracker.app.feature.common.formatDistanceKm
import com.mototriptracker.app.feature.common.formatDurationCompact

/** TRS-001/F0.9 §14: reached from Settings, not a tab - trashing is infrequent, unlike Favorites. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    TrashContent(
        uiState = uiState,
        onBack = onBack,
        onRestore = viewModel::onRestore,
        onDeleteForever = viewModel::onDeleteForever
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashContent(
    uiState: TrashUiState,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onDeleteForever: (String) -> Unit
) {
    var pendingDeleteForeverId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (uiState.trips.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp), contentAlignment = Alignment.Center) {
                Text("Trash is empty", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.trips, key = { it.tripId }) { trip ->
                    TrashTripRow(
                        trip,
                        onRestore = { onRestore(trip.tripId) },
                        onDeleteForever = { pendingDeleteForeverId = trip.tripId }
                    )
                }
                item { Box(modifier = Modifier.padding(bottom = 16.dp)) }
            }
        }
    }

    val deleteForeverId = pendingDeleteForeverId
    if (deleteForeverId != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteForeverId = null },
            title = { Text("Delete forever?") },
            text = { Text("This permanently deletes the trip and its route. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteForever(deleteForeverId)
                    pendingDeleteForeverId = null
                }) { Text("Delete forever") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteForeverId = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TrashTripRow(trip: TrashTripUi, onRestore: () -> Unit, onDeleteForever: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(trip.displayName, style = MaterialTheme.typography.titleSmall)
            Text(trip.dateTimeLabel, style = MaterialTheme.typography.bodySmall)
            val distanceText = trip.distanceMeters?.let { formatDistanceKm(it) } ?: "—"
            val durationText = trip.durationMs?.let { formatDurationCompact(it) } ?: "—"
            Text("$distanceText · $durationText", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Deletes permanently on ${trip.purgeDateLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDeleteForever) { Text("Delete forever") }
                TextButton(onClick = onRestore) { Text("Restore") }
            }
        }
    }
}
