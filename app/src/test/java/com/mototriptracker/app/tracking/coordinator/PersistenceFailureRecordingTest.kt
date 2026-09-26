package com.mototriptracker.app.tracking.coordinator

import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.LocationSample
import com.mototriptracker.app.testing.FakeProcessingScheduler
import com.mototriptracker.app.testing.FlakyRawTrackPointDao
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.location.LocationGateway
import com.mototriptracker.app.tracking.persistence.PersistenceLevel
import com.mototriptracker.app.tracking.persistence.PersistenceState
import com.mototriptracker.app.tracking.persistence.RawPointWriter
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/**
 * REC-006 wired into a live recording: the same real-time harness as [LocationGapRecordingTest].
 * What matters here is what [RawPointWriterTest] cannot show - the periodic tick retries with no
 * new fix, Finish flushes before the Finish transaction reads the last sequence number, and a
 * failing database never stops the recording itself.
 */
@RunWith(RobolectricTestRunner::class)
class PersistenceFailureRecordingTest {

    private class ChannelLocationGateway : LocationGateway {
        val channel = Channel<LocationSample>(Channel.UNLIMITED)
        override fun locationUpdates(): Flow<LocationSample> = channel.receiveAsFlow()
    }

    private lateinit var db: MotoTripDatabase
    private lateinit var clock: FakeClock
    private lateinit var flakyPoints: FlakyRawTrackPointDao
    private lateinit var gateway: ChannelLocationGateway
    private lateinit var coordinator: TrackingSessionCoordinator
    private val states: MutableList<PersistenceState> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        clock = FakeClock(wallMillis = 1_000_000L, elapsedNanos = 10_000_000_000L)
        flakyPoints = FlakyRawTrackPointDao(db.rawTrackPointDao())
        gateway = ChannelLocationGateway()
        coordinator = TrackingSessionCoordinator(
            database = db, tripCaptureDao = db.tripCaptureDao(), diagnosticEventDao = db.diagnosticEventDao(),
            rawTrackPointDao = flakyPoints, captureEventDao = db.captureEventDao(), tripDao = db.tripDao(),
            tripPartDao = db.tripPartDao(), manualPauseIntervalDao = db.manualPauseIntervalDao(),
            locationGateway = gateway, processingScheduler = FakeProcessingScheduler(), clock = clock,
            idGenerator = FakeIdGenerator(prefix = "id")
        )
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
            throw AssertionError("timed out waiting for: $what; states=${states.toList()}")
        }
    }

    private suspend fun stored(captureId: String) = db.rawTrackPointDao().findAllByCapture(captureId).map { it.sequenceNumber }

    private suspend fun events(type: String) = db.diagnosticEventDao().findAll().filter { it.eventType == type }

    private fun kotlinx.coroutines.CoroutineScope.record(captureId: String): Job = launch(Dispatchers.Default) {
        coordinator.recordLocationUpdates(
            captureId,
            onPersistenceStateChanged = { states += it },
            tickIntervalMs = 20L
        )
    }

    private suspend fun startCapture() =
        (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId

    /** Every fix sent so far has been taken off the channel and handled by the collector. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun settle() {
        awaitTrue("collector drained") { gateway.channel.isEmpty }
        delay(150)
    }

    private suspend fun sendFix() {
        clock.advanceMillis(2_000L)
        gateway.channel.send(fixNow())
    }

    @Test
    fun heldPointsAreSavedByThePeriodicRetryOnceTheDatabaseRecoversWithNoNewFixNeeded() = runBlocking {
        val captureId = startCapture()
        val job = record(captureId)
        sendFix()
        awaitTrue("first point") { stored(captureId) == listOf(0L) }

        flakyPoints.failure = IllegalStateException("disk hiccup")
        repeat(3) { sendFix() }
        awaitTrue("degraded reported") { states.lastOrNull()?.level == PersistenceLevel.DEGRADED }
        settle()
        assertEquals("held, not claimed as saved", listOf(0L), stored(captureId))
        assertEquals("the capture itself carries on", CaptureStatus.ACTIVE, db.tripCaptureDao().findById(captureId)?.status)

        flakyPoints.failure = null
        clock.advanceMillis(60_000L) // past the backoff; the ticker retries by itself
        awaitTrue("held points written") { stored(captureId) == listOf(0L, 1, 2, 3) }
        awaitTrue("healthy again") { states.lastOrNull() == PersistenceState.HEALTHY }

        sendFix()
        awaitTrue("recording continues in sequence") { stored(captureId) == listOf(0L, 1, 2, 3, 4) }
        assertEquals(1, events(RawPointWriter.EVENT_INSERT_FAILED).size)
        assertEquals(1, events(RawPointWriter.EVENT_RECOVERED).size)
        job.cancelAndJoin()
    }

    /** Finish cancels the collector first, then runs its transaction: what is held must be flushed in between. */
    @Test
    fun finishingFlushesWhatIsHeldBeforeTheFinishTransactionReadsTheLastSequenceNumber() = runBlocking {
        val captureId = startCapture()
        val job = record(captureId)
        flakyPoints.failure = IllegalStateException("disk hiccup")
        repeat(3) { sendFix() }
        awaitTrue("degraded reported") { states.lastOrNull()?.level == PersistenceLevel.DEGRADED }
        settle()
        flakyPoints.failure = null // recovered, backoff not elapsed: only the final flush can save them

        job.cancelAndJoin()
        val finished = coordinator.finishCapture(captureId) as TrackingSessionCoordinator.FinishResult.Finished

        assertEquals(listOf(0L, 1, 2), stored(captureId))
        val part = db.tripPartDao().findAllByTrip(finished.tripId).single()
        assertEquals("the TripPart covers the flushed points", 2L, part.endSequenceNumber)
    }

    @Test
    fun finishingWhileTheDatabaseIsStillDownRecordsTheLossInsteadOfLettingItVanish() = runBlocking {
        val captureId = startCapture()
        val job = record(captureId)
        flakyPoints.failure = IllegalStateException("disk hiccup")
        repeat(3) { sendFix() }
        awaitTrue("degraded reported") { states.lastOrNull()?.level == PersistenceLevel.DEGRADED }
        settle()
        job.cancelAndJoin()

        assertEquals("3", events(RawPointWriter.EVENT_DATA_LOSS).single().metadata["discardedAtStop"])
    }

    @Test
    fun aFailingDatabaseNeverStopsTheRecordingLoopItself() = runBlocking {
        val captureId = startCapture()
        val job = record(captureId)
        flakyPoints.failure = IllegalStateException("disk hiccup")

        repeat(20) { sendFix() }
        delay(200)

        assertEquals("still collecting", true, job.isActive)
        flakyPoints.failure = null
        clock.advanceMillis(60_000L)
        awaitTrue("all twenty saved in order") { stored(captureId) == (0L..19L).toList() }
        job.cancelAndJoin()
    }
}
