package com.mototriptracker.app.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mototriptracker.app.core.theme.AccentColor
import com.mototriptracker.app.core.theme.Appearance
import com.mototriptracker.app.core.theme.ThemeBase
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * SET-002: the theme the person picked - a base and an accent. Stored by name, so a value this build does not know
 * (a colour dropped or renamed later) falls back to the default instead of failing, and a store that cannot be read
 * gives the default rather than an error: the worst case is the usual black and orange, never a broken launch.
 */
class AppearancePreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val appearance: Flow<Appearance> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences ->
            Appearance(
                base = ThemeBase.entries.firstOrNull { it.name == preferences[KEY_BASE] } ?: Appearance.Default.base,
                accent = AccentColor.entries.firstOrNull { it.name == preferences[KEY_ACCENT] } ?: Appearance.Default.accent
            )
        }

    suspend fun set(appearance: Appearance) {
        dataStore.edit {
            it[KEY_BASE] = appearance.base.name
            it[KEY_ACCENT] = appearance.accent.name
        }
    }

    companion object {
        private val KEY_BASE = stringPreferencesKey("appearance_base")
        private val KEY_ACCENT = stringPreferencesKey("appearance_accent")
    }
}
