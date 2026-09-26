package com.mototriptracker.app.tracking.recovery

import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.database.dao.DiagnosticEventDao
import com.mototriptracker.app.core.database.entity.DiagnosticEventEntity
import com.mototriptracker.app.core.model.DiagnosticCategory
import com.mototriptracker.app.core.model.DiagnosticSeverity
import com.mototriptracker.app.testing.TestDatabaseFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DIA-004 / F0.13 §9, §18.1 "process recovery registra exit reason cuando está disponible": how earlier
 * processes ended becomes durable diagnostic evidence - without ever deciding anything about a trip.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessExitRecorderTest {

    private lateinit var db: MotoTripDatabase
    private lateinit var reader: FakeExitReader
    private lateinit var store: FakeHandledExitStore
    private lateinit var recorder: ProcessExitRecorder

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory()
        reader = FakeExitReader(exit = null)
        store = FakeHandledExitStore()
        recorder = ProcessExitRecorder(reader, store, db.diagnosticEventDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun exit(pid: Int, at: Long, reason: String, importance: String = "FOREGROUND_SERVICE", summary: String? = null) =
        ProcessExitRecord(id = "process-exit-$pid-$at", timestampMillis = at, reason = reason, importance = importance, status = 9, stateSummary = summary)

    private suspend fun exitEvents() =
        db.diagnosticEventDao().findAll().filter { it.eventType == ProcessExitRecorder.EVENT_PROCESS_EXIT }.sortedBy { it.occurredAt }

    @Test
    fun eachNewExitBecomesOneEventAboutWhenTheProcessEndedNotWhenItWasNoticed() = runTest {
        reader.records = listOf(exit(30, at = 3_000L, reason = "USER_REQUESTED"), exit(20, at = 2_000L, reason = "CRASH"))

        assertEquals(2, recorder.record())

        val events = exitEvents()
        assertEquals(listOf(2_000L, 3_000L), events.map { it.occurredAt })
        assertEquals(listOf("CRASH", "USER_REQUESTED"), events.map { it.reasonCode })
        assertTrue(events.all { it.category == DiagnosticCategory.RECOVERY_SYSTEM })
    }

    @Test
    fun theStateTheProcessWasInWhenItDiedTravelsWithTheEvent() = runTest {
        val summary = "v=1;cap=Y;pause=N;sig=OK;apx=N;db=DEG;app=0.1-w0"
        reader.records = listOf(exit(20, at = 2_000L, reason = "SIGNALED", summary = summary))

        recorder.record()

        val event = exitEvents().single()
        assertEquals(summary, event.metadata["stateSummary"])
        assertEquals("FOREGROUND_SERVICE", event.metadata["importance"])
        assertEquals("9", event.metadata["status"])
    }

    @Test
    fun anExitWithNoSummaryHasNoSummaryKeyRatherThanAnEmptyOne() = runTest {
        reader.records = listOf(exit(20, at = 2_000L, reason = "CRASH", summary = null))

        recorder.record()

        assertFalse(exitEvents().single().metadata.containsKey("stateSummary"))
    }

    @Test
    fun runningAgainRecordsNothingNewAndOnlyLaterExitsAreAdded() = runTest {
        reader.records = listOf(exit(20, at = 2_000L, reason = "CRASH"))
        recorder.record()

        assertEquals("already recorded", 0, recorder.record())

        reader.records = listOf(exit(30, at = 3_000L, reason = "LOW_MEMORY"), exit(20, at = 2_000L, reason = "CRASH"))
        assertEquals(1, recorder.record())
        assertEquals(2, exitEvents().size)
        assertEquals(3_000L, store.recorded)
    }

    /** The cursor is only an optimisation: a lost one must not turn history into duplicates. */
    @Test
    fun aLostCursorCannotDuplicateAnExitBecauseTheEventIdComesFromTheExitItself() = runTest {
        reader.records = listOf(exit(20, at = 2_000L, reason = "CRASH"))
        recorder.record()
        store.recorded = 0L // DataStore cleared

        recorder.record()

        assertEquals(1, exitEvents().size)
    }

    /** A failed write must be retried at the next start, never skipped by an advanced cursor. */
    @Test
    fun aFailedWriteDoesNotAdvanceTheCursorSoItIsRetried() = runTest {
        val realDao = db.diagnosticEventDao()
        var failing = true
        val flaky = object : DiagnosticEventDao by realDao {
            override suspend fun insertOrIgnore(event: DiagnosticEventEntity): Long {
                if (failing) throw IllegalStateException("disk hiccup")
                return realDao.insertOrIgnore(event)
            }
        }
        reader.records = listOf(exit(20, at = 2_000L, reason = "CRASH"))

        runCatching { ProcessExitRecorder(reader, store, flaky).record() }
        assertEquals("cursor untouched", 0L, store.recorded)

        failing = false
        assertEquals(1, ProcessExitRecorder(reader, store, flaky).record())
        assertEquals(1, exitEvents().size)
    }

    @Test
    fun whenThePlatformKnowsNothingNothingIsRecordedAndNothingBreaks() = runTest {
        reader.records = emptyList() // API < 30, or an OEM that reports nothing

        assertEquals(0, recorder.record())
        assertTrue(exitEvents().isEmpty())
    }

    @Test
    fun severityFollowsTheKindOfEnd() {
        assertEquals(DiagnosticSeverity.ERROR, ProcessExitRecorder.severityFor("CRASH"))
        assertEquals(DiagnosticSeverity.ERROR, ProcessExitRecorder.severityFor("ANR"))
        assertEquals(DiagnosticSeverity.WARN, ProcessExitRecorder.severityFor("LOW_MEMORY"))
        assertEquals(DiagnosticSeverity.WARN, ProcessExitRecorder.severityFor("SIGNALED"))
        assertEquals(DiagnosticSeverity.INFO, ProcessExitRecorder.severityFor("USER_REQUESTED"))
        assertEquals(DiagnosticSeverity.INFO, ProcessExitRecorder.severityFor("PACKAGE_UPDATED"))
        assertEquals("an unrecognised code is information, not an alarm", DiagnosticSeverity.INFO, ProcessExitRecorder.severityFor("UNRECOGNISED_99"))
    }

    /** F0.13 §4.1: codes and counters only - the platform's free-text description is never copied. */
    @Test
    fun theRecordCarriesNoFreeTextField() {
        val fields = ProcessExitRecord::class.java.declaredFields.map { it.name }
        assertNull("no description", fields.firstOrNull { it.contains("description", ignoreCase = true) })
    }
}
