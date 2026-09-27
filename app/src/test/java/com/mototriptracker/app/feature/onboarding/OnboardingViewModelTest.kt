package com.mototriptracker.app.feature.onboarding

import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.datastore.OnboardingPreferences
import com.mototriptracker.app.testing.FailingDataStore
import com.mototriptracker.app.testing.TestOnboardingPreferences
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

/** PERM-001 / ONB-01: the welcome shows once, and remembering that is never a condition for getting into the app. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnboardingViewModelTest {

    private var viewModel: OnboardingViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        // The gate collects a DataStore flow; leaving it running past the test collides with the next test's setMain.
        viewModel?.viewModelScope?.coroutineContext?.get(Job)?.cancel()
        Dispatchers.resetMain()
    }

    private fun create(preferences: OnboardingPreferences) = OnboardingViewModel(preferences).also { viewModel = it }

    private suspend fun OnboardingViewModel.settledGate(): OnboardingGate = withTimeout(5_000) { gate.first { it != OnboardingGate.LOADING } }

    @Test
    fun aFreshInstallShowsTheWelcome() = runBlocking {
        assertEquals(OnboardingGate.WELCOME, create(TestOnboardingPreferences.create()).settledGate())
    }

    @Test
    fun onceContinuedTheAppIsShownAtOnceWithoutWaitingForTheWrite() = runBlocking {
        val vm = create(TestOnboardingPreferences.create())
        vm.settledGate()

        vm.onWelcomeContinue()

        assertEquals(OnboardingGate.DONE, withTimeout(5_000) { vm.gate.first { it == OnboardingGate.DONE } })
    }

    @Test
    fun continuingIsRememberedSoTheNextLaunchSkipsTheWelcome() = runBlocking {
        val preferences = TestOnboardingPreferences.create()
        val vm = create(preferences)
        vm.settledGate()

        vm.onWelcomeContinue()
        withTimeout(5_000) { while (!preferences.welcomeSeen.first()) delay(10) }

        // What a new launch reads: the welcome is already seen, so the gate goes straight to the app.
        vm.viewModelScope.coroutineContext[Job]?.cancel()
        assertEquals(OnboardingGate.DONE, create(preferences).settledGate())
    }

    @Test
    fun whenItCannotBeRememberedThePersonStillGetsIn() = runBlocking {
        val vm = create(OnboardingPreferences(FailingDataStore()))
        assertEquals("an unreadable store reads as a first launch", OnboardingGate.WELCOME, vm.settledGate())

        vm.onWelcomeContinue() // the write fails; that must neither crash nor trap them on the welcome

        assertEquals(OnboardingGate.DONE, withTimeout(5_000) { vm.gate.first { it == OnboardingGate.DONE } })
    }
}
