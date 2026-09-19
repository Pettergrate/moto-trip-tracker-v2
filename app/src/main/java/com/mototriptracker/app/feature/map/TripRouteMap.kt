package com.mototriptracker.app.feature.map

import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mototriptracker.app.domain.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * MAP-001/ADR-019/ADR-021. The ONLY file in this codebase allowed to import
 * `org.maplibre.*` - `ADR-019`'s "map presentation adapter" boundary made
 * concrete: nothing in `domain`/`tracking`/`core` ever sees a map SDK type,
 * only a plain `List<GeoPoint>` crosses in.
 *
 * [points] should already be simplified (`domain.simplifyRoute`) before
 * reaching here - this composable draws whatever it's given, it doesn't
 * decide what's worth drawing.
 *
 * Deliberately a classic `AndroidView` bridge, not a native Compose API:
 * MapLibre Native's own Android SDK has no official Jetpack Compose
 * artifact (verified against its own API docs - `ADR-021`), so this is the
 * standard, supported way to host a View-based SDK inside Compose, not a
 * workaround.
 */
@Composable
fun TripRouteMap(points: List<GeoPoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) {
        MapUnavailablePlaceholder(modifier)
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val fitRequested = remember { mutableStateOf(0) }

    // Recomposition with a *different* `points` (a different Trip, or the
    // same Trip's route arriving after the map was already ready) must
    // redraw the route, not just the very first one - `AndroidView`'s own
    // `factory` runs exactly once for as long as this composable's
    // `remember`ed `mapView` survives, which real navigation testing on a
    // real device showed outlives a single Trip Detail visit (a second
    // Trip's screen reused the first Trip's already-drawn route until this
    // fix, even though the header/metrics above it updated correctly).
    LaunchedEffect(points, map) {
        map?.let { configureRoute(it, points) }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // The Activity is already at least CREATED/STARTED by the time this
        // composition runs (it's rendering this composable), so mapView's
        // own onCreate needs an explicit first call here - later transitions
        // arrive through the observer above.
        mapView.onCreate(null)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.getMapAsync { readyMap -> map = readyMap }
                mapView
            },
            update = {
                // fitRequested changing (the button below) re-triggers a
                // camera fit without rebuilding the style/sources.
                if (fitRequested.value > 0) {
                    map?.let { fitCameraToRoute(it, points) }
                }
            }
        )
        FloatingActionButton(
            onClick = { fitRequested.value += 1 },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
        ) {
            Text("⤢")
        }
    }
}

private fun configureRoute(map: MapLibreMap, points: List<GeoPoint>) {
    val lineString = LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })
    val routeSource = GeoJsonSource(SOURCE_ROUTE, Feature.fromGeometry(lineString))
    val startSource = GeoJsonSource(SOURCE_START, Feature.fromGeometry(Point.fromLngLat(points.first().longitude, points.first().latitude)))
    val endSource = GeoJsonSource(SOURCE_END, Feature.fromGeometry(Point.fromLngLat(points.last().longitude, points.last().latitude)))

    map.setStyle(
        Style.Builder()
            .fromUri(OPENFREEMAP_LIBERTY_STYLE_URL)
            .withSource(routeSource)
            .withSource(startSource)
            .withSource(endSource)
            .withLayer(
                LineLayer(LAYER_ROUTE, SOURCE_ROUTE).withProperties(
                    PropertyFactory.lineColor(ROUTE_COLOR),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
            .withLayer(
                CircleLayer(LAYER_START, SOURCE_START).withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor(START_COLOR),
                    PropertyFactory.circleStrokeColor(Color.WHITE),
                    PropertyFactory.circleStrokeWidth(2f)
                )
            )
            .withLayer(
                CircleLayer(LAYER_END, SOURCE_END).withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor(END_COLOR),
                    PropertyFactory.circleStrokeColor(Color.WHITE),
                    PropertyFactory.circleStrokeWidth(2f)
                )
            )
    ) {
        fitCameraToRoute(map, points)
    }
}

/** UX contract (`ux-navigation.md` §19): the optional "Ajustar ruta" affordance, also used once on first load. */
private fun fitCameraToRoute(map: MapLibreMap, points: List<GeoPoint>) {
    val boundsBuilder = LatLngBounds.Builder()
    points.forEach { boundsBuilder.include(LatLng(it.latitude, it.longitude)) }
    val bounds = boundsBuilder.build()
    val cameraUpdate = map.getCameraForLatLngBounds(bounds, intArrayOf(48, 48, 48, 48))
    if (cameraUpdate != null) {
        map.cameraPosition = cameraUpdate
    }
}

@Composable
private fun MapUnavailablePlaceholder(modifier: Modifier) {
    Card(modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Map not available yet", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val OPENFREEMAP_LIBERTY_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val SOURCE_ROUTE = "trip-route-source"
private const val SOURCE_START = "trip-start-source"
private const val SOURCE_END = "trip-end-source"
private const val LAYER_ROUTE = "trip-route-layer"
private const val LAYER_START = "trip-start-layer"
private const val LAYER_END = "trip-end-layer"
private const val ROUTE_COLOR = "#6750A4"
private const val START_COLOR = "#4CAF50"
private const val END_COLOR = "#F44336"
