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

/**
 * REC-005 follow-up: `raw_track_point` gains `isApproximateLocation` (see the entity). Additive and
 * nullable like [MIGRATION_1_2]: no backfill is attempted, because nothing recorded before this
 * version can say whether precise location was granted - existing rows stay `NULL` (unknown), never
 * a guessed `0`/`false` (ADR-016). The raw points themselves are untouched (ADR-006).
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE raw_track_point ADD COLUMN isApproximateLocation INTEGER")
    }
}
