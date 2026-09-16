package com.mototriptracker.app.testing

import com.mototriptracker.app.core.model.ActivityTransitionSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/**
 * F0.12 §5.3. Replays a fixed, ordered [ActivityTransitionSample] sequence
 * without needing Google Play services on the test JVM. Same no-real-delay,
 * no-interpretation discipline as [LocationReplaySource].
 */
class ActivityReplaySource(private val samples: List<ActivityTransitionSample>) {
    fun replay(): Flow<ActivityTransitionSample> = samples.asFlow()
}
