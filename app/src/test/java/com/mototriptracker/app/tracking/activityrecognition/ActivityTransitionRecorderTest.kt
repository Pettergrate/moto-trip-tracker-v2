package com.mototriptracker.app.tracking.activityrecognition

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.TransitionType
import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.testing.TestDatabaseFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActivityTransitionRecorderTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var recorder: ActivityTransitionRecorder

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        recorder = ActivityTransitionRecorder(
            diagnosticEventDao = db.diagnosticEventDao(),
            clock = FakeClock(wallMillis = 5_000L, elapsedNanos = 5_000L),
            idGenerator = FakeIdGenerator(prefix = "event")
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun recordsATransitionAsAnActivityRecognitionDiagnosticEventNotTiedToAnyCaptureOrTrip() = runTest {
        recorder.record(
            ActivityTransitionSample(
                activityType = ActivityType.IN_VEHICLE,
                transitionType = TransitionType.ENTER,
                elapsedRealtimeNanos = 123_000_000_000L,
                wallTimeEpochMs = 1_000L,
                source = "activity-transition-receiver"
            )
        )

        val event = db.diagnosticEventDao().findAll().single()
        assertEquals(DiagnosticCategory.ACTIVITY_RECOGNITION, event.category)
        assertEquals("ACTIVITY_TRANSITION", event.eventType)
        assertEquals("IN_VEHICLE", event.stateAfter)
        assertEquals("ENTER", event.reasonCode)
        assertEquals(123_000_000_000L, event.elapsedRealtimeNanos)
        assertNull("passive monitoring has no active capture/trip yet", event.captureId)
        assertNull(event.tripId)
    }

    @Test
    fun confidenceIsRecordedInMetadataWhenThePlatformProvidesIt() = runTest {
        recorder.record(
            ActivityTransitionSample(
                activityType = ActivityType.STILL,
                transitionType = TransitionType.EXIT,
                elapsedRealtimeNanos = 0L,
                wallTimeEpochMs = 0L,
                source = "activity-transition-receiver",
                confidence = 87
            )
        )

        val event = db.diagnosticEventDao().findAll().single()
        assertEquals("87", event.metadata["confidence"])
    }

    @Test
    fun metadataIsEmptyRatherThanFabricatedWhenNoConfidenceIsProvided() = runTest {
        recorder.record(
            ActivityTransitionSample(
                activityType = ActivityType.WALKING,
                transitionType = TransitionType.ENTER,
                elapsedRealtimeNanos = 0L,
                wallTimeEpochMs = 0L,
                source = "activity-transition-receiver",
                confidence = null
            )
        )

        val event = db.diagnosticEventDao().findAll().single()
        assertEquals(emptyMap<String, String>(), event.metadata)
    }
}
