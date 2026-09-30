package com.mototriptracker.app.domain

/**
 * MAP-003/`ADR-023`: [simplifyRoute] is a full, non-incremental recompute - re-running it on every single raw point
 * landing during an active capture (as often as every 2s, `ExperimentLocationProfiles.DEFAULT`) would mean an
 * increasingly expensive full recompute over a growing list for the length of a ride, for no real benefit (a live
 * progress view has no need for sub-second freshness the way the current position does).
 *
 * [buildLiveRoute] throttles that recompute to at most once every [throttlePoints] new raw points, and always
 * appends the exact, unsimplified tail of points added since the last recompute - so the drawn route's *older*
 * portion lags in point-density optimization by at most [throttlePoints] points, but the current/last position
 * ([LiveRouteResult.displayPoints]'s own last element) is always the true latest point, never stale.
 */
data class LiveRouteState(val simplifiedPrefix: List<GeoPoint> = emptyList(), val simplifiedUpToCount: Int = 0)

data class LiveRouteResult(val displayPoints: List<GeoPoint>, val nextState: LiveRouteState)

fun buildLiveRoute(previous: LiveRouteState, allPoints: List<GeoPoint>, throttlePoints: Int = 10): LiveRouteResult {
    if (allPoints.isEmpty()) return LiveRouteResult(emptyList(), previous)

    val shouldResimplify = previous.simplifiedPrefix.isEmpty() || allPoints.size - previous.simplifiedUpToCount >= throttlePoints
    val nextState = if (shouldResimplify) {
        LiveRouteState(simplifiedPrefix = simplifyRoute(allPoints), simplifiedUpToCount = allPoints.size)
    } else {
        previous
    }
    val displayPoints = nextState.simplifiedPrefix + allPoints.drop(nextState.simplifiedUpToCount)
    return LiveRouteResult(displayPoints, nextState)
}
