package com.mototriptracker.app.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mototriptracker.app.domain.GeoPoint
import com.mototriptracker.app.feature.common.routeScrubberPercent

/**
 * MAP-004/MAP-005/`FR-MAP-008`/`ADR-023`: a read-only preview control, shared by Trip Detail's inline map and its
 * full-screen view ([com.mototriptracker.app.feature.tripdetail.TripMapScreen]) - dragging it only moves
 * [TripRouteMap]'s external marker/camera focus (via [onIndexChanged]), it never creates, edits or persists
 * anything, unlike Split/Trim's sliders which share this same interaction shape but write a new Trip on save.
 */
@Composable
fun RouteScrubber(routePoints: List<GeoPoint>, index: Int?, onIndexChanged: (Int) -> Unit) {
    val maxIndex = routePoints.size - 1
    val percent = routeScrubberPercent(index, maxIndex)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Slider(
            value = (index ?: 0).toFloat(),
            onValueChange = { onIndexChanged(it.toInt()) },
            valueRange = 0f..maxIndex.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            if (index == null) "Drag to preview a point along the route" else "$percent% along the route",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
