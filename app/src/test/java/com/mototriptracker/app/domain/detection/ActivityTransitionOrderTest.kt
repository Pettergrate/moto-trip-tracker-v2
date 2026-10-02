package com.mototriptracker.app.domain.detection

import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityTransitionOrderTest {

    private fun sample(type: ActivityType, transition: TransitionType, elapsedNanos: Long) = ActivityTransitionSample(
        activityType = type,
        transitionType = transition,
        elapsedRealtimeNanos = elapsedNanos,
        wallTimeEpochMs = elapsedNanos / 1_000_000,
        source = "test"
    )

    @Test
    fun onlyTransitionsOfTheSameInstantAreReorderedAndEverythingElseStaysAsDelivered() {
        val delivered = listOf(
            sample(ActivityType.STILL, TransitionType.EXIT, elapsedNanos = 1_000L),
            sample(ActivityType.IN_VEHICLE, TransitionType.ENTER, elapsedNanos = 1_000L),
            sample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 9_000L),
            sample(ActivityType.ON_BICYCLE, TransitionType.ENTER, elapsedNanos = 9_000L),
            sample(ActivityType.WALKING, TransitionType.ENTER, elapsedNanos = 4_000L)
        )

        val ordered = delivered.entersFirstAtTheSameInstant()

        assertEquals(
            listOf(
                ActivityType.IN_VEHICLE to TransitionType.ENTER,
                ActivityType.STILL to TransitionType.EXIT,
                ActivityType.ON_BICYCLE to TransitionType.ENTER,
                ActivityType.IN_VEHICLE to TransitionType.EXIT,
                ActivityType.WALKING to TransitionType.ENTER
            ),
            ordered.map { it.activityType to it.transitionType }
        )
    }

    @Test
    fun anEmptyListAndASingleTransitionComeBackUnchanged() {
        assertEquals(emptyList<ActivityTransitionSample>(), emptyList<ActivityTransitionSample>().entersFirstAtTheSameInstant())
        val single = listOf(sample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 5L))
        assertEquals(single, single.entersFirstAtTheSameInstant())
    }

    @Test
    fun entersAndExitsAtDifferentInstantsKeepTheirDeliveredOrder() {
        val delivered = listOf(
            sample(ActivityType.IN_VEHICLE, TransitionType.EXIT, elapsedNanos = 1L),
            sample(ActivityType.ON_BICYCLE, TransitionType.ENTER, elapsedNanos = 2L)
        )

        assertEquals(delivered, delivered.entersFirstAtTheSameInstant())
    }
}
