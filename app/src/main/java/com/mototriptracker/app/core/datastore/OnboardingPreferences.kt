package com.mototriptracker.app.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * PERM-001 / `privacy-permissions.md` §19.1-19.2: the two things the app remembers so it never repeats itself -
 * that the welcome screen was shown, and that notifications were already asked for once at the first Start
 * ("no spamear diálogos", §19.4). Small preferences only (F0.8 §12), never a trip's own state.
 *
 * Both default to `false` when absent, which means "show it / ask it". A store that cannot be read is treated as
 * empty rather than as an error: the worst outcome is showing the welcome once more, never a broken launch.
 */
class OnboardingPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    val welcomeSeen: Flow<Boolean> = preferences.map { it[KEY_WELCOME_SEEN] ?: false }

    /** True once the notification permission prompt has been offered at a Start; from then on only the person's own tap on a card asks again. */
    val notificationPromptShown: Flow<Boolean> = preferences.map { it[KEY_NOTIFICATION_PROMPT_SHOWN] ?: false }

    suspend fun markWelcomeSeen() {
        dataStore.edit { it[KEY_WELCOME_SEEN] = true }
    }

    suspend fun markNotificationPromptShown() {
        dataStore.edit { it[KEY_NOTIFICATION_PROMPT_SHOWN] = true }
    }

    companion object {
        private val KEY_WELCOME_SEEN = booleanPreferencesKey("onboarding_welcome_seen")
        private val KEY_NOTIFICATION_PROMPT_SHOWN = booleanPreferencesKey("onboarding_notification_prompt_shown")
    }
}
