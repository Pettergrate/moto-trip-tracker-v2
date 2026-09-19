package com.mototriptracker.app.domain

import kotlin.math.cos
import kotlin.math.sqrt

/** A plain lat/lon pair - `MAP-001`'s own minimal shape, independent of any specific Room entity or map SDK type (ADR-013/ADR-019). */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * MAP-001/FR-MAP-003: "the map implementation MUST remain usable for long
 * Trips and large numbers of TrackPoints" - `ux-navigation.md` §19 is more
 * specific still: "la ruta debe poder verse aunque tenga miles de puntos
 * mediante representación optimizada." Ramer-Douglas-Peucker is that
 * representation: it keeps exactly the points needed to represent the
 * route's real shape within [toleranceMeters] and drops the rest, rather
 * than a fixed max-point decimation that would need to guess a count and
 * could thin out a sharp turn as readily as a straight highway stretch.
 *
 * This never touches the Processed Track itself (ADR-006: polyline
 * simplification is never a source of truth for distance/speed) - it's a
 * presentation-only transform applied just before handing points to the map
 * renderer.
 */
fun simplifyRoute(points: List<GeoPoint>, toleranceMeters: Double = 8.0): List<GeoPoint> {
    if (points.size <= 2) return points
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.size - 1] = true
    simplifySegment(points, 0, points.size - 1, toleranceMeters, keep)
    return points.filterIndexed { index, _ -> keep[index] }
}

private fun simplifySegment(points: List<GeoPoint>, startIndex: Int, endIndex: Int, toleranceMeters: Double, keep: BooleanArray) {
    if (endIndex <= startIndex + 1) return

    var maxDistance = 0.0
    var maxIndex = -1
    for (i in (startIndex + 1) until endIndex) {
        val distance = perpendicularDistanceMeters(points[i], points[startIndex], points[endIndex])
        if (distance > maxDistance) {
            maxDistance = distance
            maxIndex = i
        }
    }

    if (maxDistance > toleranceMeters && maxIndex != -1) {
        keep[maxIndex] = true
        simplifySegment(points, startIndex, maxIndex, toleranceMeters, keep)
        simplifySegment(points, maxIndex, endIndex, toleranceMeters, keep)
    }
}

/**
 * An equirectangular local-planar approximation, not a great-circle
 * distance - adequate (and standard practice for RDP implementations) for
 * deciding which points are visually redundant over the short segments a
 * single-digit-meter tolerance operates on; never used for anything this
 * project treats as an authoritative distance (that's [haversineMeters]'s
 * job, e.g. in `TripMetricsCalculator`).
 */
private fun perpendicularDistanceMeters(point: GeoPoint, lineStart: GeoPoint, lineEnd: GeoPoint): Double {
    val metersPerDegreeLat = 111_320.0
    val metersPerDegreeLon = 111_320.0 * cos(Math.toRadians(lineStart.latitude))

    val x0 = point.longitude * metersPerDegreeLon
    val y0 = point.latitude * metersPerDegreeLat
    val x1 = lineStart.longitude * metersPerDegreeLon
    val y1 = lineStart.latitude * metersPerDegreeLat
    val x2 = lineEnd.longitude * metersPerDegreeLon
    val y2 = lineEnd.latitude * metersPerDegreeLat

    val dx = x2 - x1
    val dy = y2 - y1
    if (dx == 0.0 && dy == 0.0) {
        val dpx = x0 - x1
        val dpy = y0 - y1
        return sqrt(dpx * dpx + dpy * dpy)
    }

    val t = ((x0 - x1) * dx + (y0 - y1) * dy) / (dx * dx + dy * dy)
    val clampedT = t.coerceIn(0.0, 1.0)
    val projectedX = x1 + clampedT * dx
    val projectedY = y1 + clampedT * dy
    val distanceX = x0 - projectedX
    val distanceY = y0 - projectedY
    return sqrt(distanceX * distanceX + distanceY * distanceY)
}
