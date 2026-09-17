package com.mototriptracker.app.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * AUTO-001: the single persisted setting `CapabilityInputs.autoTrackingEnabledByUser`
 * needs a real backing store for. F0.8 §12/ADR: DataStore is reserved for
 * small preferences like this one, never a Trip's own state.
 *
 * Defaults to `false` when absent (F0.11 §7.2's onboarding sequence treats
 * Auto Tracking as a deliberate, explicit opt-in step) - a clean install
 * with no onboarding run yet correctly resolves to MANUAL, never FULL_AUTO/
 * ASSISTED_AUTO by accident.
 */
class AutoTrackingPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val autoTrackingEnabled: Flow<Boolean> =
        dataStore.data.map { preferences -> preferences[KEY_AUTO_TRACKING_ENABLED] ?: false }

    suspend fun setAutoTrackingEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_AUTO_TRACKING_ENABLED] = enabled }
    }

    companion object {
        private val KEY_AUTO_TRACKING_ENABLED = booleanPreferencesKey("auto_tracking_enabled")
    }
}
