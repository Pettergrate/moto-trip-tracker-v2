package com.mototriptracker.app.tracking.persistence

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteFullException
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.entity.RawTrackPointEntity
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.testing.FakeLocationGateway
import com.mototriptracker.app.testing.FlakyDiagnosticEventDao
import com.mototriptracker.app.testing.FlakyRawTrackPointDao
import com.mototriptracker.app.testing.FakeProcessingScheduler
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * REC-006 / F0.10 §14: when Room refuses writes the recording buffers (bounded), retries (without a
 * storm), says so, and never claims what it could not save - and never loses one silently.
 */
@RunWith(RobolectricTestRunner::class)
class RawPointWriterTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var clock: FakeClock
    private lateinit var flakyPoints: FlakyRawTrackPointDao
    private lateinit var flakyDiagnostics: FlakyDiagnosticEventDao
    private lateinit var captureId: String
    private val states = mutableListOf<PersistenceState>()

    @Before
    fun setUp() = runTest {
        db = TestDatabaseFactory.createInMemory()
        clock = FakeClock(wallMillis = 1_000_000L, elapsedNanos = 10_000_000_000L)
        flakyPoints = FlakyRawTrackPointDao(db.rawTrackPointDao())
        flakyDiagnostics = FlakyDiagnosticEventDao(db.diagnosticEventDao())
        val coordinator = TrackingSessionCoordinator(
            database = db, tripCaptureDao = db.tripCaptureDao(), diagnosticEventDao = db.diagnosticEventDao(),
            rawTrackPointDao = db.rawTrackPointDao(), captureEventDao = db.captureEventDao(), tripDao = db.tripDao(),
            tripPartDao = db.tripPartDao(), manualPauseIntervalDao = db.manualPauseIntervalDao(),
            locationGateway = FakeLocationGateway(emptyList()), processingScheduler = FakeProcessingScheduler(),
            clock = clock, idGenerator = FakeIdGenerator(prefix = "capture")
        )
        captureId = (coordinator.startManualCapture() as TrackingSessionCoordinator.StartResult.Started).captureId
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun writer(capacity: Int = RawPointWriter.DEFAULT_CAPACITY) = RawPointWriter(
        rawTrackPointDao = flakyPoints, diagnosticEventDao = flakyDiagnostics, clock = clock,
        idGenerator = FakeIdGenerator(prefix = "diag"), captureId = captureId, capacity = capacity,
        onStateChanged = { states += it }
    )

    private fun point(seq: Long) = RawTrackPointEntity(
        captureId = captureId, sequenceNumber = seq, capturedAt = 1_000L + seq * 2_000L,
        elapsedRealtimeNanos = 10_000_000_000L + seq * 2_000_000_000L, receivedAtElapsedRealtimeNanos = null,
        latitude = 10.0 + seq * 1e-5, longitude = -20.0, horizontalAccuracyM = 5f, altitudeEllipsoidM = null,
        altitudeMslM = null, verticalAccuracyM = null, speedMps = null, speedAccuracyMps = null, bearingDeg = null,
        bearingAccuracyDeg = null, provider = null, isMock = false, requestProfileId = "test", callbackBatchId = null,
        detectorStateSnapshot = "TRACKING"
    )

    private suspend fun stored() = db.rawTrackPointDao().findAllByCapture(captureId).map { it.sequenceNumber }

    private suspend fun events(type: String) = db.diagnosticEventDao().findAll().filter { it.eventType == type }

    /** The capture's own START event lives in the same table; only REC-006's persistence events matter here. */
    private suspend fun persistenceEventCount() =
        db.diagnosticEventDao().findAll().count { it.category == DiagnosticCategory.PERSISTENCE }

    private fun tick(ms: Long = 2_000L) = clock.advanceMillis(ms)

    @Test
    fun aHealthyDatabaseIsJustAnInsertPerPointWithNoEventsAndNoStateChange() = runTest {
        val writer = writer()

        (0L..4L).forEach { writer.write(point(it)); tick() }

        assertEquals(listOf(0L, 1, 2, 3, 4), stored())
        assertEquals(0, persistenceEventCount())
        assertTrue("nothing to tell the rider", states.isEmpty())
    }

    @Test
    fun aFailedInsertIsHeldNotClaimedAsSavedAndTheStateSaysSo() = runTest {
        val writer = writer()
        writer.write(point(0)); tick()
        flakyPoints.failure = IllegalStateException("disk hiccup")

        writer.write(point(1)); tick()

        assertEquals("only what really reached Room counts as saved", listOf(0L), stored())
        assertEquals(PersistenceState(PersistenceLevel.DEGRADED), writer.state)
        assertEquals(listOf(PersistenceState(PersistenceLevel.DEGRADED)), states)
    }

    @Test
    fun onRecoveryHeldPointsAreWrittenInOrderWithTheirOriginalSequenceNumbers() = runTest {
        val writer = writer()
        writer.write(point(0)); tick()
        flakyPoints.failure = IllegalStateException("disk hiccup")
        (1L..4L).forEach { writer.write(point(it)); tick() }
        flakyPoints.failure = null
        tick(60_000L) // well past the backoff

        writer.write(point(5))

        assertEquals("nothing reordered, nothing missing", listOf(0L, 1, 2, 3, 4, 5), stored())
        assertEquals(PersistenceState.HEALTHY, writer.state)
        val recovered = events(RawPointWriter.EVENT_RECOVERED).single()
        assertEquals("the 4 held plus the point that triggered the flush", "5", recovered.metadata["flushedPoints"])
        assertEquals("0", recovered.metadata["droppedPoints"])
        assertEquals(0, events(RawPointWriter.EVENT_DATA_LOSS).size)
        assertEquals(listOf(PersistenceLevel.DEGRADED, PersistenceLevel.HEALTHY), states.map { it.level })
    }

    /** The old behaviour logged an ERROR event per failed point - thousands of rows in an outage. */
    @Test
    fun anOutageIsOneEpisodeWithAHandfulOfEventsNotOneEventPerFailedPoint() = runTest {
        val writer = writer()
        flakyPoints.failure = IllegalStateException("disk hiccup")

        (0L..59L).forEach { writer.write(point(it)); tick() }

        assertEquals(1, events(RawPointWriter.EVENT_INSERT_FAILED).size)
        assertTrue("well under one event per point", persistenceEventCount() <= 3)
    }

    @Test
    fun aDeadDatabaseIsRetriedAFewTimesNotOncePerFix() = runTest {
        val writer = writer()
        flakyPoints.failure = IllegalStateException("disk hiccup")

        repeat(300) { writer.write(point(it.toLong())); tick() } // 10 minutes of 2 s fixes

        assertTrue("attempts=${flakyPoints.insertAttempts}", flakyPoints.insertAttempts < 40)
    }

    @Test
    fun theBufferIsBoundedAndDroppingTheOldestIsCountedAndRecordedNeverSilent() = runTest {
        val writer = writer(capacity = 5)
        flakyPoints.failure = IllegalStateException("disk hiccup")

        (0L..11L).forEach { writer.write(point(it)); tick() }

        assertEquals(PersistenceState(PersistenceLevel.CRITICAL, pointsLost = true), writer.state)
        assertEquals("one overflow event for the outage, not one per dropped point", 1, events(RawPointWriter.EVENT_BUFFER_OVERFLOW).size)

        flakyPoints.failure = null
        tick(60_000L)
        writer.retryPending()

        assertEquals("the freshest 5 survive, in order", listOf(7L, 8, 9, 10, 11), stored())
        val loss = events(RawPointWriter.EVENT_DATA_LOSS).single()
        assertEquals("7", loss.metadata["droppedOnOverflow"])
        assertEquals("the affected interval starts at the first lost fix", 10_000_000_000L, loss.elapsedRealtimeNanos)
        assertEquals("7", events(RawPointWriter.EVENT_RECOVERED).single().metadata["droppedPoints"])
        assertEquals(PersistenceState.HEALTHY, writer.state)
    }

    /** Found on the phone: the card said points were being lost while everything was still held in memory. */
    @Test
    fun fullStorageWithEverythingStillHeldIsNotReportedAsALossUntilAPointIsActuallyDropped() = runTest {
        val writer = writer(capacity = 3)
        flakyPoints.failure = SQLiteFullException("database or disk is full")

        (0L..2L).forEach { writer.write(point(it)); tick() }
        assertEquals(PersistenceState(PersistenceLevel.CRITICAL, storageFull = true, pointsLost = false), writer.state)

        writer.write(point(3)) // the buffer is full: the oldest is dropped
        assertEquals(PersistenceState(PersistenceLevel.CRITICAL, storageFull = true, pointsLost = true), writer.state)
    }

    /** Found on the phone: the second outage reported the first one's maximum. */
    @Test
    fun theHighWaterMarkIsPerOutageNotForTheWholeRecording() = runTest {
        val writer = writer()
        flakyPoints.failure = IllegalStateException("disk hiccup")
        (0L..4L).forEach { writer.write(point(it)); tick() }
        flakyPoints.failure = null
        tick(60_000L)
        writer.retryPending()

        flakyPoints.failure = IllegalStateException("disk hiccup")
        (5L..6L).forEach { writer.write(point(it)); tick() }
        flakyPoints.failure = null
        tick(60_000L)
        writer.retryPending()

        val marks = events(RawPointWriter.EVENT_RECOVERED).sortedBy { it.occurredAt }.map { it.metadata["highWaterMark"] }
        assertEquals(listOf("5", "2"), marks)
    }

    @Test
    fun aFullDiskIsCriticalAndSaysTheStorageIsFullFromTheFirstFailure() = runTest {
        val writer = writer()
        flakyPoints.failure = SQLiteFullException("database or disk is full")

        writer.write(point(0))

        assertEquals(PersistenceState(PersistenceLevel.CRITICAL, storageFull = true, pointsLost = false), writer.state)
        assertEquals("true", events(RawPointWriter.EVENT_INSERT_FAILED).single().metadata["storageFull"])
    }

    /** A row that can never be stored must not block every point behind it. */
    @Test
    fun aPointTheDatabaseWillNeverAcceptIsDroppedAndCountedWhileTheNextOnesCarryOn() = runTest {
        val writer = writer()
        writer.write(point(0)); tick()
        flakyPoints.failure = SQLiteConstraintException("UNIQUE constraint failed")
        writer.write(point(1)); tick()
        flakyPoints.failure = null
        writer.write(point(2)); tick()
        writer.finish()

        assertEquals(listOf(0L, 2), stored())
        assertEquals(PersistenceState.HEALTHY, writer.state)
        assertEquals(1, events(RawPointWriter.EVENT_INSERT_FAILED).size)
        assertEquals("1", events(RawPointWriter.EVENT_DATA_LOSS).single().metadata["rejectedPoints"])
    }

    @Test
    fun finishTriesOnceMoreIgnoringTheBackoffSoFinishingDoesNotThrowAwayWhatCouldBeSaved() = runTest {
        val writer = writer()
        flakyPoints.failure = IllegalStateException("disk hiccup")
        (0L..3L).forEach { writer.write(point(it)); tick() }
        flakyPoints.failure = null // recovered, but the backoff has not elapsed yet

        writer.finish()

        assertEquals(listOf(0L, 1, 2, 3), stored())
        assertEquals(0, events(RawPointWriter.EVENT_DATA_LOSS).size)
    }

    @Test
    fun ifTheDatabaseIsStillDownAtFinishWhatIsHeldIsRecordedAsLostNotLeftLookingSaved() = runTest {
        val writer = writer()
        flakyPoints.failure = IllegalStateException("disk hiccup")
        (0L..3L).forEach { writer.write(point(it)); tick() }

        writer.finish()

        assertEquals(emptyList<Long>(), stored())
        val loss = events(RawPointWriter.EVENT_DATA_LOSS).single()
        assertEquals("4", loss.metadata["discardedAtStop"])
        assertEquals("RECORDING_ENDED_UNSAVED", events(RawPointWriter.EVENT_RECOVERED).single().reasonCode)
        assertEquals(PersistenceState.HEALTHY, writer.state)
    }

    @Test
    fun aFinishWithNothingHeldIsAQuietNoOpAndItIsIdempotent() = runTest {
        val writer = writer()
        writer.write(point(0))

        writer.finish()
        writer.finish()

        assertEquals(listOf(0L), stored())
        assertEquals(0, persistenceEventCount())
    }

    /** When the database is the thing that is down, the diagnostics about it cannot be written either - they must wait, not vanish. */
    @Test
    fun eventsThatCouldNotBeWrittenDuringTheOutageAreWrittenOnceTheDatabaseIsBack() = runTest {
        val writer = writer(capacity = 3)
        flakyPoints.failure = IllegalStateException("disk hiccup")
        flakyDiagnostics.failing = true
        (0L..7L).forEach { writer.write(point(it)); tick() }
        assertEquals("nothing could be written yet", 0, persistenceEventCount())

        flakyPoints.failure = null
        flakyDiagnostics.failing = false
        tick(60_000L)
        writer.retryPending()

        assertEquals(1, events(RawPointWriter.EVENT_INSERT_FAILED).size)
        assertEquals(1, events(RawPointWriter.EVENT_BUFFER_OVERFLOW).size)
        assertEquals(1, events(RawPointWriter.EVENT_RECOVERED).size)
        assertEquals(1, events(RawPointWriter.EVENT_DATA_LOSS).size)
    }

    @Test
    fun theStateListenerHearsOnlyRealChangesNotEveryFix() = runTest {
        val writer = writer()
        flakyPoints.failure = IllegalStateException("disk hiccup")
        (0L..29L).forEach { writer.write(point(it)); tick() }
        flakyPoints.failure = null
        tick(60_000L)
        writer.retryPending()

        assertEquals(listOf(PersistenceLevel.DEGRADED, PersistenceLevel.HEALTHY), states.map { it.level })
    }

    @Test
    fun aListenerThatThrowsNeverBreaksTheRecording() = runTest {
        val writer = RawPointWriter(
            rawTrackPointDao = flakyPoints, diagnosticEventDao = flakyDiagnostics, clock = clock,
            idGenerator = FakeIdGenerator(prefix = "diag"), captureId = captureId,
            onStateChanged = { throw IllegalStateException("notification failed") }
        )
        flakyPoints.failure = IllegalStateException("disk hiccup")
        writer.write(point(0)); tick()
        flakyPoints.failure = null
        tick(60_000L)

        writer.write(point(1))

        assertEquals(listOf(0L, 1), stored())
    }
}
