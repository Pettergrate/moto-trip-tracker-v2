package com.mototriptracker.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * MET-001. FND-003 wired the schema-export/`androidTest` asset plumbing
 * ("added the moment a real v1->v2 migration exists" - its own KDoc) for
 * exactly this: a real device/emulator, not Robolectric, running
 * [MIGRATION_1_2] against the actual exported v1 schema checked into
 * `app/schemas/`, proving a real user's existing database survives the
 * upgrade rather than trusting the additive `ALTER TABLE` calls by
 * inspection alone.
 */
class MotoTripDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MotoTripDatabase::class.java
    )

    @Test
    fun migrate1To2AddsNullableStartAndEndElevationColumnsWithoutLosingExistingRows() {
        val dbName = "migration-test"

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
                    "('trip-1', 0, 1000, 4200.0, 600000, NULL, NULL, 0, NULL, NULL, NULL, 100.0, 120.0, NULL, NULL, 10, 0, 0, 0)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        migrated.query("SELECT distanceM, minElevationM, startElevationM, endElevationM FROM trip_statistics WHERE tripId = 'trip-1'").use { cursor ->
            assertTrue("the pre-existing row must survive the migration", cursor.moveToFirst())
            assertEquals(4200.0, cursor.getDouble(0), 0.0001)
            assertEquals(100.0, cursor.getDouble(1), 0.0001)
            assertTrue("no backfill is attempted - existing rows get NULL, not a fabricated value", cursor.isNull(2))
            assertTrue(cursor.isNull(3))
        }
    }
}
