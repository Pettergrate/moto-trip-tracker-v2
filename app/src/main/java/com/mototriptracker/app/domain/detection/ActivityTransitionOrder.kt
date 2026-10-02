package com.mototriptracker.app.domain.detection

import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.TransitionType

/**
 * DET-009 (`ADR-026`): Android reports a change of label as an EXIT of the old one and an ENTER of the new one with
 * the very same timestamp, EXIT first (read back from the phone: every pair, to the nanosecond). Handed to the
 * detector in that order, the EXIT of `IN_VEHICLE` would end a ride that was only being re-labelled `ON_BICYCLE`.
 * Putting the ENTER first - only between transitions of the *same instant*, everything else stays as delivered -
 * lets the engines see the new label as current before the old one's EXIT arrives.
 *
 * Used wherever transitions are handed to the engines: live by the receiver, and by the stop monitoring that is
 * rebuilt after a restart (`AUTO-002`) so that replaying the record gives the engines the order they would have seen.
 */
fun List<ActivityTransitionSample>.entersFirstAtTheSameInstant(): List<ActivityTransitionSample> {
    val ordered = ArrayList<ActivityTransitionSample>(size)
    var start = 0
    while (start < size) {
        var end = start
        while (end < size && this[end].elapsedRealtimeNanos == this[start].elapsedRealtimeNanos) end++
        val sameInstant = subList(start, end)
        ordered += sameInstant.filter { it.transitionType == TransitionType.ENTER }
        ordered += sameInstant.filter { it.transitionType != TransitionType.ENTER }
        start = end
    }
    return ordered
}
