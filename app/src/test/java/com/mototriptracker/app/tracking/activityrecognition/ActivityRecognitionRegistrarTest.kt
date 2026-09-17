package com.mototriptracker.app.tracking.activityrecognition

import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityRecognitionRegistrarTest {

    @Test
    fun buildsBothEnterAndExitTransitionsForEveryF0dot4NamedActivityType() {
        val transitions = ActivityRecognitionRegistrar.buildTransitions()

        assertEquals(12, transitions.size)
        val expectedActivityTypes = listOf(
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_FOOT,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.STILL
        )
        for (activityType in expectedActivityTypes) {
            assertTrue(
                "expected an ENTER transition for activity type $activityType",
                transitions.any { it.activityType == activityType && it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER }
            )
            assertTrue(
                "expected an EXIT transition for activity type $activityType",
                transitions.any { it.activityType == activityType && it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT }
            )
        }
    }
}
