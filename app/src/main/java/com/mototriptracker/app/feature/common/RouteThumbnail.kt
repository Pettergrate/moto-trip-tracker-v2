package com.mototriptracker.app.feature.common

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.mototriptracker.app.domain.GeoPoint

/**
 * HIS-002/F0.9 §8.1's "miniatura estática/preview de ruta" - a tiny,
 * illustrative route shape for a history row, not a real map. Deliberately
 * `androidx.compose.foundation.Canvas`, not `TripRouteMap` - MapLibre's own
 * `ADR-019` isolation boundary ("the ONLY file allowed to import
 * `org.maplibre.*`") already rules that out, and a real `MapView` per
 * scrolling list row (its own `Lifecycle`, a real network tile fetch each)
 * would be far too heavy regardless - this draws [points] as flat lines,
 * nothing else.
 *
 * Normalizes latitude/longitude independently to fill the box, which
 * distorts the true aspect ratio away from the equator - an accepted
 * simplification for a small shape preview, not a claim about real
 * proportions the way `TripRouteMap`'s own projected map is.
 */
@Composable
fun RouteThumbnail(points: List<GeoPoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) return

    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val lats = points.map { it.latitude }
        val lons = points.map { it.longitude }
        val latSpan = (lats.max() - lats.min()).takeIf { it > 0.0 } ?: 1.0
        val lonSpan = (lons.max() - lons.min()).takeIf { it > 0.0 } ?: 1.0
        val minLat = lats.min()
        val minLon = lons.min()

        val path = Path()
        points.forEachIndexed { index, point ->
            val x = ((point.longitude - minLon) / lonSpan * size.width).toFloat()
            // Latitude increases northward but Canvas y increases downward - flip it.
            val y = ((1.0 - (point.latitude - minLat) / latSpan) * size.height).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 2.dp.toPx()))
    }
}
