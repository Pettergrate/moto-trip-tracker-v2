package com.mototriptracker.app.core.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MIG-001: a real phone that skips an app update entirely (or was offline for both releases) jumps straight from v1
 * to v3 in one open - `DatabaseModule.provideDatabase`'s `addMigrations(MIGRATION_1_2, MIGRATION_2_3)` is what Room
 * chains for that case, and the two migrations had only ever been proven one at a time. This is the same real exported
 * schema on the JVM, run end to end: both `ALTER TABLE` statements applied in sequence, one open, no data lost.
 */
@RunWith(RobolectricTestRunner::class)
class MotoTripDatabaseMigration1To3ChainedJvmTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MotoTripDatabase::class.java
    )

    @Test
    fun aV1DatabaseSurvivesJumpingStraightToV3InOneOpen() {
        val dbName = ApplicationProvider.getApplicationContext<Context>().getDatabasePath("migration-1-3-chained").absolutePath

        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO trip (id, status, name, isFavorite, motorcycleId, routeId, notes, createdAt, updatedAt, deletedAt) " +
                    "VALUES ('trip-1', 'COMPLETED', NULL, 0, NULL, NULL, NULL, 1000, 1000, NULL)"
            )
            execSQL(
                "INSERT INTO trip_statistics (tripId, processingVersion, computedAt, distanceM, totalDurationMs, " +
                    "movingDurationMs, stoppedDurationMs, manualPauseDurationMs, maxSpeedMps, averageSpeedMps, " +
                    "averageMovingSpeedMps, minElevationM, maxElevationM, ascentM, descentM, validPointCount, " +
                    "suspectPointCount, rejectedPointCount, gapCount) VALUES " +
                    "('trip-1', 0, 2000, 1500.5, 60000, 55000, 5000, 0, 12.3, 8.1, 8.9, 100.0, 150.0, 60.0, 10.0, 40, 2, 0, 0)"
            )
            execSQL(
                "INSERT INTO trip_capture (id, status, startedAt, endedAt, startElapsedRealtimeNanos, endElapsedRealtimeNanos, " +
                    "localTimeZoneId, startSource, endSource, detectorVersion, locationProfileVersion, createdAt, updatedAt) " +
                    "VALUES ('cap-1', 'COMPLETED', 1000, 9000, 1000000000, 9000000000, 'UTC', 'MANUAL', 'MANUAL', 0, 0, 1000, 9000)"
            )
            execSQL(
                "INSERT INTO raw_track_point (captureId, sequenceNumber, capturedAt, elapsedRealtimeNanos, latitude, longitude, " +
                    "horizontalAccuracyM, provider, isMock, requestProfileId, detectorStateSnapshot) " +
                    "VALUES ('cap-1', 0, 1000, 1000000000, 10.5, -20.25, 5.0, 'fused', 0, 'profile-1', 'TRACKING')"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_1_2, MIGRATION_2_3)

        migrated.query(
            "SELECT distanceM, minElevationM, startElevationM FROM trip_statistics WHERE tripId = 'trip-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("v1's own metric survives both steps", 1500.5, cursor.getDouble(0), 0.0001)
            assertEquals(100.0, cursor.getDouble(1), 0.0001)
            assertTrue("MIGRATION_1_2's column exists and stays unknown", cursor.isNull(2))
        }
        migrated.query(
            "SELECT sequenceNumber, horizontalAccuracyM, isApproximateLocation FROM raw_track_point WHERE captureId = 'cap-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertEquals("v1's raw point is untouched (ADR-006)", 5.0, cursor.getDouble(1), 0.0001)
            assertTrue("MIGRATION_2_3's column exists and stays unknown", cursor.isNull(2))
        }
    }
}
