package com.mototriptracker.app.tracking.coordinator

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.testing.FakeProcessingScheduler
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator.LocationSignalReport
import com.mototriptracker.app.tracking.location.LocationGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/**
 * REC-005 follow-up, found on the real phone: with only approximate location allowed, a stationary
 * phone recorded a 2000 m fix ~920 m away and the distance climbed to 1,9 km, with the rider told
 * only "No GPS signal". The fix is kept (ADR-006) but marked, is not signal, is not fed to the
 * movement heuristics, and the rider is told the real cause.
 */
@RunWith(RobolectricTestRunner::class)
class ApproximateLocationRecordingTest {

    private class ChannelLocationGateway : LocationGateway {
        val channel = Channel<LocationSample>(Channel.UNLIMITED)
        override fun locationUpdates(): Flow<LocationSample> = channel.receiveAsFlow()
    }

    private lateinit var db: MotoTripDatabase
    private lateinit var clock: FakeClock
    private lateinit var gateway: ChannelLocationGateway
    private lateinit var coordinator: TrackingSessionCoordinator
    private val reports: MutableList<LocationSignalReport> = Collections.synchronizedList(mutableListOf())

    /** Flipped by a test the way the phone flips it when the permission is revoked or granted. */
    @Volatile private var precise = true

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        clock = FakeClock(wallMillis = 1_000_000L, elapsedNanos = 10_000_000_000L)
        gateway = ChannelLocationGateway()
        coordinator = TrackingSessionCoordinator(
            database = db, tripCaptureDao = db.tripCaptureDao(), diagnosticEventDao = db.diagnosticEventDao(),
            rawTrackPointDao = db.rawTrackPointDao(), captureEventDao = db.captureEventDao(), tripDao = db.tripDao(),
            tripPartDao = db.tripPartDao(), manualPauseIntervalDao = db.manualPauseIntervalDao(),
            locationGateway = gateway, processingScheduler = FakeProcessingScheduler(), clock = clock,
            idGenerator = FakeIdGenerator(prefix = "id")
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun fixNow(accuracy: Float = 5.0f) = LocationSample(
        wallTimeEpochMs = clock.wallClockMillis(), elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
        receivedAtElapsedRealtimeNanos = clock.elapsedRealtimeNanos(), latitude = 10.0, longitude = -20.0,
        horizontalAccuracyM = accuracy, requestProfileId = "test-profile"
    )

    private suspend fun awaitTrue(what: String, condition: suspend () -> Boolean) {
        try {
            withTimeout(5_000) { while (!condition()) delay(10) }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            val seen = db.diagnosticEventDao().findAll().joinToString { "${it.eventType}(${it.reasonCode})" }
            throw AssertionError("timed out waiting for: $what; events=[$seen]; reports=${reports.toList()}")
        }
    }

    private suspend fun events(type: String) = db.diagnosticEventDao().findAll().filter { it.eventType == type }

    private suspend fun points(captureId: String) = db.rawTrackPointDao().findAllByCapture(captureId)

    private fun kotlinx.coroutines.CoroutineScope.record(captureId: String): Job = launch(Dispatchers.Default) {
        coordinator.recordLocationUpdates(
            captureId,
            preciseLocationGranted = { precise },
            onLocationSignalChanged = { reports += it },
            tickIntervalMs = 20L
        )
    }

    private suspend fun startCapture() =
        (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId

    @Test
    fun pointsRecordedWithPreciseLocationAreMarkedNotApproximateNotLeftUnknown() = runBlocking {
        val captureId = startCapture()
        val job = record(captureId)
        gateway.channel.send(fixNow())
        awaitTrue("point stored") { points(captureId).size == 1 }

        assertEquals("known at receipt, so false - null is only for what predates the marker", false, points(captureId).single().isApproximateLocation)
        assertTrue("healthy start: no accuracy events", events(TrackingSessionCoordinator.EVENT_LOCATION_ACCURACY_DEGRADED).isEmpty())
        job.cancelAndJoin()
    }

    @Test
    fun anApproximateFixIsKeptAndMarkedAndTheRiderIsToldThePermissionIsTheCause() = runBlocking {
        val captureId = startCapture()
        val job = record(captureId)
        gateway.channel.send(fixNow())
        awaitTrue("precise point stored") { points(captureId).size == 1 }

        precise = false // the permission is revoked; the sticky restart comes back like this
        clock.advanceMillis(2_000L)
        gateway.channel.send(fixNow(accuracy = 2000f))
        awaitTrue("approximate point stored") { points(captureId).size == 2 }

        assertEquals("ADR-006: the raw fix is kept, only marked", true, points(captureId).last().isApproximateLocation)
        assertEquals(2000f, points(captureId).last().horizontalAccuracyM)
        val degraded = events(TrackingSessionCoordinator.EVENT_LOCATION_ACCURACY_DEGRADED).single()
        assertEquals(TrackingSessionCoordinator.REASON_PRECISE_LOCATION_MISSING, degraded.reasonCode)
        assertEquals(listOf(LocationSignalReport.APPROXIMATE_ONLY), reports.toList())
        assertEquals("the trip keeps recording", CaptureStatus.ACTIVE, db.tripCaptureDao().findById(captureId)?.status)
        job.cancelAndJoin()
    }

    @Test
    fun anApproximateFixIsNotSignalSoTheGapOpensAndNamesThePermissionInsteadOfNoFix() = runBlocking {
        val captureId = startCapture()
        val job = record(captureId)
        gateway.channel.send(fixNow())
        awaitTrue("precise point stored") { points(captureId).size == 1 }

        precise = false
        // Approximate fixes keep arriving every 10 s. If they counted as signal the silence would never reach
        // the 30 s threshold; they are not positions, so the gap opens 30 s after the last precise fix.
        repeat(6) {
            clock.advanceMillis(10_000L)
            gateway.channel.send(fixNow(accuracy = 2000f))
            delay(40)
        }
        awaitTrue("gap started") { events(TrackingSessionCoordinator.EVENT_LOCATION_GAP_STARTED).size == 1 }
        awaitTrue("all approximate points stored") { points(captureId).size == 7 }

        assertEquals(
            TrackingSessionCoordinator.REASON_APPROXIMATE_LOCATION_ONLY,
            events(TrackingSessionCoordinator.EVENT_LOCATION_GAP_STARTED).single().reasonCode
        )
        job.cancelAndJoin()
    }

    @Test
    fun grantingPreciseLocationAgainRestoresTheStateAndClosesTheGap() = runBlocking {
        val captureId = startCapture()
        val job = record(captureId)
        gateway.channel.send(fixNow())
        awaitTrue("precise point stored") { points(captureId).size == 1 }
        precise = false
        clock.advanceMillis(60_000L)
        awaitTrue("gap started") { events(TrackingSessionCoordinator.EVENT_LOCATION_GAP_STARTED).size == 1 }

        precise = true // a grant does not restart the process: it has to be noticed live
        clock.advanceMillis(5_000L)
        gateway.channel.send(fixNow())
        awaitTrue("gap ended") { events(TrackingSessionCoordinator.EVENT_LOCATION_GAP_ENDED).size == 1 }

        assertEquals(1, events(TrackingSessionCoordinator.EVENT_LOCATION_ACCURACY_RESTORED).size)
        assertTrue(reports.toList().containsAll(listOf(LocationSignalReport.APPROXIMATE_ONLY, LocationSignalReport.PRECISE_RESTORED, LocationSignalReport.RESTORED)))
        assertEquals("the new fix is precise", false, points(captureId).last().isApproximateLocation)
        job.cancelAndJoin()
    }

    @Test
    fun startingWithOnlyApproximateLocationIsReportedFromTheFirstLook() = runBlocking {
        precise = false
        val captureId = startCapture()
        val job = record(captureId)

        awaitTrue("degraded from the first tick") { events(TrackingSessionCoordinator.EVENT_LOCATION_ACCURACY_DEGRADED).size == 1 }

        assertEquals(listOf(LocationSignalReport.APPROXIMATE_ONLY), reports.toList())
        assertEquals("said once, not on every fix or tick", 1, events(TrackingSessionCoordinator.EVENT_LOCATION_ACCURACY_DEGRADED).size)
        job.cancelAndJoin()
    }

    @Test
    fun theDegradedStateIsNeverLeftOpenWhenTheRecordingStops() = runBlocking {
        val captureId = startCapture()
        precise = false
        val job = record(captureId)
        awaitTrue("degraded") { events(TrackingSessionCoordinator.EVENT_LOCATION_ACCURACY_DEGRADED).size == 1 }

        job.cancelAndJoin()

        assertEquals(TrackingSessionCoordinator.REASON_RECORDING_STOPPED, events(TrackingSessionCoordinator.EVENT_LOCATION_ACCURACY_RESTORED).single().reasonCode)
        assertEquals("nothing new to tell a service that is stopping", listOf(LocationSignalReport.APPROXIMATE_ONLY), reports.toList())
    }

    /** A restart must not take an approximate fix for a live signal just because it is the last point stored. */
    @Test
    fun theLastUsablePointSkipsApproximateOnesButCountsLegacyUnknownOnes() = runTest {
        val captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
        fun entity(seq: Long, approximate: Boolean?) = com.mototriptracker.app.core.database.entity.RawTrackPointEntity(
            captureId = captureId, sequenceNumber = seq, capturedAt = seq, elapsedRealtimeNanos = seq * 1_000L,
            receivedAtElapsedRealtimeNanos = null, latitude = 10.0, longitude = -20.0, horizontalAccuracyM = 5f,
            altitudeEllipsoidM = null, altitudeMslM = null, verticalAccuracyM = null, speedMps = null, speedAccuracyMps = null,
            bearingDeg = null, bearingAccuracyDeg = null, provider = null, isMock = false, requestProfileId = "p",
            callbackBatchId = null, detectorStateSnapshot = "TRACKING", isApproximateLocation = approximate
        )
        val dao = db.rawTrackPointDao()
        dao.insert(entity(0, null)) // predates the marker: unknown, counts as usable
        dao.insert(entity(1, false))
        dao.insert(entity(2, true))
        dao.insert(entity(3, true))

        assertEquals(1L, dao.findLastUsableByCapture(captureId)?.sequenceNumber)
        assertEquals("the plain last-point query is unchanged", 3L, dao.findLastByCapture(captureId)?.sequenceNumber)
        assertNull(dao.findLastUsableByCapture("no-such-capture"))
    }
}
