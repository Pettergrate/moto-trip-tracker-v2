package com.mototriptracker.app.domain

import com.mototriptracker.app.core.database.entity.RawTrackPointEntity

/**
 * UI-001: a live, best-effort route length for an in-progress capture -
 * straight consecutive-point haversine summation, no gap-boundary exclusion
 * or point-quality filtering. Deliberately not the authoritative distance:
 * PRC-001/PRC-002's real pipeline (assessment, gap detection) only runs once
 * the capture finishes. This exists purely so Home/Active Trip can show an
 * honest in-progress estimate instead of nothing, per F0.9's wireframes. It does skip fixes marked
 * approximate-only (see [RawTrackPointEntity.isApproximateLocation]) - a known-bad input, not a quality filter.
 */
fun liveDistanceMeters(rawPoints: List<RawTrackPointEntity>): Double {
    // REC-005 follow-up: fixes taken with only approximate location allowed are ~2 km blocks; summing
    // the jumps between them would show a kilometre of travel for a phone that has not moved.
    val points = rawPoints.filter { it.isApproximateLocation != true }
    var total = 0.0
    for (i in 1 until points.size) {
        val previous = points[i - 1]
        val current = points[i]
        total += haversineMeters(previous.latitude, previous.longitude, current.latitude, current.longitude)
    }
    return total
}
