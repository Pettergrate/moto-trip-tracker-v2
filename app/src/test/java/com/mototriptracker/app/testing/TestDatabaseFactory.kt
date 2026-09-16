package com.mototriptracker.app.testing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.database.MotoTripDatabase
import java.io.File

/**
 * F0.12 §5.6: both DB flavors tests need, in one place instead of every DAO
 * test re-discovering the same setup (and the same Robolectric/driver
 * quirks — see TripCaptureDaoTest's original version for what was learned
 * building this).
 */
object TestDatabaseFactory {

    /** Fast, wiped-on-close. What most DAO/unit tests want. */
    fun createInMemory(): MotoTripDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, MotoTripDatabase::class.java).build()
    }

    /**
     * Persists to a real temp file — for migration, process-recreation and
     * transaction/recovery-style tests that need the database to survive
     * being closed and reopened. Caller is responsible for deleting
     * [dbFile] afterward.
     */
    fun createFileBacked(dbFile: File): MotoTripDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.databaseBuilder(context, MotoTripDatabase::class.java, dbFile.absolutePath).build()
    }
}
