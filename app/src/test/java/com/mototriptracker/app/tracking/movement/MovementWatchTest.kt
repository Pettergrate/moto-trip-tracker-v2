package com.mototriptracker.app.tracking.movement

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.StartSource
import com.mototriptracker.app.testing.FakeCapabilityInputsProvider
import com.mototriptracker.app.testing.FakeMovementFixSource
import com.mototriptracker.app.testing.FakeMovementWatchRegistration
import com.mototriptracker.app.testing.TestDatabaseFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DET-011 (`ADR-030`): the movement watch in observation mode - when it arms, when it refuses and says why, what it
 * records, and that it records no coordinate and starts nothing.
 */
@RunWith(RobolectricTestRunner::class)
class MovementWatchTest {

    private lateinit var db: MotoTripDatabase
    private val clock = FakeClock(wallMillis = 1_000_000L, elapsedNanos = 1_000_000_000L)
    private val registration = FakeMovementWatchRegistration()
    private val goodFix = MovementFix(latitude = 10.12345, longitude = -84.67891, accuracyM = 20f, ageMs = 5_000L, source = "current")
    private val fixSource = FakeMovementFixSource(goodFix)

    private val everything = CapabilityInputs(
        preciseLocationGranted = true, approximateLocationGranted = true, activityRecognitionGranted = true,
        backgroundLocationGranted = true, notificationsEnabled = true, locationServicesEnabled = true, autoTrackingEnabledByUser = true
    )
    private val provider = FakeCapabilityInputsProvider(everything)

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun watch() = MovementWatch(
        registration = registration,
        fixSource = fixSource,
        capabilityInputsProvider = provider,
        tripCaptureDao = db.tripCaptureDao(),
        diagnosticEventDao = db.diagnosticEventDao(),
        clock = clock,
        idGenerator = FakeIdGenerator(prefix = "watch")
    )

    private suspend fun events(type: String? = null): List<DiagnosticEventEntity> =
        db.diagnosticEventDao().findAll().filter { type == null || it.eventType == type }.sortedBy { it.occurredAt }

    private fun activeCapture() = TripCaptureEntity(
        id = "capture-1", status = CaptureStatus.ACTIVE, startedAt = 1_000L, endedAt = null, startElapsedRealtimeNanos = 1_000L,
        endElapsedRealtimeNanos = null, localTimeZoneId = "UTC", startSource = StartSource.MANUAL, endSource = null,
        detectorVersion = DetectorVersion(0), locationProfileVersion = LocationProfileVersion(0), createdAt = 1_000L, updatedAt = 1_000L
    )

    // --- arming ---

    @Test
    fun armsAroundTheCurrentPositionAndRecordsWhyWithoutAnyCoordinate() = runTest {
        watch().ensureArmed(MovementWatch.REASON_SYNC)

        val armed = registration.armed.single()
        assertEquals(10.12345, armed.latitude, 0.0)
        assertEquals(-84.67891, armed.longitude, 0.0)
        assertEquals(150f, armed.radiusMeters, 0f)
        val event = events(MovementWatch.EVENT_ARMED).single()
        assertEquals(DiagnosticCategory.DETECTOR, event.category)
        assertEquals("SYNC", event.reasonCode)
        assertEquals(mapOf("radiusM" to "150", "fixAccuracyM" to "20", "fixAgeMs" to "5000", "source" to "current"), event.metadata)
        // ADR-009: not the position, not a rounded form of it, anywhere in the event.
        val everythingRecorded = listOf(event.reasonCode, event.stateBefore, event.stateAfter, event.metadata.toString()).joinToString(" ")
        assertTrue(everythingRecorded, listOf("10.12", "84.67", "10.1", "-84").none { everythingRecorded.contains(it) })
    }

    @Test
    fun asksForACheapRecentEnoughPositionAndNeverForALongWait() = runTest {
        watch().ensureArmed(MovementWatch.REASON_SYNC)

        assertEquals(120_000L, fixSource.lastMaxAgeMs)
        assertEquals(8_000L, fixSource.lastTimeoutMs)
    }

    @Test
    fun syncTrueArmsAndSyncFalseRemovesItRecordingTheRemovalOnlyIfItHadArmed() = runTest {
        val watch = watch()

        watch.sync(listening = true)
        watch.sync(listening = false)
        watch.sync(listening = false)

        assertEquals(1, registration.armed.size)
        assertEquals("removal is asked of the platform every time: the geofence outlives the process", 2, registration.disarmCount)
        assertEquals(listOf("SYNC"), events(MovementWatch.EVENT_ARMED).map { it.reasonCode })
        assertEquals(listOf("AUTO_TRACKING_OFF"), events(MovementWatch.EVENT_DISARMED).map { it.reasonCode })
    }

    // --- when it must not run ---

    @Test
    fun withAutoTrackingNotListeningItArmsNothingAndLeavesNoTraceAtAll() = runTest {
        provider.set(everything.copy(autoTrackingEnabledByUser = false))

        watch().ensureArmed(MovementWatch.REASON_STILL)

        assertEquals(emptyList<FakeMovementWatchRegistration.Armed>(), registration.armed)
        assertEquals("off means off (PERM-002): the platform is asked to remove it, and nothing is recorded", 1, registration.disarmCount)
        assertEquals(0, db.diagnosticEventDao().count())
        assertEquals(0, fixSource.requests)
    }

    @Test
    fun eachMissingRequirementIsRecordedAsWhyItCouldNotArmWithoutAskingForAPosition() = runTest {
        val cases = listOf(
            everything.copy(locationServicesEnabled = false) to MovementWatch.REASON_LOCATION_SERVICES_OFF,
            everything.copy(preciseLocationGranted = false) to MovementWatch.REASON_PRECISE_LOCATION_MISSING,
            everything.copy(backgroundLocationGranted = false) to MovementWatch.REASON_BACKGROUND_LOCATION_MISSING
        )
        val watch = watch()
        for ((inputs, reason) in cases) {
            provider.set(inputs)
            watch.ensureArmed(MovementWatch.REASON_STILL)
            val failed = events(MovementWatch.EVENT_ARM_FAILED).last()
            assertEquals(reason, failed.reasonCode)
            assertEquals("STILL", failed.metadata["armReason"])
        }
        assertEquals(emptyList<FakeMovementWatchRegistration.Armed>(), registration.armed)
        assertEquals(0, fixSource.requests)
    }

    @Test
    fun whileACaptureIsActiveItDoesNothingBecauseARideIsAlreadyBeingRecorded() = runTest {
        db.tripCaptureDao().startCaptureIfNoneActive(activeCapture())

        watch().ensureArmed(MovementWatch.REASON_STILL)

        assertEquals(emptyList<FakeMovementWatchRegistration.Armed>(), registration.armed)
        assertEquals(0, fixSource.requests)
        assertEquals(0, db.diagnosticEventDao().count())
    }

    @Test
    fun aCapabilityReadThatFailsArmsNothingNotBeingSureIsNotAReasonToWatch() = runTest {
        provider.throwOnRead = true

        watch().ensureArmed(MovementWatch.REASON_SYNC)

        assertEquals(emptyList<FakeMovementWatchRegistration.Armed>(), registration.armed)
        assertEquals(0, db.diagnosticEventDao().count())
    }

    // --- when the position is not good enough ---

    @Test
    fun noPositionIsRecordedAsNoFixAndNothingIsArmed() = runTest {
        fixSource.fix = null

        watch().ensureArmed(MovementWatch.REASON_SYNC)

        assertEquals("NO_FIX", events(MovementWatch.EVENT_ARM_FAILED).single().reasonCode)
        assertEquals(emptyList<FakeMovementWatchRegistration.Armed>(), registration.armed)
    }

    @Test
    fun aPositionTooPoorToCentreACircleOnIsRecordedAsPoorFixWithItsAccuracy() = runTest {
        fixSource.fix = goodFix.copy(accuracyM = 400f)

        watch().ensureArmed(MovementWatch.REASON_SYNC)

        val failed = events(MovementWatch.EVENT_ARM_FAILED).single()
        assertEquals("POOR_FIX", failed.reasonCode)
        assertEquals("400", failed.metadata["fixAccuracyM"])
        assertEquals(emptyList<FakeMovementWatchRegistration.Armed>(), registration.armed)
    }

    /** Seen on the phone: the platform answered a request for the current position with one 4.8 minutes old. */
    @Test
    fun aPositionOlderThanTheLimitIsRefusedAsStaleEvenWhenTheSourceCalledItCurrent() = runTest {
        fixSource.fix = goodFix.copy(ageMs = 290_059L, source = "current")

        watch().ensureArmed(MovementWatch.REASON_SYNC)

        val failed = events(MovementWatch.EVENT_ARM_FAILED).single()
        assertEquals("STALE_FIX", failed.reasonCode)
        assertEquals("290059", failed.metadata["fixAgeMs"])
        assertEquals(emptyList<FakeMovementWatchRegistration.Armed>(), registration.armed)
    }

    @Test
    fun aPositionExactlyAtTheAgeLimitIsStillUsed() = runTest {
        fixSource.fix = goodFix.copy(ageMs = 120_000L)

        watch().ensureArmed(MovementWatch.REASON_SYNC)

        assertEquals(1, registration.armed.size)
    }

    /** Seen on the phone: opening the app armed twice within the same second. */
    @Test
    fun syncingAgainStraightAwayCostsNoSecondPosition() = runTest {
        val watch = watch()

        watch.sync(listening = true)
        watch.sync(listening = true)

        assertEquals(1, registration.armed.size)
        assertEquals(1, fixSource.requests)
        assertEquals(listOf("SYNC"), events(MovementWatch.EVENT_ARMED).map { it.reasonCode })
    }

    @Test
    fun aSyncAfterTheMinimumIntervalRecentresAgain() = runTest {
        val watch = watch()
        watch.sync(listening = true)
        clock.advanceMillis(130_000L)

        watch.sync(listening = true)

        assertEquals(2, registration.armed.size)
    }

    @Test
    fun aPositionExactlyAtTheAccuracyLimitStillArms() = runTest {
        fixSource.fix = goodFix.copy(accuracyM = 100f)

        watch().ensureArmed(MovementWatch.REASON_SYNC)

        assertEquals(1, registration.armed.size)
    }

    @Test
    fun aPlatformRefusalIsRecordedAsRegistrationFailedAndTheWatchIsNotConsideredArmed() = runTest {
        registration.failArmWith = IllegalStateException("not allowed")
        val watch = watch()

        watch.ensureArmed(MovementWatch.REASON_SYNC)
        watch.sync(listening = false)

        val failed = events(MovementWatch.EVENT_ARM_FAILED).single()
        assertEquals("REGISTRATION_FAILED", failed.reasonCode)
        assertEquals("IllegalStateException", failed.metadata["error"])
        assertEquals("never armed, so nothing to record as removed", 0, events(MovementWatch.EVENT_DISARMED).size)
    }

    // --- the EXIT: the moment a detector built on this would have started ---

    @Test
    fun anExitIsRecordedAsTheMomentAndTheWatchFollowsThePhoneToItsNewPosition() = runTest {
        val watch = watch()
        watch.ensureArmed(MovementWatch.REASON_SYNC)
        clock.advanceMillis(200_000L)
        fixSource.fix = goodFix.copy(latitude = 10.2, longitude = -84.7)

        watch.onExit()

        val exit = events(MovementWatch.EVENT_EXIT).single()
        assertEquals("GEOFENCE_EXIT", exit.reasonCode)
        assertEquals("false", exit.metadata["captureActive"])
        assertEquals(listOf("SYNC", "AFTER_EXIT"), events(MovementWatch.EVENT_ARMED).map { it.reasonCode })
        assertEquals(2, registration.armed.size)
        assertEquals(10.2, registration.armed.last().latitude, 0.0)
    }

    @Test
    fun aRearmRightAfterAnExitIsThrottledSoARideDoesNotCostAFixEveryCircle() = runTest {
        val watch = watch()
        watch.ensureArmed(MovementWatch.REASON_SYNC)
        clock.advanceMillis(30_000L)

        watch.onExit()

        assertEquals("the exit is still recorded", 1, events(MovementWatch.EVENT_EXIT).size)
        assertEquals("but no second circle within the two-minute minimum", 1, registration.armed.size)
    }

    @Test
    fun theThrottleDoesNotApplyToTheOtherReasonsToArm() = runTest {
        val watch = watch()
        watch.ensureArmed(MovementWatch.REASON_SYNC)
        clock.advanceMillis(10_000L)

        watch.ensureArmed(MovementWatch.REASON_STILL)

        assertEquals(2, registration.armed.size)
    }

    @Test
    fun anExitWhileACaptureIsActiveIsRecordedAsSuchAndDoesNotRearm() = runTest {
        val watch = watch()
        watch.ensureArmed(MovementWatch.REASON_SYNC)
        db.tripCaptureDao().startCaptureIfNoneActive(activeCapture())
        clock.advanceMillis(200_000L)

        watch.onExit()

        assertEquals("true", events(MovementWatch.EVENT_EXIT).single().metadata["captureActive"])
        assertEquals(1, registration.armed.size)
    }

    @Test
    fun anExitThatArrivesAfterAutoTrackingWasSwitchedOffIsDroppedNotRecorded() = runTest {
        val watch = watch()
        watch.ensureArmed(MovementWatch.REASON_SYNC)
        provider.set(everything.copy(autoTrackingEnabledByUser = false))

        watch.onExit()

        assertEquals("off means off: no trace of the movement", 0, events(MovementWatch.EVENT_EXIT).size)
        assertTrue("and the platform is asked to remove the circle", registration.disarmCount >= 1)
    }

    // --- a delivery with nothing usable in it must leave a trace ---

    @Test
    fun anEmptyDeliveryWhileListeningIsRecordedWithItsReason() = runTest {
        watch().onEmptyBroadcast("NO_EVENT")

        val event = events(MovementWatch.EVENT_BROADCAST_EMPTY).single()
        assertEquals("NO_EVENT", event.reasonCode)
    }

    @Test
    fun anEmptyDeliveryWhileNotListeningLeavesNoTrace() = runTest {
        provider.set(everything.copy(autoTrackingEnabledByUser = false))

        watch().onEmptyBroadcast("NO_EVENT")

        assertEquals(0, db.diagnosticEventDao().count())
    }
}
