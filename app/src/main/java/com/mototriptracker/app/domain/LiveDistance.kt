package com.mototriptracker.app.domain

import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.domain.processing.FixQuality

/**
 * UI-001: a live, best-effort route length for an in-progress capture -
 * straight consecutive-point haversine summation, no gap-boundary exclusion.
 * Deliberately not the authoritative distance:
 * PRC-001/PRC-002's real pipeline (assessment, gap detection) only runs once
 * the capture finishes. This exists purely so Home/Active Trip can show an
 * honest in-progress estimate instead of nothing, per F0.9's wireframes. It skips the fixes the real
 * pipeline would reject for a known cause ([isRoutePoint]): approximate-only ones (see
 * [RawTrackPointEntity.isApproximateLocation]) and, since PRC-004, ones whose own accuracy is too poor to be a position.
 */
fun liveDistanceMeters(rawPoints: List<RawTrackPointEntity>): Double {
    // REC-005 follow-up: fixes taken with only approximate location allowed are ~2 km blocks; summing
    // the jumps between them would show a kilometre of travel for a phone that has not moved. PRC-004: the same
    // for the network fixes a lost GPS falls back to (78-400 m accuracy, jumping hundreds of metres).
    val points = rawPoints.filter { it.isRoutePoint() }
    var total = 0.0
    for (i in 1 until points.size) {
        val previous = points[i - 1]
        val current = points[i]
        total += haversineMeters(previous.latitude, previous.longitude, current.latitude, current.longitude)
    }
    return total
}

/**
 * PRC-004: whether a stored fix is evidence of where the rider went, by what is known about it when it is received
 * (the same two causes `ProcessingEngine` rejects for): not taken with only approximate location allowed, and not
 * reporting an accuracy worse than [FixQuality]'s limit. Raw points stay either way (ADR-006).
 */
fun RawTrackPointEntity.isRoutePoint(): Boolean =
    isApproximateLocation != true && FixQuality.isUsablePosition(horizontalAccuracyM)
