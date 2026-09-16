package com.mototriptracker.app.testing

import com.mototriptracker.app.core.database.entity.TripCaptureEntity
import com.mototriptracker.app.core.model.CaptureStatus
import com.mototriptracker.app.core.model.DetectorVersion
import com.mototriptracker.app.core.model.LocationProfileVersion
import com.mototriptracker.app.core.model.StartSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * F0.12 §5.6 requires *both* DB flavors to exist and work — this proves the
 * file-backed one specifically survives a close/reopen, which is the whole
 * point of using it over the in-memory one for migration/recovery-style
 * tests later.
 */
@RunWith(RobolectricTestRunner::class)
class TestDatabaseFactoryTest {

    @Test
    fun fileBackedDatabaseSurvivesCloseAndReopen() = runTest {
        val dbFile = File.createTempFile("test-database-factory", ".db")
        try {
            val capture = TripCaptureEntity(
                id = "capture-1",
                status = CaptureStatus.ACTIVE,
                startedAt = 1_000L,
                endedAt = null,
                startElapsedRealtimeNanos = 1_000L,
                endElapsedRealtimeNanos = null,
                localTimeZoneId = "UTC",
                startSource = StartSource.MANUAL,
                endSource = null,
                detectorVersion = DetectorVersion(1),
                locationProfileVersion = LocationProfileVersion(1),
                createdAt = 1_000L,
                updatedAt = 1_000L
            )

            val first = TestDatabaseFactory.createFileBacked(dbFile)
            first.tripCaptureDao().startCaptureIfNoneActive(capture)
            first.close()

            val reopened = TestDatabaseFactory.createFileBacked(dbFile)
            val reloaded = reopened.tripCaptureDao().findById("capture-1")
            reopened.close()

            assertNotNull(reloaded)
            assertEquals(CaptureStatus.ACTIVE, reloaded?.status)
        } finally {
            dbFile.delete()
        }
    }
}
