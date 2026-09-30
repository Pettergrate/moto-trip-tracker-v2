package com.mototriptracker.app.feature.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.datastore.AppearancePreferences
import com.mototriptracker.app.core.theme.AccentColor
import com.mototriptracker.app.core.theme.Appearance
import com.mototriptracker.app.core.theme.ThemeBase
import com.mototriptracker.app.testing.FailingDataStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** SET-002: a choice applies at once, is saved behind it, and never depends on being saved. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppearanceViewModelTest {

    private var viewModel: AppearanceViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.coroutineContext?.get(Job)?.cancel()
        Dispatchers.resetMain()
    }

    private fun newPreferences() = AppearancePreferences(
        PreferenceDataStoreFactory.create(
            produceFile = { File(ApplicationProvider.getApplicationContext<Context>().filesDir, "test-appearance-vm-${System.nanoTime()}.preferences_pb") }
        )
    )

    private fun create(preferences: AppearancePreferences) = AppearanceViewModel(preferences).also { viewModel = it }

    private suspend fun AppearanceViewModel.settled(): Appearance = withTimeout(5_000) { appearance.first { it != null } }!!

    @Test
    fun aFreshInstallStartsOnTheDefaultTheme() = runBlocking {
        assertEquals(Appearance.Default, create(newPreferences()).settled())
    }

    @Test
    fun aChoiceTakesEffectAtOnceWithoutWaitingForTheSave() = runBlocking {
        val vm = create(newPreferences())
        vm.settled()

        vm.onAccentSelected(AccentColor.BLUE)
        vm.onBaseSelected(ThemeBase.LIGHT)

        assertEquals(Appearance(ThemeBase.LIGHT, AccentColor.BLUE), vm.appearance.value)
    }

    @Test
    fun theChoiceIsSavedSoTheNextLaunchStartsOnIt() = runBlocking {
        val preferences = newPreferences()
        val vm = create(preferences)
        vm.settled()

        vm.onAccentSelected(AccentColor.GREEN)
        withTimeout(5_000) { while (preferences.appearance.first().accent != AccentColor.GREEN) delay(10) }

        vm.viewModelScope.coroutineContext[Job]?.cancel()
        assertEquals(Appearance(ThemeBase.DARK, AccentColor.GREEN), create(preferences).settled())
    }

    @Test
    fun changingTheBaseKeepsTheAccentAndViceVersa() = runBlocking {
        val vm = create(newPreferences())
        vm.settled()

        vm.onAccentSelected(AccentColor.VIOLET)
        vm.onBaseSelected(ThemeBase.LIGHT)

        assertEquals(AccentColor.VIOLET, vm.appearance.value?.accent)
    }

    @Test
    fun resetGoesBackToTheDefault() = runBlocking {
        val vm = create(newPreferences())
        vm.settled()
        vm.onAccentSelected(AccentColor.RED)
        vm.onBaseSelected(ThemeBase.LIGHT)

        vm.onResetToDefault()

        assertEquals(Appearance.Default, vm.appearance.value)
    }

    @Test
    fun whenItCannotBeSavedThePersonStillSeesWhatTheyPicked() = runBlocking {
        val vm = create(AppearancePreferences(FailingDataStore()))
        vm.settled()

        vm.onAccentSelected(AccentColor.PINK) // the write fails; that must neither crash nor undo the choice

        assertEquals(AccentColor.PINK, vm.appearance.value?.accent)
    }
}
