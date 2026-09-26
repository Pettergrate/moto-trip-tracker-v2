package com.mototriptracker.app.tracking.recovery

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * REC-004/F0.10 §9.2: "se debe guardar un identificador/timestamp del último
 * exit reason procesado para no aplicar la misma decisión repetidamente."
 * A tiny preference (F0.8 §12: DataStore is for small settings like this,
 * never a Trip's own state).
 */
interface HandledExitStore {
    suspend fun lastHandledTimestamp(): Long
    suspend fun markHandled(timestampMillis: Long)

    /** REC-003: the `BOOT_COUNT` seen by the previous run, or `null` if there was none (first run). */
    suspend fun lastSeenBootCount(): Int?
    suspend fun setLastSeenBootCount(bootCount: Int)

    /** DIA-004: the newest process exit already written to the diagnostic evidence - a cursor separate from [lastHandledTimestamp], which is recovery's. */
    suspend fun lastRecordedExitTimestamp(): Long
    suspend fun markExitRecorded(timestampMillis: Long)
}

class DataStoreHandledExitStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : HandledExitStore {
    override suspend fun lastHandledTimestamp(): Long = dataStore.data.first()[KEY] ?: 0L

    override suspend fun markHandled(timestampMillis: Long) {
        dataStore.edit { it[KEY] = timestampMillis }
    }

    override suspend fun lastSeenBootCount(): Int? = dataStore.data.first()[BOOT_KEY]

    override suspend fun setLastSeenBootCount(bootCount: Int) {
        dataStore.edit { it[BOOT_KEY] = bootCount }
    }

    override suspend fun lastRecordedExitTimestamp(): Long = dataStore.data.first()[RECORDED_KEY] ?: 0L

    override suspend fun markExitRecorded(timestampMillis: Long) {
        dataStore.edit { it[RECORDED_KEY] = timestampMillis }
    }

    private companion object {
        val KEY = longPreferencesKey("last_handled_process_exit_timestamp")
        val RECORDED_KEY = longPreferencesKey("last_recorded_process_exit_timestamp")
        val BOOT_KEY = intPreferencesKey("last_seen_boot_count")
    }
}
