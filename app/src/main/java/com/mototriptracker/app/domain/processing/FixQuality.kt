package com.mototriptracker.app.domain.processing

/**
 * PRC-004 (`ADR-029`): when a fix's own reported accuracy says it is not a position worth drawing a route through.
 *
 * Found on the owner's phone (2026-10-02): entering a covered place (a garage) the GPS was lost and, for about two and
 * a half minutes, the fused provider kept delivering network-based positions with a reported accuracy of 78 to 400 m
 * that jump 125 to 396 m from one fix to the next. `ProcessingEngine` v0 accepted every point, so the ride's route ended
 * in a scribble and its distance was 3.1 km too long (14.1 km summed over all points, 11.0 km over the usable ones).
 *
 * The limit is read from the field data, not invented (ADR-018 still applies: it is a placeholder like every
 * threshold, revisited with `EXP-008`): of 13,101 stored points in 58 captures, 97.6 % report 20 m or better, 240 report
 * 20-50 m, exactly one reports 50-75 m, and then come 74 points reporting 78 m or worse (mostly 100-400 m) - the network fixes. A limit
 * anywhere in the empty 50-75 m stretch separates the two groups cleanly; 50 m is the round number inside it.
 *
 * The raw point is never touched (ADR-006) - it is only not used as evidence of where the rider went, the same way a
 * fix taken with only approximate location allowed is not (`ADR-022`).
 */
object FixQuality {
    const val MAX_USABLE_HORIZONTAL_ACCURACY_M = 50f

    fun isUsablePosition(horizontalAccuracyM: Float): Boolean = horizontalAccuracyM <= MAX_USABLE_HORIZONTAL_ACCURACY_M
}
