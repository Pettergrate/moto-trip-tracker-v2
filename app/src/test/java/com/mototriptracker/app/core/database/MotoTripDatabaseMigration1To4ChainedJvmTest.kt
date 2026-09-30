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
 * MOTO-001/`ADR-024` follow-up to `MIG-001`'s own `MotoTripDatabaseMigration1To3ChainedJvmTest`: a real phone that
 * skipped every release since v1 now jumps straight to v4 in one open - `DatabaseModule.provideDatabase`'s
 * `addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)` is what Room chains for that case. Same real exported
 * schema on the JVM, all three `ALTER TABLE` statements applied in sequence, one open, no data lost.
 */
@RunWith(RobolectricTestRunner::class)
class MotoTripDatabaseMigration1To4ChainedJvmTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MotoTripDatabase::class.java
    )

    @Test
    fun aV1DatabaseSurvivesJumpingStraightToV4InOneOpen() {
        val dbName = ApplicationProvider.getApplicationContext<Context>().getDatabasePath("migration-1-4-chained").absolutePath

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
                "INSERT INTO motorcycle (id, name, make, model, year, isArchived, createdAt, updatedAt) " +
                    "VALUES ('moto-1', 'Enduro', 'Honda', 'CRF250L', 2021, 0, 1000, 1000)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        migrated.query("SELECT distanceM, startElevationM FROM trip_statistics WHERE tripId = 'trip-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("v1's own metric survives all three steps", 1500.5, cursor.getDouble(0), 0.0001)
            assertTrue("MIGRATION_1_2's column exists and stays unknown", cursor.isNull(1))
        }
        migrated.query("SELECT name, vehicleType FROM motorcycle WHERE id = 'moto-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("v1's own motorcycle row survives all three steps", "Enduro", cursor.getString(0))
            assertEquals("MIGRATION_3_4's column exists and defaults honestly", "MOTORCYCLE", cursor.getString(1))
        }
    }
}
