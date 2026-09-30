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
 * MIG-001: the same job `MotoTripDatabaseMigrationJvmTest` does for `MIGRATION_2_3`, for `MIGRATION_1_2` - which had
 * only ever been proven by the `androidTest` twin (`MotoTripDatabaseMigrationTest`), never actually run
 * (`connectedAndroidTest` once wiped the owner's real database, `MET-001`), and never on the JVM at all. Same real
 * exported schema JSON (`app/schemas`), no device needed.
 */
@RunWith(RobolectricTestRunner::class)
class MotoTripDatabaseMigration1To2JvmTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MotoTripDatabase::class.java
    )

    @Test
    fun migrate1To2AddsNullableStartAndEndElevationColumnsWithoutLosingExistingRows() {
        val dbName = ApplicationProvider.getApplicationContext<Context>().getDatabasePath("migration-1-2").absolutePath

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
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        migrated.query(
            "SELECT tripId, distanceM, minElevationM, startElevationM, endElevationM FROM trip_statistics WHERE tripId = 'trip-1'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("trip-1", cursor.getString(0))
            assertEquals("the pre-existing metric is untouched", 1500.5, cursor.getDouble(1), 0.0001)
            assertEquals("a pre-existing metric with the same shape (nullable REAL) is untouched too", 100.0, cursor.getDouble(2), 0.0001)
            assertTrue("nothing recorded before v2 can say what elevation the trip started at - it stays unknown (NULL), never a guessed 0", cursor.isNull(3))
            assertTrue(cursor.isNull(4))
        }

        // The new columns really are usable from the migrated schema.
        migrated.execSQL(
            "INSERT INTO trip_statistics (tripId, processingVersion, computedAt, distanceM, totalDurationMs, movingDurationMs, " +
                "stoppedDurationMs, manualPauseDurationMs, maxSpeedMps, averageSpeedMps, averageMovingSpeedMps, minElevationM, " +
                "maxElevationM, ascentM, descentM, validPointCount, suspectPointCount, rejectedPointCount, gapCount, " +
                "startElevationM, endElevationM) VALUES " +
                "('trip-1', 1, 3000, 1500.5, 60000, 55000, 5000, 0, 12.3, 8.1, 8.9, 100.0, 150.0, 60.0, 10.0, 40, 2, 0, 0, 120.0, 140.0)"
        )
        migrated.query("SELECT startElevationM, endElevationM FROM trip_statistics WHERE tripId = 'trip-1' AND processingVersion = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(120.0, cursor.getDouble(0), 0.0001)
            assertEquals(140.0, cursor.getDouble(1), 0.0001)
        }
    }
}
