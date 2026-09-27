package com.mototriptracker.app.feature.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.datastore.AutoTrackingPreferences
import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.domain.capability.AutoTrackingRequirement
import com.mototriptracker.app.domain.capability.AutoTrackingState
import com.mototriptracker.app.testing.FailingDataStore
import com.mototriptracker.app.testing.FakeCapabilityInputsProvider
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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** SET-02: the switch is the person's intent, the state is what the resolver says given it and the permissions actually held. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AutoTrackingViewModelTest {

    private val nothingGrantedYet = CapabilityInputs(
        preciseLocationGranted = true, approximateLocationGranted = true, activityRecognitionGranted = false,
        backgroundLocationGranted = false, notificationsEnabled = true, locationServicesEnabled = true, autoTrackingEnabledByUser = false
    )

    private lateinit var provider: FakeCapabilityInputsProvider
    private var viewModel: AutoTrackingViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        provider = FakeCapabilityInputsProvider(nothingGrantedYet)
    }

    @After
    fun tearDown() {
        // The screen re-reads on a timer; left running past the test it collides with the next test's setMain.
        viewModel?.viewModelScope?.coroutineContext?.get(Job)?.cancel()
        Dispatchers.resetMain()
    }

    private fun preferences() = AutoTrackingPreferences(
        PreferenceDataStoreFactory.create(
            produceFile = { File(ApplicationProvider.getApplicationContext<Context>().filesDir, "test-autotracking-vm-${System.nanoTime()}.preferences_pb") }
        )
    )

    private fun create(preferences: AutoTrackingPreferences = preferences()) =
        AutoTrackingViewModel(preferences, provider).also { viewModel = it }

    private suspend fun AutoTrackingViewModel.state(matching: (AutoTrackingUiState) -> Boolean = { true }): AutoTrackingUiState =
        withTimeout(8_000) { uiState.first { it != null && matching(it) } }!!

    @Test
    fun aFreshInstallReadsAsOffWithAllTheRequirementsListed() = runBlocking {
        val state = create().state()

        assertFalse(state.enabled)
        assertEquals(AutoTrackingState.OFF, state.state)
        assertEquals(5, state.requirements.size)
        assertFalse("activity recognition is missing", state.requirements.first { it.requirement == AutoTrackingRequirement.ACTIVITY_RECOGNITION }.met)
    }

    @Test
    fun switchingItOnWithoutTheActivityPermissionMeansNeedsSetupNotReadyAndNotOff() = runBlocking {
        val vm = create()
        vm.state()

        vm.onToggle(true)

        val state = vm.state { it.enabled }
        assertEquals(AutoTrackingState.NEEDS_SETUP, state.state)
    }

    @Test
    fun theSwitchMovesAtOnceWithoutWaitingForTheNextPermissionRead() = runBlocking {
        val vm = create()
        vm.state()

        vm.onToggle(true)

        // Well inside the 3 s re-read interval: it comes from the preference itself.
        assertTrue(withTimeout(1_500) { vm.uiState.first { it?.enabled == true } }!!.enabled)
    }

    @Test
    fun withEverythingGrantedAndTheSwitchOnItIsReady() = runBlocking {
        provider.set(nothingGrantedYet.copy(activityRecognitionGranted = true, backgroundLocationGranted = true))
        val vm = create()
        vm.state()

        vm.onToggle(true)

        assertEquals(AutoTrackingState.READY, vm.state { it.enabled }.state)
    }

    @Test
    fun switchingItOffGoesBackToOffWhateverIsGranted() = runBlocking {
        provider.set(nothingGrantedYet.copy(activityRecognitionGranted = true, backgroundLocationGranted = true))
        val vm = create()
        vm.onToggle(true)
        vm.state { it.state == AutoTrackingState.READY }

        vm.onToggle(false)

        assertEquals(AutoTrackingState.OFF, vm.state { !it.enabled }.state)
    }

    /** The person grants a permission in the phone's Settings and comes back: the screen notices without being asked. */
    @Test
    fun aPermissionGrantedElsewhereIsNoticedOnTheNextRead() = runBlocking {
        val vm = create()
        vm.onToggle(true)
        assertEquals(AutoTrackingState.NEEDS_SETUP, vm.state { it.enabled }.state)

        provider.set(nothingGrantedYet.copy(activityRecognitionGranted = true)) // background still missing

        assertEquals(AutoTrackingState.LIMITED, vm.state { it.state == AutoTrackingState.LIMITED }.state)
    }

    @Test
    fun whenThePreferenceCannotBeReadItReadsAsOffInsteadOfFailing() = runBlocking {
        val state = create(AutoTrackingPreferences(FailingDataStore())).state()

        assertEquals(AutoTrackingState.OFF, state.state)
    }

    @Test
    fun whenItCannotBeSavedTheSwitchStaysOffBecauseThatIsWhatIsTrue() = runBlocking {
        val vm = create(AutoTrackingPreferences(FailingDataStore()))
        vm.state()

        vm.onToggle(true) // the write fails; that must not crash

        delay(300)
        assertFalse(vm.state().enabled)
    }

    @Test
    fun aFailedPlatformReadShowsNothingRatherThanAnInventedState() = runBlocking {
        provider.throwOnRead = true
        val vm = create()

        val state = withTimeoutOrNull(600) { vm.uiState.first { it != null } }

        assertNull(state)
    }
}
