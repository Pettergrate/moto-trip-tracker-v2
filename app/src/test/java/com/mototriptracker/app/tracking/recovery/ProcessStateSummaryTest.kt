package com.mototriptracker.app.tracking.recovery

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DIA-004 / F0.13 §9.1: the compact process-state hint, and how it is read back from a process that no longer exists. */
class ProcessStateSummaryTest {

    private class RecordingPublisher : ProcessStateSummaryPublisher {
        val published = mutableListOf<String>()
        override fun publish(encoded: String) {
            published += encoded
        }
    }

    @Test
    fun theDefaultSaysNothingIsInFlight() {
        assertEquals("v=1;cap=N;pause=N;sig=OK;apx=N;db=OK;app=0.1-w0", ProcessStateSummary().encode("0.1-w0"))
    }

    @Test
    fun everyFactIsEncodedInAFixedVocabulary() {
        val summary = ProcessStateSummary(
            recording = true, paused = true, signal = ProcessStateSummary.SIGNAL_OFF, approximateOnly = true,
            persistence = ProcessStateSummary.PERSISTENCE_CRITICAL
        )

        assertEquals("v=1;cap=Y;pause=Y;sig=OFF;apx=Y;db=CRIT;app=1.2.3", summary.encode("1.2.3"))
    }

    @Test
    fun evenTheWorstCaseFitsTheApiLimitOf128Bytes() {
        val worst = ProcessStateSummary(
            recording = true, paused = true, signal = ProcessStateSummary.SIGNAL_OFF, approximateOnly = true,
            persistence = ProcessStateSummary.PERSISTENCE_CRITICAL
        )

        val encoded = worst.encode("x".repeat(500))

        assertTrue("${encoded.length} bytes", encoded.toByteArray().size <= ProcessStateSummary.MAX_BYTES)
    }

    @Test
    fun theVersionNameCannotSmuggleAnythingBeyondTheAllowlist() {
        val encoded = ProcessStateSummary().encode("1.0 \"weird\"\n;evil=1")

        assertEquals("v=1;cap=N;pause=N;sig=OK;apx=N;db=OK;app=1.0weirdevil1", encoded)
    }

    @Test
    fun whatWasPublishedIsReadBackIdenticallyFromTheDeadProcess() {
        val encoded = ProcessStateSummary(recording = true, persistence = ProcessStateSummary.PERSISTENCE_DEGRADED).encode("0.1-w0")

        assertEquals(encoded, AndroidProcessExitReasonReader.decodeStateSummary(encoded.toByteArray()))
    }

    @Test
    fun aSummaryThatIsMissingTooLongOrHasUnexpectedCharactersIsDropped() {
        assertNull(AndroidProcessExitReasonReader.decodeStateSummary(null))
        assertNull(AndroidProcessExitReasonReader.decodeStateSummary(ByteArray(0)))
        assertNull("over the API limit", AndroidProcessExitReasonReader.decodeStateSummary(ByteArray(129) { 'a'.code.toByte() }))
        assertNull("not our vocabulary", AndroidProcessExitReasonReader.decodeStateSummary("lat=10.5,lon=-20.2".toByteArray()))
        assertNull("binary noise", AndroidProcessExitReasonReader.decodeStateSummary(byteArrayOf(0x01, 0x02)))
    }

    @Test
    fun theTrackerPublishesOnlyWhenTheSummaryActuallyChanges() {
        val publisher = RecordingPublisher()
        val tracker = ProcessStateTracker(publisher, AppVersionName())

        tracker.update { it.copy(recording = true) }
        tracker.update { it.copy(recording = true) } // nothing changed: not a transition
        tracker.update { it.copy(recording = true, paused = false) }
        tracker.update { it.copy(paused = true) }

        assertEquals(2, publisher.published.size)
        assertTrue(publisher.published[0].contains("cap=Y;pause=N"))
        assertTrue(publisher.published[1].contains("pause=Y"))
    }

    @Test
    fun resettingAfterARecordingPublishesTheIdleState() {
        val publisher = RecordingPublisher()
        val tracker = ProcessStateTracker(publisher, AppVersionName())
        tracker.update { it.copy(recording = true) }

        tracker.reset()

        assertTrue(publisher.published.last().contains("cap=N"))
    }

    @Test
    fun everyPlatformReasonCodeHasAStableNameAndNothingIsGuessed() {
        assertEquals("CRASH", AndroidProcessExitReasonReader.reasonName(ApplicationExitInfo.REASON_CRASH))
        assertEquals("SIGNALED", AndroidProcessExitReasonReader.reasonName(ApplicationExitInfo.REASON_SIGNALED))
        assertEquals("USER_REQUESTED", AndroidProcessExitReasonReader.reasonName(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals("PERMISSION_CHANGE", AndroidProcessExitReasonReader.reasonName(ApplicationExitInfo.REASON_PERMISSION_CHANGE))
        assertEquals("PACKAGE_UPDATED", AndroidProcessExitReasonReader.reasonName(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
        assertEquals("ANR", AndroidProcessExitReasonReader.reasonName(ApplicationExitInfo.REASON_ANR))
        assertEquals("UNRECOGNISED_4242", AndroidProcessExitReasonReader.reasonName(4242))
    }

    @Test
    fun importanceSaysWhetherARecordingWasBeingKeptAlive() {
        assertEquals("FOREGROUND_SERVICE", AndroidProcessExitReasonReader.importanceName(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        assertEquals("CACHED", AndroidProcessExitReasonReader.importanceName(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED))
        assertEquals("IMPORTANCE_777", AndroidProcessExitReasonReader.importanceName(777))
    }
}
