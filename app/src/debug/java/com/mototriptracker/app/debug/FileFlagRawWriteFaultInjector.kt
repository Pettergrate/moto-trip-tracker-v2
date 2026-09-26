package com.mototriptracker.app.debug

import android.database.sqlite.SQLiteFullException
import com.mototriptracker.app.tracking.persistence.RawWriteFaultInjector
import java.io.File

/**
 * REC-006, **debug builds only** (this file is in `src/debug`): makes raw-point inserts fail while a
 * flag file exists in the app's private `files/` directory, so the persistence-failure path can be
 * exercised on a real phone with no effect on the stored data and no change to the phone's storage:
 *
 * ```
 * adb shell run-as com.mototriptracker.app sh -c 'echo transient > files/fail_raw_writes'   # inserts throw
 * adb shell run-as com.mototriptracker.app sh -c 'echo full > files/fail_raw_writes'        # SQLiteFullException
 * adb shell run-as com.mototriptracker.app rm files/fail_raw_writes                         # back to normal
 * adb shell run-as com.mototriptracker.app sh -c 'echo 10 > files/raw_buffer_capacity'      # small buffer, read when a trip starts
 * ```
 *
 * Nothing here runs unless someone creates one of those files; the app's private directory is not
 * reachable by other apps.
 */
class FileFlagRawWriteFaultInjector(private val dir: File) : RawWriteFaultInjector {

    override fun beforeRawInsert() {
        val flag = File(dir, FAIL_FLAG_FILE)
        if (!flag.exists()) return
        when (flag.readText().trim()) {
            "full" -> throw SQLiteFullException("injected: database or disk is full")
            else -> throw IllegalStateException("injected transient write failure")
        }
    }

    override fun bufferCapacity(): Int? =
        File(dir, CAPACITY_FILE).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    companion object {
        const val FAIL_FLAG_FILE = "fail_raw_writes"
        const val CAPACITY_FILE = "raw_buffer_capacity"
    }
}
