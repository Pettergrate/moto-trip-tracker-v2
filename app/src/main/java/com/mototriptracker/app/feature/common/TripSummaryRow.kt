package com.mototriptracker.app.feature.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mototriptracker.app.domain.GeoPoint

/** FAV-001/F0.9 §8.1's minimum row fields: name, date/time, distance, duration, favorite - shared by History (HIS-001) and Favorites. */
data class TripSummaryUi(
    val tripId: String,
    val displayName: String,
    val dateTimeLabel: String,
    val distanceMeters: Double?,
    val durationMs: Long?,
    val isFavorite: Boolean,
    /** HIS-002: only History populates this today (a bounded, sampled preview - see `ProcessedTrackPointDao.observeSampledByTrips`); empty for Favorites' rows, which then render with no thumbnail slot at all, unchanged from before this task. */
    val routePoints: List<GeoPoint> = emptyList()
)

/**
 * FAV-001: the same row card History (HIS-001) already shipped, now with a
 * real tap-to-toggle star instead of a read-only indicator - shared so
 * Favorites (FR-FAV-002) renders identically rather than duplicating it.
 */
@Composable
fun TripSummaryRow(trip: TripSummaryUi, onClick: () -> Unit, onToggleFavorite: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (trip.routePoints.size >= 2) {
                    RouteThumbnail(trip.routePoints, modifier = Modifier.size(48.dp))
                }
                Column {
                    Text(trip.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(trip.dateTimeLabel, style = MaterialTheme.typography.bodySmall)
                    val distanceText = trip.distanceMeters?.let { formatDistanceKm(it) } ?: "—"
                    val durationText = trip.durationMs?.let { formatDurationCompact(it) } ?: "—"
                    Text("$distanceText · $durationText", style = MaterialTheme.typography.bodyMedium)
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = if (trip.isFavorite) "Unfavorite" else "Favorite",
                    tint = if (trip.isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    }
                )
            }
        }
    }
}
