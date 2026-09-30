package com.mototriptracker.app.feature.tripdetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.mototriptracker.app.feature.map.RouteScrubber
import com.mototriptracker.app.feature.map.TripRouteMap

/**
 * MAP-005/`ADR-023`: the same route Trip Detail already shows, full-screen for easier manipulation - opened from
 * Trip Detail's own "⛶" button (`ADR-023`'s recommendation over a `Dialog` overlay: a real back-stack destination
 * like Split/Trim already are, which gets Predictive Back support for free, `UX-16`). Reuses [TripDetailViewModel]
 * directly rather than duplicating its route-loading query - this screen shows a subset of the same data, not a
 * different one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripMapScreen(
    tripId: String,
    onBack: () -> Unit,
    viewModel: TripDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(tripId) { viewModel.load(tripId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip route") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        when (val state = uiState) {
            is TripDetailUiState.Loaded -> {
                // MAP-004's own reset-per-Trip key, unchanged - this screen has its own scrubber state, independent
                // of whichever position (if any) was scrubbed to on Trip Detail beneath it.
                var scrubberIndex by remember(state.tripId) { mutableStateOf<Int?>(null) }
                val scrubbedPoint = scrubberIndex?.let { state.routePoints.getOrNull(it) }

                Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
                    TripRouteMap(
                        points = state.routePoints,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        markerPoints = scrubbedPoint?.let { listOf(it) } ?: emptyList(),
                        focusPoint = scrubbedPoint
                    )
                    if (state.routePoints.size >= 2) {
                        Column(modifier = Modifier.padding(top = 16.dp)) {
                            RouteScrubber(
                                routePoints = state.routePoints,
                                index = scrubberIndex,
                                onIndexChanged = { scrubberIndex = it }
                            )
                        }
                    }
                }
            }
            else -> {
                // Loading/NotFound: a fresh `TripDetailViewModel` instance means a brief real reload, not just
                // reusing Trip Detail's already-loaded state - a plain message here, matching the same two states'
                // own copy on Trip Detail.
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    Text(
                        if (uiState is TripDetailUiState.NotFound) "Trip not found" else "Loading…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
