package com.mototriptracker.app.tracking.coordinator

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DiagnosticCategory
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
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/**
 * REC-005 / F0.10 §13: a stretch with no location fixes is *recorded* (a LOCATION gap event pair
 * and a state the UI can show) and never acted on: the capture stays ACTIVE, nothing is invented
 * to bridge it, and a failure to write the diagnostic cannot stop the recording.
 *
 * Real time, not virtual: Room answers on its own threads, so a virtual scheduler can race ahead
 * of it. The clock is a [FakeClock] the test moves by hand (a 60 s silence costs no waiting) and the
 * silence check ticks every few milliseconds.
 */
@RunWith(RobolectricTestRunner::class)
class LocationGapRecordingTest {

    private class ChannelLocationGateway : LocationGateway {
        val channel = Channel<LocationSample>(Channel.UNLIMITED)
        override fun locationUpdates(): Flow<LocationSample> = channel.receiveAsFlow()
    }

    private lateinit var db: MotoTripDatabase
    private lateinit var clock: FakeClock
    private lateinit var gateway: ChannelLocationGateway
    private val ids = FakeIdGenerator(prefix = "id")
    private lateinit var coordinator: TrackingSessionCoordinator
    private val reports: MutableList<LocationSignalReport> = Collections.synchronizedList(mutableListOf())

    private fun buildCoordinator(diagnosticDao: DiagnosticEventDao = db.diagnosticEventDao()) = TrackingSessionCoordinator(
        database = db, tripCaptureDao = db.tripCaptureDao(), diagnosticEventDao = diagnosticDao,
        rawTrackPointDao = db.rawTrackPointDao(), captureEventDao = db.captureEventDao(), tripDao = db.tripDao(),
        tripPartDao = db.tripPartDao(), manualPauseIntervalDao = db.manualPauseIntervalDao(),
        locationGateway = gateway, processingScheduler = FakeProcessingScheduler(), clock = clock,
        idGenerator = ids
    )

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        clock = FakeClock(wallMillis = 1_000_000L, elapsedNanos = 10_000_000_000L)
        gateway = ChannelLocationGateway()
        coordinator = buildCoordinator()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun fixNow() = LocationSample(
        wallTimeEpochMs = clock.wallClockMillis(), elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
        receivedAtElapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
        latitude = 10.0 + clock.elapsedRealtimeNanos() * 1e-12, longitude = -20.0, horizontalAccuracyM = 5.0f,
        requestProfileId = "test-profile"
    )

    private suspend fun awaitTrue(what: String, condition: suspend () -> Boolean) {
        try {
            withTimeout(5_000) { while (!condition()) delay(10) }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            val seen = db.diagnosticEventDao().findAll().joinToString { "${it.eventType}(${it.reasonCode},${it.metadata})" }
            throw AssertionError("timed out waiting for: $what; diagnostics so far: [$seen]; reports=${reports.toList()}")
        }
    }

    private suspend fun gapEvents(type: String) =
        db.diagnosticEventDao().findAll().filter { it.eventType == type }

    private fun kotlinx.coroutines.CoroutineScope.record(
        captureId: String,
        servicesEnabled: () -> Boolean = { true },
        tickMs: Long = 20L,
        with: TrackingSessionCoordinator = coordinator
    ): Job = launch(Dispatchers.Default) {
        with.recordLocationUpdates(
            captureId,
            locationServicesEnabled = servicesEnabled,
            onLocationSignalChanged = { reports += it },
            tickIntervalMs = tickMs
        )
    }

    private suspend fun startCapture() =
        (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId

    private suspend fun pointCount(captureId: String) = db.rawTrackPointDao().countByCapture(captureId)

    @Test
    fun aSilenceOfAMinuteIsRecordedAsALiveGapAndAsRestoredWhenTheFixesReturnWhileTheTripStaysActive() = runBlocking {
        val captureId = startCapture()
        val job = record(captureId)
        gateway.channel.send(fixNow())
        awaitTrue("first point") { pointCount(captureId) == 1 }

        clock.advanceMillis(60_000L)
        awaitTrue("gap started") { gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_STARTED).size == 1 }

        val started = gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_STARTED).single()
        assertEquals(DiagnosticCategory.LOCATION, started.category)
        assertEquals(TrackingSessionCoordinator.REASON_NO_FIX, started.reasonCode)
        assertEquals("LIVE", started.metadata["detection"])
        assertEquals("60000", started.metadata["silenceMsWhenDetected"])
        assertEquals(listOf(LocationSignalReport.LOST_NO_FIX), reports.toList())
        assertEquals("the trip is never ended or paused because fixes stopped (§13.2)", CaptureStatus.ACTIVE, db.tripCaptureDao().findById(captureId)?.status)
        assertEquals("nothing is invented to bridge the gap", 1, pointCount(captureId))

        clock.advanceMillis(5_000L)
        gateway.channel.send(fixNow())
        awaitTrue("gap ended") { gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_ENDED).size == 1 }

        val ended = gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_ENDED).single()
        assertEquals("65000", ended.metadata["durationMs"])
        assertEquals(listOf(LocationSignalReport.LOST_NO_FIX, LocationSignalReport.RESTORED), reports.toList())
        awaitTrue("second point") { pointCount(captureId) == 2 }
        job.cancelAndJoin()
    }

    @Test
    fun aGapWithLocationServicesSwitchedOffSaysSoInsteadOfBlamingTheSignal() = runBlocking {
        val captureId = startCapture()
        val job = record(captureId, servicesEnabled = { false })
        gateway.channel.send(fixNow())
        awaitTrue("first point") { pointCount(captureId) == 1 }

        clock.advanceMillis(45_000L)
        awaitTrue("gap started") { gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_STARTED).size == 1 }

        assertEquals(TrackingSessionCoordinator.REASON_LOCATION_SERVICES_OFF, gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_STARTED).single().reasonCode)
        assertEquals(listOf(LocationSignalReport.LOST_LOCATION_SERVICES_OFF), reports.toList())
        job.cancelAndJoin()
    }

    /** The ticker does not run in deep sleep; the first fix afterwards must still report the whole gap. */
    @Test
    fun aGapNobodyTickedDuringIsStillRecordedWhenTheNextFixArrives() = runBlocking {
        val captureId = startCapture()
        val job = record(captureId, tickMs = Long.MAX_VALUE)
        gateway.channel.send(fixNow())
        awaitTrue("first point") { pointCount(captureId) == 1 }

        clock.advanceMillis(120_000L)
        gateway.channel.send(fixNow())
        awaitTrue("gap ended") { gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_ENDED).size == 1 }

        val started = gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_STARTED).single()
        assertEquals("RETROACTIVE", started.metadata["detection"])
        assertEquals("120000", gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_ENDED).single().metadata["durationMs"])
        assertEquals(listOf(LocationSignalReport.LOST_NO_FIX, LocationSignalReport.RESTORED), reports.toList())
        job.cancelAndJoin()
    }

    @Test
    fun fixesSecondsApartAreNeverAGap() = runBlocking {
        val captureId = startCapture()
        val job = record(captureId)
        repeat(5) {
            gateway.channel.send(fixNow())
            clock.advanceMillis(2_000L)
        }
        awaitTrue("five points") { pointCount(captureId) == 5 }
        delay(150)

        assertEquals(0, db.diagnosticEventDao().findAll().count { it.category == DiagnosticCategory.LOCATION })
        assertTrue(reports.isEmpty())
        job.cancelAndJoin()
    }

    @Test
    fun beforeTheFirstFixThereIsNoGapToReport() = runBlocking {
        val captureId = startCapture()
        val job = record(captureId)

        clock.advanceMillis(300_000L)
        delay(200)

        assertEquals("waiting for a first fix is not losing one", 0, db.diagnosticEventDao().findAll().count { it.category == DiagnosticCategory.LOCATION })
        assertTrue(reports.isEmpty())
        job.cancelAndJoin()
    }

    @Test
    fun aManualPauseIsNeverReportedAsALostSignalAndFixesHeardWhilePausedCountAsSignal() = runBlocking {
        val captureId = startCapture()
        val job = record(captureId)
        gateway.channel.send(fixNow())
        awaitTrue("first point") { pointCount(captureId) == 1 }
        coordinator.pauseCapture()

        clock.advanceMillis(300_000L)
        delay(200)
        assertEquals("no gap is opened while the rider asked for no route", 0, gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_STARTED).size)

        // The GPS is still heard during the pause (not persisted), so resuming is not a gap.
        gateway.channel.send(fixNow())
        clock.advanceMillis(2_000L)
        coordinator.resumeCapture()
        gateway.channel.send(fixNow())
        awaitTrue("post-resume point") { pointCount(captureId) == 2 }
        delay(100)

        assertEquals(0, db.diagnosticEventDao().findAll().count { it.eventType == TrackingSessionCoordinator.EVENT_LOCATION_GAP_STARTED })
        job.cancelAndJoin()
    }

    @Test
    fun aRestartedRecordingReportsTheSilenceItSleptThroughAsAGap() = runBlocking {
        val captureId = startCapture()
        val first = record(captureId)
        gateway.channel.send(fixNow())
        awaitTrue("first point") { pointCount(captureId) == 1 }
        first.cancelAndJoin() // the process died

        clock.advanceMillis(240_000L)
        val restartedGateway = ChannelLocationGateway()
        gateway = restartedGateway
        val restarted = buildCoordinator()
        val second = record(captureId, with = restarted)
        restartedGateway.channel.send(fixNow())
        awaitTrue("gap ended") { gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_ENDED).size == 1 }

        assertEquals("240000", gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_ENDED).single().metadata["durationMs"])
        assertEquals(CaptureStatus.ACTIVE, db.tripCaptureDao().findById(captureId)?.status)
        second.cancelAndJoin()
    }

    /** A diagnostic that cannot be written must never take the recording down with it. */
    @Test
    fun aFailureToWriteTheGapEventDoesNotStopTheRecording() = runBlocking {
        val realDao = db.diagnosticEventDao()
        val failingDao = object : DiagnosticEventDao by realDao {
            override suspend fun insert(event: DiagnosticEventEntity) {
                if (event.category == DiagnosticCategory.LOCATION) throw IllegalStateException("disk full")
                realDao.insert(event)
            }
        }
        val captureId = startCapture()
        val job = record(captureId, with = buildCoordinator(failingDao))
        gateway.channel.send(fixNow())
        awaitTrue("first point") { pointCount(captureId) == 1 }

        clock.advanceMillis(90_000L)
        awaitTrue("the rider is still told about the gap") { reports.contains(LocationSignalReport.LOST_NO_FIX) }
        assertEquals("the diagnostic really was refused", 0, gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_STARTED).size)
        gateway.channel.send(fixNow())
        awaitTrue("recording carried on") { pointCount(captureId) == 2 }

        assertNotNull(db.tripCaptureDao().findById(captureId))
        assertTrue("the recording job is still alive", job.isActive)
        job.cancelAndJoin()
    }

    @Test
    fun aGapStillOpenWhenTheRiderPausesIsClosedByThePauseNotByARestoredSignal() = runBlocking {
        val captureId = startCapture()
        val job = record(captureId)
        gateway.channel.send(fixNow())
        awaitTrue("first point") { pointCount(captureId) == 1 }
        clock.advanceMillis(60_000L)
        awaitTrue("gap started") { gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_STARTED).size == 1 }

        coordinator.pauseCapture()
        awaitTrue("gap closed by the pause") { gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_ENDED).size == 1 }

        assertEquals(TrackingSessionCoordinator.REASON_MANUAL_PAUSE, gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_ENDED).single().reasonCode)
        assertEquals("the rider is not left looking at a stale 'no signal'", LocationSignalReport.RESTORED, reports.last())
        job.cancelAndJoin()
    }

    /** A gap must never be left with a start and no end in the evidence just because the trip was finished during it. */
    @Test
    fun aGapStillOpenWhenTheRecordingStopsIsClosedAndTheRiderIsNotToldAnythingNew() = runBlocking {
        val captureId = startCapture()
        val job = record(captureId)
        gateway.channel.send(fixNow())
        awaitTrue("first point") { pointCount(captureId) == 1 }
        clock.advanceMillis(60_000L)
        awaitTrue("gap started") { gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_STARTED).size == 1 }

        job.cancelAndJoin() // what the service does first on Finish

        val ended = gapEvents(TrackingSessionCoordinator.EVENT_LOCATION_GAP_ENDED).single()
        assertEquals(TrackingSessionCoordinator.REASON_RECORDING_STOPPED, ended.reasonCode)
        assertEquals("no notification refresh for a service that is stopping", listOf(LocationSignalReport.LOST_NO_FIX), reports.toList())
    }
}
