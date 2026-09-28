package com.mototriptracker.app.feature.settings

import androidx.lifecycle.viewModelScope
import com.mototriptracker.app.core.datastore.AutoTrackingPreferences
import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.domain.capability.AutoTrackingRequirement
import com.mototriptracker.app.domain.capability.AutoTrackingState
import com.mototriptracker.app.domain.capability.SetupStep
import com.mototriptracker.app.tracking.activityrecognition.AutoTrackingDetection
import com.mototriptracker.app.tracking.capability.CapabilityInputsProvider
import com.mototriptracker.app.testing.FailingDataStore
import com.mototriptracker.app.testing.FakeActivityTransitionRegistration
import com.mototriptracker.app.testing.InMemoryDataStore
import com.mototriptracker.app.testing.FakeCapabilityInputsProvider
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

    // In memory: this class tests what the view model does with the switch, not the disk (see `InMemoryDataStore`).
    private fun preferences() = AutoTrackingPreferences(InMemoryDataStore())

    private val registration = FakeActivityTransitionRegistration()

    /** In the app the inputs provider reads the switch from the same preference the screen writes; the fake has to follow it too. */
    private fun create(preferences: AutoTrackingPreferences = preferences()): AutoTrackingViewModel {
        val followsThePreference = object : CapabilityInputsProvider {
            override suspend fun current() = provider.current().copy(autoTrackingEnabledByUser = preferences.autoTrackingEnabled.first())
        }
        return AutoTrackingViewModel(preferences, provider, AutoTrackingDetection(registration, followsThePreference))
            .also { it.recheckMs = 100L } // the screen re-reads every 3 s; the tests must not wait that long
            .also { viewModel = it }
    }

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

    /** PERM-002: the guided setup reads the phone at the moment it is asked, not the last poll. */
    private fun AutoTrackingViewModel.nextStep(attempted: Set<SetupStep> = emptySet(), sdk: Int = 36): SetupStep? = runBlocking {
        var answered = false
        var answer: SetupStep? = null
        nextSetupStep(attempted, sdk) { answer = it; answered = true }
        withTimeout(5_000) { while (!answered) delay(10) }
        answer
    }

    @Test
    fun theGuidedSetupStartsWithTheActivityPermissionWhenItIsMissing() {
        assertEquals(SetupStep.ACTIVITY_RECOGNITION, create().nextStep())
    }

    @Test
    fun theGuidedSetupSeesAPermissionGrantedAMomentAgoWithoutWaitingForThePoll() {
        val vm = create()
        assertEquals(SetupStep.ACTIVITY_RECOGNITION, vm.nextStep())

        provider.set(nothingGrantedYet.copy(activityRecognitionGranted = true)) // answered in the system dialog just now

        assertEquals(SetupStep.BACKGROUND_LOCATION, vm.nextStep())
    }

    @Test
    fun theGuidedSetupDoesNotAskTwiceAndEndsAfterANo() {
        assertNull(create().nextStep(attempted = setOf(SetupStep.ACTIVITY_RECOGNITION)))
    }

    @Test
    fun theGuidedSetupHasNothingToAskWhenEverythingIsGranted() {
        provider.set(nothingGrantedYet.copy(activityRecognitionGranted = true, backgroundLocationGranted = true))

        assertNull(create().nextStep())
    }

    @Test
    fun aFailedReadEndsTheGuidedSetupInsteadOfBreakingTheScreen() {
        provider.throwOnRead = true

        assertNull(create().nextStep())
    }

    /** PERM-002: "off" has to mean not listening, and "on" with the permission has to start listening, at once. */
    private fun waitFor(condition: () -> Boolean) = runBlocking { withTimeout(5_000) { while (!condition()) delay(10) } }

    @Test
    fun switchingItOnWithThePermissionStartsListeningAtOnce() {
        provider.set(nothingGrantedYet.copy(activityRecognitionGranted = true))
        val vm = create()

        vm.onToggle(true)

        waitFor { registration.isRegistered == true }
    }

    /** The guided setup starts after the switch is saved and listening applied - it never races the write it depends on. */
    @Test
    fun theFollowUpRunsOnlyOnceTheChoiceIsSavedAndListeningIsApplied() {
        provider.set(nothingGrantedYet.copy(activityRecognitionGranted = true))
        val vm = create()
        var registeredWhenCalled: Boolean? = null
        var called = false

        vm.onToggle(true) {
            registeredWhenCalled = registration.isRegistered
            called = true
        }
        waitFor { called }

        assertEquals(true, registeredWhenCalled)
    }

    @Test
    fun switchingItOffStopsListeningAtOnce() {
        provider.set(nothingGrantedYet.copy(activityRecognitionGranted = true))
        val vm = create()
        vm.onToggle(true)
        waitFor { registration.isRegistered == true }

        vm.onToggle(false)

        waitFor { registration.isRegistered == false }
    }

    @Test
    fun switchingItOnWithoutThePermissionDoesNotRegisterAnything() {
        val vm = create()

        vm.onToggle(true)
        waitFor { registration.calls.isNotEmpty() }

        assertFalse(registration.calls.contains(FakeActivityTransitionRegistration.REGISTER))
    }

    @Test
    fun aPermissionAnsweredInTheGuidedSetupStartsTheListening() {
        val vm = create()
        vm.onToggle(true) // on, but the activity permission is still missing: not listening
        waitFor { registration.calls.isNotEmpty() }

        provider.set(nothingGrantedYet.copy(activityRecognitionGranted = true)) // answered in the system dialog
        vm.nextStep()

        waitFor { registration.isRegistered == true }
    }

    @Test
    fun comingBackToTheScreenAppliesTheStateAgain() {
        val vm = create()
        vm.onToggle(true)
        provider.set(nothingGrantedYet.copy(activityRecognitionGranted = true)) // granted in the phone's Settings

        vm.syncDetection()

        waitFor { registration.isRegistered == true }
    }

    @Test
    fun aFailedPlatformReadShowsNothingRatherThanAnInventedState() = runBlocking {
        provider.throwOnRead = true
        val vm = create()

        val state = withTimeoutOrNull(600) { vm.uiState.first { it != null } }

        assertNull(state)
    }
}
