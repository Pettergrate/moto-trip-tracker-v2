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
 * MOTO-001/`ADR-024`: proves `MIGRATION_3_4` the same way `MIG-001` already proved `MIGRATION_1_2`/`MIGRATION_2_3` -
 * against the real exported schema JSON, on the JVM, no device needed. `motorcycle` genuinely has no real rows in
 * any shipped build (confirmed by grep before this task), but the migration is still proven against a real row so
 * the "NOT NULL DEFAULT" itself - not just the empty-table case - is exercised.
 */
@RunWith(RobolectricTestRunner::class)
class MotoTripDatabaseMigration3To4JvmTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MotoTripDatabase::class.java
    )

    @Test
    fun migrate3To4AddsVehicleTypeDefaultingToMotorcycleWithoutLosingExistingRows() {
        val dbName = ApplicationProvider.getApplicationContext<Context>().getDatabasePath("migration-3-4").absolutePath

        helper.createDatabase(dbName, 3).apply {
            execSQL(
                "INSERT INTO motorcycle (id, name, make, model, year, isArchived, createdAt, updatedAt) " +
                    "VALUES ('moto-1', 'Enduro', 'Honda', 'CRF250L', 2021, 0, 1000, 1000)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4)

        migrated.query("SELECT id, name, make, vehicleType FROM motorcycle WHERE id = 'moto-1'").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("the pre-existing row is untouched", "Enduro", cursor.getString(1))
            assertEquals("a pre-existing row not naming a type gets the honest default, not a guess about which vehicle it was", "MOTORCYCLE", cursor.getString(3))
        }

        // The new column really is usable from the migrated schema, for a non-default value too.
        migrated.execSQL(
            "INSERT INTO motorcycle (id, name, make, model, year, isArchived, createdAt, updatedAt, vehicleType) " +
                "VALUES ('car-1', 'Weekend car', NULL, NULL, NULL, 0, 2000, 2000, 'CAR')"
        )
        migrated.query("SELECT vehicleType FROM motorcycle WHERE id = 'car-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("CAR", cursor.getString(0))
        }
    }
}
