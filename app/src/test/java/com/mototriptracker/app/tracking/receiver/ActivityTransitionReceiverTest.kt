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
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.EndSource
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.core.model.TransitionType
import com.mototriptracker.app.testing.FakeCapabilityInputsProvider
import com.mototriptracker.app.testing.FakeCapabilityProvider
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.activityrecognition.ActivityTransitionBus
import com.mototriptracker.app.tracking.activityrecognition.ActivityTransitionRecorder
import com.mototriptracker.app.tracking.service.TrackingForegroundService
import com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * worth guarding: an intent that carries no such result must never be a crash
 * - this is exactly the shape a stray/malformed broadcast to this receiver would
 * take. (While listening it is now also *recorded*, as an empty-broadcast
 * diagnostic; this test uses a receiver that is not listening, so it stores nothing.)
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

    private fun nextStartedService(): Intent? {
        val application = ApplicationProvider.getApplicationContext<Application>()
        return shadowOf(application).peekNextStartedService()
    }

    private fun nextStartedServiceAction(): String? = nextStartedService()?.action

    /** The last `DETECTOR` diagnostic in the database, or `null` if none was recorded - what `maybeStartAutoDetection` decided and why. */
    private suspend fun latestDecision() =
        db.diagnosticEventDao().findAll().filter { it.category == DiagnosticCategory.DETECTOR }.maxByOrNull { it.occurredAt }

    @Test
    fun startsAutoDetectionOnAnInVehicleEnterWithNoActiveCaptureAndFullAutoCapability() = runTest {
        val receiver = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()))

        receiver.maybeStartAutoDetection(
            ApplicationProvider.getApplicationContext(),
            listOf(sample(ActivityType.IN_VEHICLE, TransitionType.ENTER))
        )

        assertEquals(TrackingForegroundService.ACTION_AUTO_DETECT, nextStartedServiceAction())
        val decision = latestDecision()
        assertEquals(ActivityTransitionRecorder.EVENT_AUTO_DETECTION_STARTED, decision?.eventType)
        assertEquals(ActivityTransitionRecorder.REASON_IN_VEHICLE_ENTER, decision?.reasonCode)
    }

    /**
     * Found on the phone: the transition that starts the service is the one `ActivityTransitionBus` cannot be trusted
     * to deliver (no replay, and the service's own subscription cannot exist yet at this point) - see
     * `ActivityTransitionBusProbeTest`. The receiver hands it over as the intent's seed instead.
     */
    @Test
    fun theTriggeringSampleTravelsAsTheServiceIntentsSeed() = runTest {
        val receiver = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()))

        receiver.maybeStartAutoDetection(
            ApplicationProvider.getApplicationContext(),
            listOf(sample(ActivityType.IN_VEHICLE, TransitionType.ENTER))
        )

        val seed = TrackingForegroundService.seedFromIntent(nextStartedService())
        assertEquals(ActivityType.IN_VEHICLE, seed?.activityType)
        assertEquals(TransitionType.ENTER, seed?.transitionType)
    }

    @Test
    fun doesNotStartAutoDetectionWhenCapabilityModeIsManual() = runTest {
        val receiver = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.cleanInstall()))

        receiver.maybeStartAutoDetection(
            ApplicationProvider.getApplicationContext(),
            listOf(sample(ActivityType.IN_VEHICLE, TransitionType.ENTER))
        )

        assertNull(nextStartedServiceAction())
        val decision = latestDecision()
        assertEquals(ActivityTransitionRecorder.EVENT_AUTO_DETECTION_NOT_STARTED, decision?.eventType)
        assertEquals(ActivityTransitionRecorder.REASON_CAPABILITY_NOT_ELIGIBLE, decision?.reasonCode)
        // FakeCapabilityProvider.cleanInstall() also has preciseLocationGranted=false, which the resolver checks first: LOCATION_DEGRADED.
        assertEquals("LOCATION_DEGRADED", decision?.stateAfter)
    }

    /** Found on the phone: this exact branch, silent, is why two real commutes recorded transitions but started nothing. */
    @Test
    fun whenLocationIsDegradedTheDecisionRecordsTheRealModeNotJustManual() = runTest {
        val inputs = FakeCapabilityProvider.fullAuto().copy(locationServicesEnabled = false)
        val receiver = buildReceiver(FakeCapabilityInputsProvider(inputs))

        receiver.maybeStartAutoDetection(
            ApplicationProvider.getApplicationContext(),
            listOf(sample(ActivityType.IN_VEHICLE, TransitionType.ENTER))
        )

        assertNull(nextStartedServiceAction())
        val decision = latestDecision()
        assertEquals(ActivityTransitionRecorder.REASON_CAPABILITY_NOT_ELIGIBLE, decision?.reasonCode)
        assertEquals("LOCATION_DEGRADED", decision?.stateAfter)
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
        assertEquals(ActivityTransitionRecorder.REASON_CAPTURE_ALREADY_ACTIVE, latestDecision()?.reasonCode)
    }

    /** The receiver must never crash on this - Android 12+'s background-start limits can refuse the service outright. */
    @Test
    fun aRefusedServiceStartIsRecordedRatherThanCrashingTheReceiver() = runTest {
        val receiver = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()))
        val refusingContext = object : android.content.ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun startForegroundService(service: Intent) = throw IllegalStateException("background start not allowed")
        }

        receiver.maybeStartAutoDetection(refusingContext, listOf(sample(ActivityType.IN_VEHICLE, TransitionType.ENTER)))

        val decision = latestDecision()
        assertEquals(ActivityTransitionRecorder.EVENT_AUTO_DETECTION_NOT_STARTED, decision?.eventType)
        assertEquals(ActivityTransitionRecorder.REASON_SERVICE_START_FAILED, decision?.reasonCode)
        assertEquals("IllegalStateException", decision?.stateAfter)
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

    // --- DET-006: post-Finish auto-start suppression ---------------------

    private fun mostRecentlyEndedCapture(endElapsedRealtimeNanos: Long) = activeCapture().copy(
        status = CaptureStatus.COMPLETED,
        endedAt = endElapsedRealtimeNanos / 1_000_000,
        endElapsedRealtimeNanos = endElapsedRealtimeNanos,
        endSource = EndSource.MANUAL
    )

    @Test
    fun doesNotStartAutoDetectionWithinThePostFinishSuppressionWindow() = runTest {
        db.tripCaptureDao().insert(mostRecentlyEndedCapture(endElapsedRealtimeNanos = 1_000L))
        val receiver = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()))
        receiver.clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L) // "now" is right after the Finish

        receiver.maybeStartAutoDetection(
            ApplicationProvider.getApplicationContext(),
            listOf(sample(ActivityType.IN_VEHICLE, TransitionType.ENTER))
        )

        assertNull(
            "a rider still moving right after Finish must not immediately get a new candidate Trip",
            nextStartedServiceAction()
        )
        assertEquals(ActivityTransitionRecorder.REASON_POST_FINISH_SUPPRESSED, latestDecision()?.reasonCode)
    }

    @Test
    fun stillSuppressesWhenAnOlderCaptureFromBeforeARebootHasALargerElapsedClockValue() = runTest {
        // DET-008, found in a real field day: the elapsed-realtime counter restarts at every boot, so a capture from
        // before the last reboot can carry a bigger value than "now". Picking "the most recently ended" by that counter
        // returned that stale row first, the reboot guard read it as "not suppressed", and DET-006 did nothing for the
        // whole boot - two automatic trips started 67 s and 79 s after a Finish.
        db.tripCaptureDao().insert(
            activeCapture().copy(
                id = "from-before-the-reboot",
                status = CaptureStatus.ABORTED,
                startedAt = 500L,
                endedAt = 600L, // long ago on the wall clock...
                endElapsedRealtimeNanos = 900_000_000_000_000L, // ...but a huge value of the previous boot's counter
                endSource = EndSource.RECOVERY
            )
        )
        db.tripCaptureDao().insert(
            activeCapture().copy(
                id = "just-finished",
                status = CaptureStatus.COMPLETED,
                startedAt = 40_000_000L,
                endedAt = 50_000_000L,
                endElapsedRealtimeNanos = 1_000L,
                endSource = EndSource.MANUAL
            )
        )
        val receiver = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()))
        receiver.clock = FakeClock(wallMillis = 51_000_000L, elapsedNanos = 1_000L + 52_000_000_000L) // 52 s after the Finish

        receiver.maybeStartAutoDetection(
            ApplicationProvider.getApplicationContext(),
            listOf(sample(ActivityType.IN_VEHICLE, TransitionType.ENTER))
        )

        assertNull(nextStartedServiceAction())
        assertEquals(ActivityTransitionRecorder.REASON_POST_FINISH_SUPPRESSED, latestDecision()?.reasonCode)
    }

    @Test
    fun doesNotSuppressAfterAnAutomaticFinishBecauseTheRideMayBeContinuing() = runTest {
        // DET-008: the detector now finishes at least a grace period after the vehicle stopped. An IN_VEHICLE ENTER right
        // after such a Finish is the ride going on (Activity Recognition will not emit it again) - suppressing it would
        // lose everything that follows. The window is for a Finish the rider asked for while still moving.
        db.tripCaptureDao().insert(mostRecentlyEndedCapture(endElapsedRealtimeNanos = 1_000L).copy(endSource = EndSource.AUTO))
        val receiver = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()))
        receiver.clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L + 5_000_000_000L) // 5 s after the automatic Finish

        receiver.maybeStartAutoDetection(
            ApplicationProvider.getApplicationContext(),
            listOf(sample(ActivityType.IN_VEHICLE, TransitionType.ENTER))
        )

        assertEquals(TrackingForegroundService.ACTION_AUTO_DETECT, nextStartedServiceAction())
    }

    @Test
    fun startsAutoDetectionOnceThePostFinishSuppressionWindowHasElapsed() = runTest {
        db.tripCaptureDao().insert(mostRecentlyEndedCapture(endElapsedRealtimeNanos = 1_000L))
        val receiver = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()))
        receiver.clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L + 121_000_000_000L) // 121s later

        receiver.maybeStartAutoDetection(
            ApplicationProvider.getApplicationContext(),
            listOf(sample(ActivityType.IN_VEHICLE, TransitionType.ENTER))
        )

        assertEquals(TrackingForegroundService.ACTION_AUTO_DETECT, nextStartedServiceAction())
    }

    // PERM-002: "off" has to mean the app does not keep the movement data, whatever Google's side still delivers.

    private val fullyOn get() = FakeCapabilityProvider.fullAuto()

    private fun kotlinx.coroutines.test.TestScope.collectPublished(receiver: ActivityTransitionReceiver): List<ActivityTransitionSample> {
        val seen = mutableListOf<ActivityTransitionSample>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { receiver.activityTransitionBus.events.toList(seen) }
        return seen
    }

    @Test
    fun whileListeningATransitionIsStoredAndPublished() = runTest {
        val receiver = buildReceiver(FakeCapabilityInputsProvider(fullyOn))
        val published = collectPublished(receiver)

        receiver.handleTransitions(ApplicationProvider.getApplicationContext(), listOf(sample(ActivityType.WALKING, TransitionType.ENTER)))

        assertEquals(1, db.diagnosticEventDao().count())
        assertEquals(1, published.size)
    }

    @Test
    fun withAutoTrackingOffATransitionIsNeitherStoredNorPublished() = runTest {
        val receiver = buildReceiver(FakeCapabilityInputsProvider(fullyOn.copy(autoTrackingEnabledByUser = false)))
        val published = collectPublished(receiver)

        receiver.handleTransitions(ApplicationProvider.getApplicationContext(), listOf(sample(ActivityType.WALKING, TransitionType.ENTER)))

        assertEquals(0, db.diagnosticEventDao().count())
        assertEquals(0, published.size)
    }

    @Test
    fun withAutoTrackingOffEvenAnInVehicleEnterStartsNothing() = runTest {
        val receiver = buildReceiver(FakeCapabilityInputsProvider(fullyOn.copy(autoTrackingEnabledByUser = false)))

        receiver.handleTransitions(ApplicationProvider.getApplicationContext(), listOf(sample(ActivityType.IN_VEHICLE, TransitionType.ENTER)))

        assertNull(nextStartedServiceAction())
    }

    @Test
    fun withoutTheActivityPermissionATransitionIsNotStored() = runTest {
        val receiver = buildReceiver(FakeCapabilityInputsProvider(fullyOn.copy(activityRecognitionGranted = false)))

        receiver.handleTransitions(ApplicationProvider.getApplicationContext(), listOf(sample(ActivityType.STILL, TransitionType.ENTER)))

        assertEquals(0, db.diagnosticEventDao().count())
    }

    /** Not being sure is not a reason to keep collecting. */
    @Test
    fun whenTheCapabilityReadFailsATransitionIsDropped() = runTest {
        val provider = FakeCapabilityInputsProvider(fullyOn).apply { throwOnRead = true }
        val receiver = buildReceiver(provider)

        receiver.handleTransitions(ApplicationProvider.getApplicationContext(), listOf(sample(ActivityType.WALKING, TransitionType.ENTER)))

        assertEquals(0, db.diagnosticEventDao().count())
    }

    @Test
    fun whileListeningAnInVehicleEnterStillStartsAutoDetectionAsBefore() = runTest {
        val receiver = buildReceiver(FakeCapabilityInputsProvider(fullyOn))

        receiver.handleTransitions(ApplicationProvider.getApplicationContext(), listOf(sample(ActivityType.IN_VEHICLE, TransitionType.ENTER)))

        assertEquals(TrackingForegroundService.ACTION_AUTO_DETECT, nextStartedServiceAction())
    }

    // Found on the phone: two weeks of deliveries arrived empty and were discarded without a trace. Reading a broadcast is now
    // tested with a transition serialized the way Play Services sends it, and each way of coming out empty is a named case.

    private fun intentWith(vararg events: ActivityTransitionEvent) = Intent(ActivityTransitionReceiver.ACTION_ACTIVITY_TRANSITION).also {
        SafeParcelableSerializer.serializeToIntentExtra(ActivityTransitionResult(events.toList()), it, "com.google.android.location.internal.EXTRA_ACTIVITY_TRANSITION_RESULT")
    }

    @Test
    fun aBroadcastCarryingATransitionIsReadIntoASample() {
        val receiver = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()))
        val intent = intentWith(ActivityTransitionEvent(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER, 5_000_000L))

        val samples = receiver.samplesFrom(intent)

        assertEquals(1, samples.size)
        assertEquals(ActivityType.IN_VEHICLE, samples.single().activityType)
        assertEquals(TransitionType.ENTER, samples.single().transitionType)
    }

    @Test
    fun severalTransitionsInOneBroadcastAreAllRead() {
        val receiver = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()))
        val intent = intentWith(
            ActivityTransitionEvent(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_EXIT, 1_000L),
            ActivityTransitionEvent(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER, 2_000L)
        )

        assertEquals(2, receiver.samplesFrom(intent).size)
    }

    /** This is what an immutable PendingIntent produced: the broadcast arrives, the transition is missing. It must come out empty - and, now, say so in the log. */
    @Test
    fun aBroadcastWithoutTheTransitionExtraComesOutEmpty() {
        val receiver = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()))

        assertEquals(emptyList<ActivityTransitionSample>(), receiver.samplesFrom(Intent(ActivityTransitionReceiver.ACTION_ACTIVITY_TRANSITION)))
    }

    @Test
    fun aResultWithNoEventsComesOutEmpty() {
        val receiver = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()))

        assertEquals(emptyList<ActivityTransitionSample>(), receiver.samplesFrom(intentWith()))
    }

    // What a broadcast held is now also recorded, durably, while listening: the log the phone keeps is gone in minutes, and
    // "Google never called" versus "Google called with nothing" is the distinction that had to be dug out by hand.

    private fun receiver() = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()))

    @Test
    fun aBroadcastWithoutTheTransitionExtraIsReadAsNoResult() {
        val reading = receiver().readBroadcast(Intent(ActivityTransitionReceiver.ACTION_ACTIVITY_TRANSITION))

        assertEquals(ActivityTransitionRecorder.EMPTY_NO_RESULT, reading.emptyReason)
        assertEquals(0, reading.samples.size)
    }

    @Test
    fun aResultWithNoEventsIsReadAsNoEvents() {
        assertEquals(ActivityTransitionRecorder.EMPTY_NO_EVENTS, receiver().readBroadcast(intentWith()).emptyReason)
    }

    @Test
    fun aBroadcastWithATransitionHasNoEmptyReason() {
        val reading = receiver().readBroadcast(
            intentWith(ActivityTransitionEvent(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER, 1_000L))
        )

        assertEquals(1, reading.samples.size)
        assertNull(reading.emptyReason)
    }

    @Test
    fun anEmptyBroadcastWhileListeningIsRecordedWithItsReasonAndNothingElse() = runTest {
        receiver().handleEmptyBroadcast(ActivityTransitionRecorder.EMPTY_NO_RESULT)

        val event = db.diagnosticEventDao().findAll().single()
        assertEquals("ACTIVITY_BROADCAST_EMPTY", event.eventType)
        assertEquals(ActivityTransitionRecorder.EMPTY_NO_RESULT, event.reasonCode)
        assertNull("no activity, nothing that describes movement", event.stateAfter)
        assertEquals(emptyMap<String, String>(), event.metadata)
    }

    /** Off has to mean the app keeps no trace of movement: not even that a broadcast arrived. */
    @Test
    fun anEmptyBroadcastWhileNotListeningLeavesNoTraceAtAll() = runTest {
        val off = buildReceiver(FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto().copy(autoTrackingEnabledByUser = false)))

        off.handleEmptyBroadcast(ActivityTransitionRecorder.EMPTY_NO_RESULT)

        assertEquals(0, db.diagnosticEventDao().count())
    }

    @Test
    fun whenTheCapabilityReadFailsAnEmptyBroadcastIsNotRecordedEither() = runTest {
        val provider = FakeCapabilityInputsProvider(FakeCapabilityProvider.fullAuto()).apply { throwOnRead = true }

        buildReceiver(provider).handleEmptyBroadcast(ActivityTransitionRecorder.EMPTY_NO_RESULT)

        assertEquals(0, db.diagnosticEventDao().count())
    }
}
