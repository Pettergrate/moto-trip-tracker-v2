package com.mototriptracker.app.domain.detection

import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.LocationSample

/**
 * The merged, chronologically-ordered input [CandidateStartEngine] (and
 * later `DET-003`'s Candidate Stop engine) consumes — DET-001's
 * [ActivityTransitionSample] and TRK-002's [LocationSample] interleaved as
 * they actually arrive in real time.
 */
sealed interface DetectionEvent {
    data class Activity(val sample: ActivityTransitionSample) : DetectionEvent
    data class Location(val sample: LocationSample) : DetectionEvent

    /**
     * Lets a caller force a stale-candidate check even when no new sample
     * has arrived — a candidate that stalls (no further location fixes)
     * must still be abandonable per F0.3 §5's "avoid creating visible
     * history for candidates that disappear", not wait forever for a
     * sample that may never come.
     */
    data class TimeTick(val nowElapsedRealtimeNanos: Long) : DetectionEvent
}
