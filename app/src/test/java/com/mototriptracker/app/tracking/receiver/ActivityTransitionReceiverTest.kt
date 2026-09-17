package com.mototriptracker.app.tracking.receiver

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.common.AndroidDispatcherProvider
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.common.FakeIdGenerator
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.tracking.activityrecognition.ActivityTransitionRecorder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
