package com.mototriptracker.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * MET-001/FR-MET-009: adds start/end elevation to `trip_statistics` - the
 * first real migration this schema has ever needed (F0.8 §16-17's "later
 * concern," deferred since FND-003, is now). Additive-only and both
 * nullable, so no backfill is needed or attempted - existing rows simply
 * get `NULL`, matching `ElevationCalculator`'s own "unknown metric stays
 * null" posture rather than a fabricated `0`.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trip_statistics ADD COLUMN startElevationM REAL")
        db.execSQL("ALTER TABLE trip_statistics ADD COLUMN endElevationM REAL")
    }
}
