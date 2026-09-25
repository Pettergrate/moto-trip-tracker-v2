package com.mototriptracker.app.feature.map

import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mototriptracker.app.domain.GeoPoint
import kotlin.math.hypot
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
import org.maplibre.geojson.FeatureCollection
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
 *
 * MAP-002/`ux-navigation.md` §19/REF-001's owner-approved scope addition:
 * tapping the route selects the nearest of [points] (a plain nearest-point
 * search over an already-simplified, at-most-few-thousand-point list - no
 * need for a spatial index at this scale) and shows it with a distinct,
 * higher-contrast marker plus an accessible info card, until the selection
 * is changed or dismissed. This is presentation-only, self-contained state -
 * nothing else in the app needs to know what's selected.
 *
 * EDT-002/003: a non-empty [markerPoints] puts the map under *external* control
 * instead - the same high-contrast marker is drawn at each exact point
 * (Split's cut, Trim's start/end, driven by their sliders), and tap-selection plus its
 * info card are switched off so the two can't fight over one marker.
 */
@Composable
fun TripRouteMap(points: List<GeoPoint>, modifier: Modifier = Modifier, markerPoints: List<GeoPoint> = emptyList()) {
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
    // Keyed on `points` so navigating from one Trip's detail to another's
    // (the same `remember`ed-across-recomposition pattern MAP-001 already
    // learned about `configureRoute`) doesn't leave the previous Trip's
    // selection visually stuck on the new route.
    var selectedPoint by remember(points) { mutableStateOf<GeoPoint?>(null) }

    // Recomposition with a *different* `points` (a different Trip, or the
    // same Trip's route arriving after the map was already ready) must
    // redraw the route, not just the very first one - `AndroidView`'s own
    // `factory` runs exactly once for as long as this composable's
    // `remember`ed `mapView` survives, which real navigation testing on a
    // real device showed outlives a single Trip Detail visit (a second
    // Trip's screen reused the first Trip's already-drawn route until this
    // fix, even though the header/metrics above it updated correctly).
    LaunchedEffect(points, map) {
        map?.let { configureRoute(it, points, markerPoints) }
    }

    // Screen-pixel distance, not ground distance: a real-world meter
    // tolerance felt broken at typical "whole route" zoom levels (verified
    // live - a tap that looked dead-on the line at a 30km route's overview
    // zoom was still ~700m from the nearest recorded point on the ground,
    // since one screen pixel there covers tens of real-world meters).
    // Comparing in screen space instead makes tap precision consistent
    // regardless of how zoomed in the current camera happens to be.
    val selectionToleranceDp = with(LocalDensity.current) { SELECTION_TOLERANCE_DP.dp.toPx() }

    // Re-registered whenever `points` or `map` changes - a click listener
    // keyed only on `map` would close over whichever `points` list existed
    // the first time it was registered, the exact stale-closure shape the
    // `configureRoute` comment above already documents for this file.
    DisposableEffect(points, map, markerPoints.isEmpty()) {
        val currentMap = map
        if (markerPoints.isNotEmpty()) return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnMapClickListener { latLng ->
            val projection = currentMap?.projection
            val tapScreenPoint = projection?.toScreenLocation(latLng)
            selectedPoint = if (projection == null || tapScreenPoint == null) {
                null
            } else {
                points.map { point ->
                    val pointScreen = projection.toScreenLocation(LatLng(point.latitude, point.longitude))
                    point to hypot((pointScreen.x - tapScreenPoint.x).toDouble(), (pointScreen.y - tapScreenPoint.y).toDouble())
                }.minByOrNull { (_, distancePx) -> distancePx }
                    ?.takeIf { (_, distancePx) -> distancePx <= selectionToleranceDp }
                    ?.first
            }
            true
        }
        currentMap?.addOnMapClickListener(listener)
        onDispose { currentMap?.removeOnMapClickListener(listener) }
    }

    LaunchedEffect(selectedPoint, markerPoints, map) {
        map?.let { updateSelectedPointLayer(it, markerPoints.ifEmpty { listOfNotNull(selectedPoint) }) }
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
        selectedPoint?.takeIf { markerPoints.isEmpty() }?.let { point ->
            SelectedPointCard(point = point, onDismiss = { selectedPoint = null }, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
        }
    }
}

/** REF-001's own accessibility requirement ("plus its own accessibility node") - a real Compose semantics node, since MapLibre's GL-rendered markers have none of their own to give. */
@Composable
private fun SelectedPointCard(point: GeoPoint, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "Selected point, latitude %.5f, longitude %.5f".format(point.latitude, point.longitude)
        }
    ) {
        Row(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("%.5f, %.5f".format(point.latitude, point.longitude), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Clear selection")
            }
        }
    }
}

private fun configureRoute(map: MapLibreMap, points: List<GeoPoint>, initialMarkers: List<GeoPoint>) {
    val lineString = LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })
    val routeSource = GeoJsonSource(SOURCE_ROUTE, Feature.fromGeometry(lineString))
    val startSource = GeoJsonSource(SOURCE_START, Feature.fromGeometry(Point.fromLngLat(points.first().longitude, points.first().latitude)))
    val endSource = GeoJsonSource(SOURCE_END, Feature.fromGeometry(Point.fromLngLat(points.last().longitude, points.last().latitude)))
    val selectedSource = GeoJsonSource(SOURCE_SELECTED, FeatureCollection.fromFeatures(markerFeatures(initialMarkers)))

    map.setStyle(
        Style.Builder()
            .fromUri(OPENFREEMAP_LIBERTY_STYLE_URL)
            .withSource(routeSource)
            .withSource(startSource)
            .withSource(endSource)
            .withSource(selectedSource)
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
            // Added last so it draws above start/end when a selected point
            // happens to coincide with either - REF-001's "higher-contrast"
            // requirement means it must never be hidden underneath them.
            .withLayer(
                CircleLayer(LAYER_SELECTED, SOURCE_SELECTED).withProperties(
                    PropertyFactory.circleRadius(11f),
                    PropertyFactory.circleColor(SELECTED_COLOR),
                    PropertyFactory.circleStrokeColor(Color.WHITE),
                    PropertyFactory.circleStrokeWidth(3f)
                )
            )
    ) {
        fitCameraToRoute(map, points)
    }
}

/** MAP-002: an empty [FeatureCollection] clears the marker - `GeoJsonSource.setGeoJson` on an already-configured style is enough, no full `configureRoute` re-run needed for a selection change. */
private fun updateSelectedPointLayer(map: MapLibreMap, selected: List<GeoPoint>) {
    val source = map.style?.getSourceAs<GeoJsonSource>(SOURCE_SELECTED) ?: return
    source.setGeoJson(FeatureCollection.fromFeatures(markerFeatures(selected)))
}

private fun markerFeatures(points: List<GeoPoint>): List<Feature> =
    points.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }

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
private const val SOURCE_SELECTED = "trip-selected-source"
private const val LAYER_ROUTE = "trip-route-layer"
private const val LAYER_START = "trip-start-layer"
private const val LAYER_END = "trip-end-layer"
private const val LAYER_SELECTED = "trip-selected-layer"
private const val ROUTE_COLOR = "#6750A4"
private const val START_COLOR = "#4CAF50"
private const val END_COLOR = "#F44336"
/** The app's own primary accent (`BlackOrangePrimary`) - ties the selection marker to the app's identity rather than an arbitrary new color. */
private const val SELECTED_COLOR = "#F2540C"
/**
 * Screen-space, not ground-distance - a flat meter tolerance falls apart
 * across zoom levels (a whole-route overview and a zoomed-in segment cover
 * wildly different real-world distances per pixel; verified live, a tap
 * that looked dead-on the line at a 30km route's overview zoom was still
 * ~700m from the nearest recorded point on the ground). ~40dp roughly
 * matches a comfortable touch-target radius. Presentation-only hit-test
 * tolerance, not a detection threshold - no ADR-018 field-gate applies.
 */
private const val SELECTION_TOLERANCE_DP = 40
