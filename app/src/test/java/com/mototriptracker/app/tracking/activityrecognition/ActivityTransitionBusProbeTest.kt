package com.mototriptracker.app.tracking.activityrecognition

import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.TransitionType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DET-001 / AUTO-001 follow-up (2026-09-29): documents, permanently, the exact `SharedFlow` characteristic that made
 * every real cold start lose its triggering transition - found on the phone (two real commutes classified correctly,
 * neither one started a trip) and reproduced with the app's own debug trigger. `ActivityTransitionBus` has no replay
 * (by design: a *live* signal, not a delivery-guaranteed queue, per its own KDoc), so an emission with zero current
 * subscribers is gone for good - which is exactly `ActivityTransitionReceiver`'s own sequence: it emits onto the bus
 * *before* `context.startForegroundService(...)` even schedules the coordinator's subscription.
 *
 * This is not itself the fix - the bus's semantics are correct for what it is elsewhere in the app - it is the reason
 * the fix lives where it does: the receiver hands the triggering sample to the service directly (as the intent's
 * seed, `TrackingForegroundService.createAutoDetectIntent`/`seedFromIntent`), so it is never lost to this gap. If this
 * test ever starts asserting `[ENTER, EXIT]`, the bus's behaviour changed and the seed-passing fix should be revisited
 * (it may have become redundant, or the two could now interact in a new way worth checking).
 */
class ActivityTransitionBusProbeTest {

    private fun sample(type: TransitionType) = ActivityTransitionSample(
        activityType = ActivityType.IN_VEHICLE, transitionType = type, elapsedRealtimeNanos = 0L, wallTimeEpochMs = 0L, source = "probe"
    )

    @Test
    fun anEmissionWithNoSubscriberYetPresentIsLostToALaterOne() = runTest {
        val bus = ActivityTransitionBus()
        bus.emit(sample(TransitionType.ENTER)) // zero subscribers right now - the receiver's own situation

        val seen = mutableListOf<ActivityTransitionSample>()
        val job = launch { bus.events.collect { seen.add(it) } }
        delay(50)
        bus.emit(sample(TransitionType.EXIT)) // this one has a live subscriber, and is seen
        delay(50)
        job.cancel()

        assertEquals("only the emission with a live subscriber survives", listOf(TransitionType.EXIT), seen.map { it.transitionType })
    }
}
