package com.mototriptracker.app.experiment

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * A real motorcycle ride can run for a long time with the screen off - long
 * enough for Android to kill the app process in the background (this
 * project's own test device is known to do this aggressively). Without this,
 * a kill mid-ride would silently lose the entire in-progress field-test
 * session - the harness's own state lived only in the ViewModel's memory.
 * Single well-known file, not per-session: at most one harness session is
 * ever active at a time.
 */
class AndroidFieldTestHarnessStateStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) : FieldTestHarnessStateStore {

    private val file: File
        get() = File(context.filesDir, "field-tests/harness-in-progress.json")

    override fun save(state: PersistedHarnessState) {
        file.parentFile?.mkdirs()
        file.writeText(FieldTestHarnessStateJson.toJson(state))
    }

    override fun load(): PersistedHarnessState? {
        if (!file.exists()) return null
        return FieldTestHarnessStateJson.fromJson(file.readText())
    }

    override fun clear() {
        file.delete()
    }
}
