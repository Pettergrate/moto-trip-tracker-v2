package com.mototriptracker.app.tracking.receiver

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.common.AndroidDispatcherProvider
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.model.ActivityTransitionSample
import com.mototriptracker.app.core.model.ActivityType
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.core.model.TransitionType
import com.mototriptracker.app.testing.FakeCapabilityInputsProvider
import com.mototriptracker.app.testing.FakeCapabilityProvider
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.activityrecognition.ActivityTransitionBus
import com.mototriptracker.app.tracking.activityrecognition.ActivityTransitionRecorder
import com.mototriptracker.app.tracking.service.TrackingForegroundService
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A real broadcast from Play Services carries an internal-only serialized
 * `ActivityTransitionResult` extra that isn't practical to fabricate in a
 * unit test (unlike `FusedLocationGateway`, whose real delivery is likewise
 * verified on-device, not deeply unit tested here). What *is* testable and
 * worth guarding: an intent that carries no such result must be a silent
 * no-op, never a crash - this is exactly the shape a stray/malformed
 * broadcast to this receiver would take.
 */
@RunWith(RobolectricTestRunner::class)
class ActivityTransitionReceiverTest {

    private lateinit var db: MotoTripDatabase

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun anIntentWithNoActivityTransitionResultIsANoOp() = runTest {
        val receiver = ActivityTransitionReceiver()
        receiver.recorder = ActivityTransitionRecorder(
            diagnosticEventDao = db.diagnosticEventDao(),
            clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L),
            idGenerator = FakeIdGenerator(prefix = "event")
        )
        receiver.clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L)
        receiver.dispatchers = AndroidDispatcherProvider()

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        receiver.onReceive(context, Intent(ActivityTransitionReceiver.ACTION_ACTIVITY_TRANSITION))

        assertEquals(0, db.diagnosticEventDao().count())
    }

    private fun sample(type: ActivityType, transition: TransitionType) = ActivityTransitionSample(
        activityType = type,
        transitionType = transition,
        elapsedRealtimeNanos = 1_000L,
        wallTimeEpochMs = 1_000L,
        source = "test"
    )

    private fun activeCapture() = TripCaptureEntity(
        id = "capture-1",
        status = CaptureStatus.ACTIVE,
        startedAt = 1_000L,
        endedAt = null,
        startElapsedRealtimeNanos = 1_000L,
        endElapsedRealtimeNanos = null,
        localTimeZoneId = "UTC",
        startSource = StartSource.MANUAL,
        endSource = null,
        detectorVersion = DetectorVersion(0),
        locationProfileVersion = LocationProfileVersion(0),
        createdAt = 1_000L,
        updatedAt = 1_000L
    )

    private fun buildReceiver(capabilityInputsProvider: FakeCapabilityInputsProvider) = ActivityTransitionReceiver().apply {
        recorder = ActivityTransitionRecorder(
            diagnosticEventDao = db.diagnosticEventDao(),
            clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L),
            idGenerator = FakeIdGenerator(prefix = "event")
        )
        clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L)
        dispatchers = AndroidDispatcherProvider()
        activityTransitionBus = ActivityTransitionBus()
        tripCaptureDao = db.tripCaptureDao()
        this.capabilityInputsProvider = capabilityInputsProvider
    }

    private fun nextStartedServiceAction(): String? {
        val application = ApplicationProvider.getApplicationContext<Application>()
        return shadowOf(application).peekNextStartedService()?.action
    }

    @Test
    fun startsAutoDetectionOnAnInVehicleEnterWithNoActiveCaptureAndFullAutoCapability() = runTest {
        val receiver = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()))

        receiver.maybeStartAutoDetection(
            ApplicationProvider.getApplicationContext(),
            listOf(sample(ActivityType.IN_VEHICLE, TransitionType.ENTER))
        )

        assertEquals(TrackingForegroundService.ACTION_AUTO_DETECT, nextStartedServiceAction())
    }

    @Test
    fun doesNotStartAutoDetectionWhenCapabilityModeIsManual() = runTest {
        val receiver = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.cleanInstall()))

        receiver.maybeStartAutoDetection(
            ApplicationProvider.getApplicationContext(),
            listOf(sample(ActivityType.IN_VEHICLE, TransitionType.ENTER))
        )

        assertNull(nextStartedServiceAction())
    }

    @Test
    fun doesNotStartAutoDetectionWhenACaptureIsAlreadyActive() = runTest {
        db.tripCaptureDao().startCaptureIfNoneActive(activeCapture())
        val receiver = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()))

        receiver.maybeStartAutoDetection(
            ApplicationProvider.getApplicationContext(),
            listOf(sample(ActivityType.IN_VEHICLE, TransitionType.ENTER))
        )

        assertNull(nextStartedServiceAction())
    }

    @Test
    fun doesNotStartAutoDetectionForATransitionThatIsNotAnInVehicleEnter() = runTest {
        val receiver = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()))

        receiver.maybeStartAutoDetection(
            ApplicationProvider.getApplicationContext(),
            listOf(sample(ActivityType.IN_VEHICLE, TransitionType.EXIT))
        )

        assertNull(nextStartedServiceAction())
    }

    @Test
    fun startsAutoDetectionForAssistedAutoCapabilityToo() = runTest {
        val assistedAutoInputs = FakeCapabilityProvider.fullAuto().copy(
            backgroundLocationGranted = false,
            notificationsEnabled = false
        )
        val receiver = buildReceiver(FakeCapabilityInputsProvider(assistedAutoInputs))

        receiver.maybeStartAutoDetection(
            ApplicationProvider.getApplicationContext(),
            listOf(sample(ActivityType.IN_VEHICLE, TransitionType.ENTER))
        )

        assertNotNull(nextStartedServiceAction())
    }
}
