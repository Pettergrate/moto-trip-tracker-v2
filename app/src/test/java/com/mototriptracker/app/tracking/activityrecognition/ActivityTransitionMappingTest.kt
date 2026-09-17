package com.mototriptracker.app.tracking.activityrecognition

import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityTransitionMappingTest {

    @Test
    fun mapsEveryF0dot4NamedActivityTypeToItsDomainCounterpart() {
        assertEquals(ActivityType.IN_VEHICLE, mapActivityType(DetectedActivity.IN_VEHICLE))
        assertEquals(ActivityType.ON_FOOT, mapActivityType(DetectedActivity.ON_FOOT))
        assertEquals(ActivityType.WALKING, mapActivityType(DetectedActivity.WALKING))
        assertEquals(ActivityType.RUNNING, mapActivityType(DetectedActivity.RUNNING))
        assertEquals(ActivityType.ON_BICYCLE, mapActivityType(DetectedActivity.ON_BICYCLE))
        assertEquals(ActivityType.STILL, mapActivityType(DetectedActivity.STILL))
    }

    @Test
    fun unrecognizedGmsActivityTypeMapsToUnknownRatherThanCrashing() {
        assertEquals(ActivityType.UNKNOWN, mapActivityType(DetectedActivity.TILTING))
        assertEquals(ActivityType.UNKNOWN, mapActivityType(-1))
    }

    @Test
    fun mapsEnterAndExitTransitionTypes() {
        assertEquals(TransitionType.ENTER, mapTransitionType(ActivityTransition.ACTIVITY_TRANSITION_ENTER))
        assertEquals(TransitionType.EXIT, mapTransitionType(ActivityTransition.ACTIVITY_TRANSITION_EXIT))
    }

    @Test
    fun unrecognizedTransitionTypeMapsToNullRatherThanFabricatingOne() {
        assertNull(mapTransitionType(-1))
    }

    @Test
    fun reconstructsWallTimeFromASharedNowReferencePair() {
        // "Now" is wall=100_000ms / elapsed=50_000_000_000ns. An event that
        // happened 5s (in elapsed terms) before now must resolve to wall
        // time 95_000ms - not to whatever the wall clock reads at call time.
        val eventElapsedNanos = 45_000_000_000L
        val result = reconstructWallTimeEpochMs(
            nowWallMillis = 100_000L,
            nowElapsedRealtimeNanos = 50_000_000_000L,
            eventElapsedRealtimeNanos = eventElapsedNanos
        )
        assertEquals(95_000L, result)
    }

    @Test
    fun reconstructedWallTimeEqualsNowWhenTheEventIsTheMostRecentInstant() {
        val result = reconstructWallTimeEpochMs(
            nowWallMillis = 100_000L,
            nowElapsedRealtimeNanos = 50_000_000_000L,
            eventElapsedRealtimeNanos = 50_000_000_000L
        )
        assertEquals(100_000L, result)
    }
}
