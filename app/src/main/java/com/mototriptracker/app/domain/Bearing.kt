package com.mototriptracker.app.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * MAP-006/`ADR-024`: the standard initial-bearing (great-circle) formula, in degrees clockwise from north (`0..360`).
 * Used to rotate the map's vehicle-icon marker to face the direction of travel, derived from whatever two points are
 * already at hand (the route's own last two points) rather than a stored per-point bearing column - `ADR-024`'s own
 * reasoning for not adding a second schema surface for a presentation-only detail.
 */
fun bearingDegrees(from: GeoPoint, to: GeoPoint): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val deltaLon = Math.toRadians(to.longitude - from.longitude)

    val y = sin(deltaLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
    val bearing = Math.toDegrees(atan2(y, x))
    return (bearing + 360.0) % 360.0
}
