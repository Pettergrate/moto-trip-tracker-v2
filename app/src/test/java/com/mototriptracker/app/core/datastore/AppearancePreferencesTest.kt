package com.mototriptracker.app.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.theme.AccentColor
import com.mototriptracker.app.core.theme.Appearance
import com.mototriptracker.app.core.theme.ThemeBase
import com.mototriptracker.app.testing.FailingDataStore
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** SET-002: the chosen theme survives, and nothing the store holds can break the launch. */
@RunWith(RobolectricTestRunner::class)
class AppearancePreferencesTest {

    private fun newStore() = PreferenceDataStoreFactory.create(
        produceFile = {
            File(ApplicationProvider.getApplicationContext<Context>().filesDir, "test-appearance-${System.nanoTime()}.preferences_pb")
        }
    )

    @Test
    fun aFreshInstallHasTheDefaultTheme() = runTest {
        assertEquals(Appearance.Default, AppearancePreferences(newStore()).appearance.first())
    }

    @Test
    fun aChosenThemeIsRemembered() = runTest {
        val preferences = AppearancePreferences(newStore())

        preferences.set(Appearance(ThemeBase.LIGHT, AccentColor.TEAL))

        assertEquals(Appearance(ThemeBase.LIGHT, AccentColor.TEAL), preferences.appearance.first())
    }

    @Test
    fun aValueThisBuildDoesNotKnowFallsBackToTheDefaultForThatPartOnly() = runTest {
        val store = newStore()
        store.edit {
            it[stringPreferencesKey("appearance_base")] = "SEPIA" // e.g. a base a later build dropped
            it[stringPreferencesKey("appearance_accent")] = AccentColor.PINK.name
        }

        assertEquals(Appearance(ThemeBase.DARK, AccentColor.PINK), AppearancePreferences(store).appearance.first())
    }

    @Test
    fun anUnreadableStoreGivesTheDefaultInsteadOfFailing() = runTest {
        assertEquals(Appearance.Default, AppearancePreferences(FailingDataStore()).appearance.first())
    }
}
