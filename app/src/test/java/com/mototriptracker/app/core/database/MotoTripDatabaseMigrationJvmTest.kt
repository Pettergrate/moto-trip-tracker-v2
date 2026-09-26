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
 * The same job as `androidTest/.../MotoTripDatabaseMigrationTest`, run on the JVM against the real
 * exported schema JSON (`app/schemas`, wired as unit-test assets), so a migration can be proven
 * without a device. That matters here: the only phone available holds the owner's real trips, and
 * `connectedAndroidTest` once wiped them (`MET-001`).
 */
@RunWith(RobolectricTestRunner::class)
class MotoTripDatabaseMigrationJvmTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MotoTripDatabase::class.java
    )

    @Test
    fun migrate2To3AddsANullableApproximateMarkerAndLeavesEveryExistingPointUntouched() {
        // Room 2.8's helper compares the database name with the path it opens; Robolectric hands back an
        // absolute path, so the absolute path is what has to be used as the name.
        val dbName = ApplicationProvider.getApplicationContext<Context>().getDatabasePath("migration-2-3").absolutePath

        helper.createDatabase(dbName, 2).apply {
            execSQL(
                "INSERT INTO trip_capture (id, status, startedAt, endedAt, startElapsedRealtimeNanos, endElapsedRealtimeNanos, " +
                    "localTimeZoneId, startSource, endSource, detectorVersion, locationProfileVersion, createdAt, updatedAt) " +
                    "VALUES ('cap-1', 'COMPLETED', 1000, 9000, 1000000000, 9000000000, 'UTC', 'MANUAL', 'MANUAL', 0, 0, 1000, 9000)"
            )
            for (seq in 0..2) {
                execSQL(
                    "INSERT INTO raw_track_point (captureId, sequenceNumber, capturedAt, elapsedRealtimeNanos, latitude, longitude, " +
                        "horizontalAccuracyM, provider, isMock, requestProfileId, detectorStateSnapshot) " +
                        "VALUES ('cap-1', $seq, ${1000 + seq * 1000}, ${1_000_000_000L + seq * 1_000_000_000L}, 10.5, -20.25, " +
                        "${5 + seq}.0, 'fused', 0, 'profile-1', 'TRACKING')"
                )
            }
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3)

        migrated.query(
            "SELECT sequenceNumber, horizontalAccuracyM, provider, isApproximateLocation FROM raw_track_point ORDER BY sequenceNumber"
        ).use { cursor ->
            assertEquals("no point may be lost or duplicated by the migration", 3, cursor.count)
            var seq = 0
            while (cursor.moveToNext()) {
                assertEquals(seq, cursor.getInt(0))
                assertEquals("the recorded accuracy is untouched", (5 + seq).toDouble(), cursor.getDouble(1), 0.0001)
                assertEquals("fused", cursor.getString(2))
                assertTrue("nothing recorded before v3 can say - it stays unknown (NULL), never a guessed false", cursor.isNull(3))
                seq++
            }
        }

        // The new column really is usable from the migrated schema.
        migrated.execSQL(
            "INSERT INTO raw_track_point (captureId, sequenceNumber, capturedAt, elapsedRealtimeNanos, latitude, longitude, " +
                "horizontalAccuracyM, requestProfileId, detectorStateSnapshot, isApproximateLocation) " +
                "VALUES ('cap-1', 3, 4000, 4000000000, 10.5, -20.25, 2000.0, 'profile-1', 'TRACKING', 1)"
        )
        migrated.query("SELECT isApproximateLocation FROM raw_track_point WHERE sequenceNumber = 3").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }
}
