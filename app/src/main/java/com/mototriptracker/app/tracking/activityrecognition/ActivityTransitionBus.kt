package com.mototriptracker.app.tracking.activityrecognition

import com.mototriptracker.app.core.model.ActivityTransitionSample
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AUTO-001: lets [com.mototriptracker.app.tracking.receiver.ActivityTransitionReceiver]
 * (invoked fresh per broadcast, no state of its own) hand live transitions to
 * whatever is currently running `TrackingSessionCoordinator.runAutoDetection`
 * inside `TrackingForegroundService` - a `@Singleton` so both sides share the
 * one instance for the whole process lifetime.
 *
 * `DROP_OLDEST` + non-suspending [emit]: a transition is a live signal, not a
 * queue to guarantee delivery of - if nothing is currently collecting (no
 * auto-detection running), there is nothing useful to buffer for, and the
 * receiver's `goAsync()`-bounded coroutine must never risk suspending on a
 * full buffer.
 */
@Singleton
class ActivityTransitionBus @Inject constructor() {
    private val _events = MutableSharedFlow<ActivityTransitionSample>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<ActivityTransitionSample> = _events.asSharedFlow()

    /** Test-only synchronization hook - lets a test wait for `runAutoDetection`'s collector to actually attach before emitting. */
    val subscriptionCount: StateFlow<Int> get() = _events.subscriptionCount

    fun emit(sample: ActivityTransitionSample) {
        _events.tryEmit(sample)
    }
}
