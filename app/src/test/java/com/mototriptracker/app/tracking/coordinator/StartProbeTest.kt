package com.mototriptracker.app.tracking.coordinator

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.testing.FakeProcessingScheduler
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.location.LocationGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DET-012 (`ADR-031`), measurement mode: a start probe looks at GPS fixes for a few minutes after Activity Recognition says
 * the phone stopped being still, and records what it *would* have done. These pin what it records, that speed alone decides
 * (a long walk is not a trip), and that it creates nothing: no capture, no raw point, no coordinate in any event.
 */
@RunWith(RobolectricTestRunner::class)
class StartProbeTest {

    private lateinit var db: MotoTripDatabase
    private val clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L)

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun coordinatorWith(locations: Flow<LocationSample>) = TrackingSessionCoordinator(
        database = db, tripCaptureDao = db.tripCaptureDao(), diagnosticEventDao = db.diagnosticEventDao(),
        rawTrackPointDao = db.rawTrackPointDao(), captureEventDao = db.captureEventDao(), tripDao = db.tripDao(),
        tripPartDao = db.tripPartDao(), manualPauseIntervalDao = db.manualPauseIntervalDao(),
        locationGateway = object : LocationGateway {
            override fun locationUpdates(): Flow<LocationSample> = locations
        },
        processingScheduler = FakeProcessingScheduler(), clock = clock, idGenerator = FakeIdGenerator(prefix = "probe")
    )

    // A latitude/longitude that appear nowhere else, so a leak of either would be found as a substring.
    private fun fix(seconds: Int, metersNorth: Double, speedMps: Float?) = LocationSample(
        wallTimeEpochMs = 2_000L + seconds * 1_000L,
        elapsedRealtimeNanos = seconds * 1_000_000_000L,
        receivedAtElapsedRealtimeNanos = seconds * 1_000_000_000L,
        latitude = 9.87654 + metersNorth / 111_195.0,
        longitude = -83.54321,
        horizontalAccuracyM = 5f,
        requestProfileId = "test-profile",
        speedMps = speedMps
    )

    /** Fixes at the given seconds, emitted in virtual time one after the other. */
    private fun timed(vararg fixes: LocationSample): Flow<LocationSample> = flow {
        var previousMs = 0L
        for (fix in fixes) {
            val atMs = fix.elapsedRealtimeNanos / 1_000_000L
            delay(atMs - previousMs)
            previousMs = atMs
            emit(fix)
        }
    }

    private suspend fun events(type: String) = db.diagnosticEventDao().findAll().filter { it.eventType == type }

    private suspend fun allProbeEvents() = db.diagnosticEventDao().findAll().filter { it.eventType.startsWith("START_PROBE_") }

    /** A person walking: 1.3 m/s for as long as the window lasts and then some. */
    private fun aWalk(untilSeconds: Int) = (0..untilSeconds step 10).map { s -> fix(s, metersNorth = s * 1.3, speedMps = 1.3f) }

    @Test
    fun aProbeThatOnlySeesAWalkEndsWhenItsWindowPassesAndSaysItWouldNotHaveStarted() = runTest {
        val outcome = coordinatorWith(timed(*aWalk(400).toTypedArray())).runStartProbe()

        assertEquals(TrackingSessionCoordinator.StartProbeOutcome("WINDOW_EXPIRED", wouldHaveStarted = false), outcome)
        assertEquals(1, events(TrackingSessionCoordinator.EVENT_START_PROBE_STARTED).size)
        assertEquals("STILL_EXIT", events(TrackingSessionCoordinator.EVENT_START_PROBE_STARTED).single().reasonCode)
        assertEquals(0, events(TrackingSessionCoordinator.EVENT_START_PROBE_WOULD_START).size)
        val ended = events(TrackingSessionCoordinator.EVENT_START_PROBE_ENDED).single()
        assertEquals(DiagnosticCategory.DETECTOR, ended.category)
        assertEquals("WINDOW_EXPIRED", ended.reasonCode)
        assertEquals("false", ended.metadata["wouldHaveStarted"])
        assertEquals("1.3", ended.metadata["maxSpeedMps"])
        assertNull(ended.metadata["wouldStartAfterMs"])
    }

    @Test
    fun aProbeNeverConfirmsOnDistanceAloneHoweverFarTheWalkerGoes() = runTest {
        // 1.5 m/s for six minutes is 540 m from where it began: far enough that displacement would confirm any ordinary candidate.
        val walk = (0..380 step 10).map { s -> fix(s, metersNorth = s * 1.5, speedMps = 1.5f) }

        val outcome = coordinatorWith(timed(*walk.toTypedArray())).runStartProbe()

        assertEquals(false, outcome.wouldHaveStarted)
        val ended = events(TrackingSessionCoordinator.EVENT_START_PROBE_ENDED).single()
        assertTrue("the walker really did cover the distance", ended.metadata.getValue("displacementM").toInt() > 450)
        assertEquals(0, events(TrackingSessionCoordinator.EVENT_START_PROBE_WOULD_START).size)
    }

    @Test
    fun aProbeThatSeesARideBeginRecordsWhenItWouldHaveStartedAndKeepsLookingUntilItsWindowEnds() = runTest {
        val fixes = buildList {
            // Standing, then walking to the bike for a minute.
            (0..60 step 5).forEach { s -> add(fix(s, metersNorth = s * 0.5, speedMps = 1.0f)) }
            // The ride begins: three fixes at speed in a row.
            add(fix(65, metersNorth = 60.0, speedMps = 5.0f))
            add(fix(67, metersNorth = 72.0, speedMps = 6.0f))
            add(fix(69, metersNorth = 84.0, speedMps = 7.0f))
            // And on it goes until past the window.
            (80..380 step 20).forEach { s -> add(fix(s, metersNorth = 84.0 + (s - 69) * 8.0, speedMps = 8.0f)) }
        }

        val outcome = coordinatorWith(timed(*fixes.toTypedArray())).runStartProbe()

        assertEquals(TrackingSessionCoordinator.StartProbeOutcome("WINDOW_EXPIRED", wouldHaveStarted = true), outcome)
        val wouldStart = events(TrackingSessionCoordinator.EVENT_START_PROBE_WOULD_START).single()
        assertEquals("CONFIRMED_SPEED", wouldStart.reasonCode)
        val afterMs = wouldStart.metadata.getValue("afterMs").toLong()
        assertTrue("confirmed by the third fast fix, at ~69 s: $afterMs", afterMs in 68_000L..70_000L)
        val ended = events(TrackingSessionCoordinator.EVENT_START_PROBE_ENDED).single()
        assertEquals("true", ended.metadata["wouldHaveStarted"])
        assertEquals(wouldStart.metadata["afterMs"], ended.metadata["wouldStartAfterMs"])
        assertEquals("the speed seen over the whole probe, not only until it would have started", "8.0", ended.metadata["maxSpeedMps"])
        assertTrue(
            "the distance the ride had covered by the end is more than by the moment it would have started",
            ended.metadata.getValue("displacementM").toInt() > ended.metadata.getValue("displacementAtWouldStartM").toInt()
        )
    }

    @Test
    fun aProbeCreatesNoCaptureAndWritesNoRawPointEvenWhenItWouldHaveStarted() = runTest {
        val ride = listOf(fix(0, 0.0, 0.5f)) + (10..30 step 2).map { s -> fix(s, s * 8.0, 8.0f) } + fix(400, 4_000.0, 8.0f)

        coordinatorWith(timed(*ride.toTypedArray())).runStartProbe()

        assertEquals("it would have started...", 1, events(TrackingSessionCoordinator.EVENT_START_PROBE_WOULD_START).size)
        assertEquals("...and did nothing of the kind", 0, db.tripCaptureDao().countByStatus(CaptureStatus.ACTIVE))
        assertNull(db.tripCaptureDao().findMostRecentlyEnded())
        assertEquals(0, db.diagnosticEventDao().findAll().count { it.eventType == "START" })
    }

    @Test
    fun noProbeEventCarriesACoordinateOrAnythingDerivedFromOneButAStraightLineDistance() = runTest {
        val ride = listOf(fix(0, 0.0, 0.5f)) + (10..30 step 2).map { s -> fix(s, s * 8.0, 8.0f) } + fix(400, 4_000.0, 8.0f)

        coordinatorWith(timed(*ride.toTypedArray())).runStartProbe()

        val recorded = allProbeEvents()
        assertEquals(3, recorded.size)
        val everything = recorded.joinToString(" ") { e -> listOf(e.reasonCode, e.stateBefore, e.stateAfter, e.captureId, e.metadata.toString()).joinToString(" ") }
        assertTrue(everything, listOf("9.87", "83.54", "9.88", "latitude", "longitude").none { everything.contains(it) })
        val keys = recorded.flatMap { it.metadata.keys }.toSet()
        assertTrue(keys.toString(), keys.none { it.contains("lat", ignoreCase = true) || it.contains("lon", ignoreCase = true) })
    }

    // --- ended by the service: real threads, since a cancellation has to arrive while the probe is running ---

    private suspend fun endedWith(cancellation: CancellationException?): DiagnosticEventEntity {
        val fixes = Channel<LocationSample>(Channel.UNLIMITED)
        val run = CoroutineScope(Dispatchers.Default).async { coordinatorWith(fixes.receiveAsFlow()).runStartProbe() }
        try {
            fixes.send(fix(0, 0.0, 0.5f))
            fixes.send(fix(5, 3.0, 0.6f))
            withTimeout(15_000L) { while (events(TrackingSessionCoordinator.EVENT_START_PROBE_STARTED).isEmpty()) delay(20L) }
            delay(300L)
            if (cancellation != null) run.cancel(cancellation) else run.cancel()
            run.join()
            withTimeout(15_000L) { while (events(TrackingSessionCoordinator.EVENT_START_PROBE_ENDED).isEmpty()) delay(20L) }
            return events(TrackingSessionCoordinator.EVENT_START_PROBE_ENDED).single()
        } finally {
            run.cancel()
            fixes.close()
        }
    }

    @Test
    fun aProbeTheServiceHandsToARealDetectionRecordsThatAsItsEndWithWhatItHadSeen() = runBlocking {
        val ended = endedWith(CancellationException(TrackingSessionCoordinator.PROBE_END_SUPERSEDED_BY_DETECTION))

        assertEquals("SUPERSEDED_BY_DETECTION", ended.reasonCode)
        assertEquals("false", ended.metadata["wouldHaveStarted"])
        assertNotNull("what it had seen is there", ended.metadata["fixCount"])
        assertNotNull(ended.metadata["elapsedMs"])
    }

    @Test
    fun aProbeEndedByACaptureStartingSaysSo() = runBlocking {
        val ended = endedWith(CancellationException(TrackingSessionCoordinator.PROBE_END_CAPTURE_STARTED))

        assertEquals("CAPTURE_STARTED", ended.reasonCode)
    }

    @Test
    fun aProbeCancelledForAnyOtherReasonIsRecordedAsCancelledNotAsOneOfTheKnownEnds() = runBlocking {
        val ended = endedWith(null)

        assertEquals("CANCELLED", ended.reasonCode)
    }
}
