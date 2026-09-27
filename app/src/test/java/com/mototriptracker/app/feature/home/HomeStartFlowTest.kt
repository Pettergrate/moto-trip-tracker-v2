package com.mototriptracker.app.feature.home

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.datastore.OnboardingPreferences
import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.testing.FailingDataStore
import com.mototriptracker.app.testing.FakeCapabilityInputsProvider
import com.mototriptracker.app.testing.TestDatabaseFactory
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PERM-001 / `privacy-permissions.md` §19.2: at Start, the notification prompt comes after location and is offered
 * once. Whatever goes wrong reading or writing that memory, Start goes on - it is never held up by it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeStartFlowTest {

    private val inputs = CapabilityInputs(
        preciseLocationGranted = true, approximateLocationGranted = true, activityRecognitionGranted = false,
        backgroundLocationGranted = false, notificationsEnabled = false, locationServicesEnabled = true, autoTrackingEnabledByUser = false
    )

    private lateinit var db: MotoTripDatabase
    private lateinit var provider: FakeCapabilityInputsProvider
    private lateinit var preferences: OnboardingPreferences
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = TestDatabaseFactory.createInMemory()
        provider = FakeCapabilityInputsProvider(inputs)
        preferences = TestOnboardingPreferences.create()
        viewModel = viewModelWith(preferences)
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.coroutineContext[Job]?.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModelWith(onboarding: OnboardingPreferences) = HomeViewModel(
        context = ApplicationProvider.getApplicationContext(), tripCaptureDao = db.tripCaptureDao(),
        rawTrackPointDao = db.rawTrackPointDao(), manualPauseIntervalDao = db.manualPauseIntervalDao(), tripDao = db.tripDao(),
        tripStatisticsDao = db.tripStatisticsDao(), capabilityInputsProvider = provider, onboardingPreferences = onboarding,
        clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L)
    )

    /** Runs the decision once and reports which way it went: "ask", "skip", or null if it never answered. */
    private fun HomeViewModel.decide(sdkInt: Int): String? {
        var answer: String? = null
        prepareNotificationAsk(sdkInt = sdkInt, onAsk = { answer = "ask" }, onSkip = { answer = "skip" })
        runBlocking { withTimeout(5_000) { while (answer == null) delay(10) } }
        return answer
    }

    @Test
    fun onTheFirstStartWithNotificationsOffOnAndroid13ItAsks() {
        assertEquals("ask", viewModel.decide(sdkInt = 36))
    }

    @Test
    fun itIsRecordedBeforeAskingSoItCanNeverRepeat() = runBlocking {
        viewModel.decide(sdkInt = 36)

        assertTrue(preferences.notificationPromptShown.first())
        assertEquals("the second Start goes straight on", "skip", viewModel.decide(sdkInt = 36))
    }

    @Test
    fun belowAndroid13ItNeverAsksAndRemembersNothing() = runBlocking {
        assertEquals("skip", viewModel.decide(sdkInt = 32))
        assertFalse(preferences.notificationPromptShown.first())
    }

    @Test
    fun withNotificationsAlreadyAllowedItDoesNotAsk() {
        provider.set(inputs.copy(notificationsEnabled = true))

        assertEquals("skip", viewModel.decide(sdkInt = 36))
    }

    /** A person who said no once is not asked again at every Start; the Home and Active Trip cards are how they change their mind. */
    @Test
    fun afterANoTheNextStartsDoNotAsk() = runBlocking {
        viewModel.decide(sdkInt = 36) // asked; the person said no (nothing else is recorded about the answer)
        provider.set(inputs) // still off

        assertEquals("skip", viewModel.decide(sdkInt = 36))
    }

    @Test
    fun whenTheMemoryCannotBeWrittenStartGoesOnWithoutAsking() {
        val broken = viewModelWith(OnboardingPreferences(FailingDataStore()))

        try {
            // Asking without being able to record it could repeat at every Start, so it does not ask.
            assertEquals("skip", broken.decide(sdkInt = 36))
        } finally {
            broken.viewModelScope.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun whenTheCapabilityReadFailsStartGoesOnWithoutAsking() {
        provider.throwOnRead = true

        assertEquals("skip", viewModel.decide(sdkInt = 36))
    }
}
