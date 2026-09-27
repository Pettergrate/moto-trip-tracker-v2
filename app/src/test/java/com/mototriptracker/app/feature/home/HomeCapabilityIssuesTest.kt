package com.mototriptracker.app.feature.home

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.common.FakeClock
import com.mototriptracker.app.core.database.MotoTripDatabase
import com.mototriptracker.app.core.datastore.OnboardingPreferences
import com.mototriptracker.app.core.model.CapabilityInputs
import com.mototriptracker.app.core.model.CapabilityMode
import com.mototriptracker.app.domain.capability.CapabilityIssue
import com.mototriptracker.app.testing.FakeCapabilityInputsProvider
import com.mototriptracker.app.testing.TestDatabaseFactory
import com.mototriptracker.app.testing.TestOnboardingPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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

/** PERM-003: Home tells the person *why*, and re-reads it when asked (the screen asks every time it comes back to the foreground). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeCapabilityIssuesTest {

    private val allGood = CapabilityInputs(
        preciseLocationGranted = true, approximateLocationGranted = true, activityRecognitionGranted = false,
        backgroundLocationGranted = false, notificationsEnabled = true, locationServicesEnabled = true, autoTrackingEnabledByUser = false
    )

    private lateinit var db: MotoTripDatabase
    private lateinit var provider: FakeCapabilityInputsProvider
    private lateinit var onboardingPreferences: OnboardingPreferences
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = TestDatabaseFactory.createInMemory()
        provider = FakeCapabilityInputsProvider(allGood.copy(notificationsEnabled = false))
        onboardingPreferences = TestOnboardingPreferences.create()
        viewModel = HomeViewModel(
            context = ApplicationProvider.getApplicationContext(), tripCaptureDao = db.tripCaptureDao(),
            rawTrackPointDao = db.rawTrackPointDao(), manualPauseIntervalDao = db.manualPauseIntervalDao(), tripDao = db.tripDao(),
            tripStatisticsDao = db.tripStatisticsDao(), capabilityInputsProvider = provider,
            onboardingPreferences = onboardingPreferences,
            clock = FakeClock(wallMillis = 1_000L, elapsedNanos = 1_000L)
        )
    }

    @After
    fun tearDown() {
        // Home re-reads on a 1 s timer; left running it can wake on Main after the test and collide with the next setMain.
        viewModel.viewModelScope.coroutineContext[Job]?.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun theDeniedNotificationsAreReportedAsTheReasonNotJustAMode() = runBlocking {
        val state = withTimeout(5_000) { viewModel.uiState.first { it.capabilityIssues.isNotEmpty() } }

        assertEquals(listOf(CapabilityIssue.NOTIFICATIONS_DENIED), state.capabilityIssues)
        assertEquals("the mode is unchanged: manual trips still work", CapabilityMode.MANUAL, state.capabilityMode)
    }

    @Test
    fun fixingItAndComingBackClearsTheCardOnRefresh() = runBlocking {
        withTimeout(5_000) { viewModel.uiState.first { it.capabilityIssues.isNotEmpty() } }

        provider.set(allGood) // the person allowed notifications in Settings
        viewModel.refreshCapabilityMode()

        val cleared = withTimeout(5_000) { viewModel.uiState.first { it.capabilityMode != null && it.capabilityIssues.isEmpty() } }
        assertEquals(emptyList<CapabilityIssue>(), cleared.capabilityIssues)
    }

    @Test
    fun aRevokedPreciseLocationSurfacesAsThePrimaryProblemWithNotificationsSecond() = runBlocking {
        provider.set(allGood.copy(preciseLocationGranted = false, notificationsEnabled = false))
        viewModel.refreshCapabilityMode()

        val state = withTimeout(5_000) { viewModel.uiState.first { it.capabilityIssues.size == 2 } }

        assertEquals(listOf(CapabilityIssue.PRECISE_LOCATION_MISSING, CapabilityIssue.NOTIFICATIONS_DENIED), state.capabilityIssues)
    }

    @Test
    fun theModeAndTheIssuesComeFromTheSameReadSoTheyCannotDisagree() = runBlocking {
        provider.set(allGood.copy(locationServicesEnabled = false))
        viewModel.refreshCapabilityMode()

        val state = withTimeout(5_000) { viewModel.uiState.first { it.capabilityIssues.isNotEmpty() && it.capabilityMode == CapabilityMode.LOCATION_DEGRADED } }

        assertEquals(listOf(CapabilityIssue.LOCATION_SERVICES_OFF), state.capabilityIssues)
    }

    /** Found on the phone: with only approximate location allowed, Home still said "Location services are off". */
    @Test
    fun theDegradedModeLineNeverBlamesLocationServicesBecauseItHasTwoCauses() {
        val line = CapabilityMode.LOCATION_DEGRADED.toReadinessText()

        assertFalse(line, line.contains("services", ignoreCase = true))
        assertFalse(line, line.contains("turn them on", ignoreCase = true))
        assertTrue("it points at the notice that names the cause", line.contains("problem above"))
    }

    /**
     * Found on the phone: Location was switched off from the quick-settings panel (which does not pause the activity),
     * the card was stale, and START TRIP began a route-less trip with no warning. The check must be a fresh read.
     */
    @Test
    fun startingIsJudgedFromAFreshReadNotFromWhatTheScreenLastShowed() = runBlocking {
        provider.set(allGood)
        viewModel.refreshCapabilityMode()
        withTimeout(5_000) { viewModel.uiState.first { it.capabilityMode != null && it.capabilityIssues.isEmpty() } }

        provider.set(allGood.copy(locationServicesEnabled = false)) // changed behind the app's back; no refresh
        var warned: CapabilityIssue? = null
        var proceeded = false
        viewModel.checkBeforeStart(onWarn = { warned = it }, onProceed = { proceeded = true })

        withTimeout(5_000) { while (warned == null && !proceeded) kotlinx.coroutines.delay(10) }
        assertEquals(CapabilityIssue.LOCATION_SERVICES_OFF, warned)
        assertFalse("must not start silently", proceeded)
        assertEquals("and the card catches up at the same moment", listOf(CapabilityIssue.LOCATION_SERVICES_OFF), viewModel.uiState.value.capabilityIssues)
    }

    @Test
    fun whenNothingWouldLeaveTheTripWithoutARouteStartingProceeds() = runBlocking {
        provider.set(allGood)
        var warned: CapabilityIssue? = null
        var proceeded = false

        viewModel.checkBeforeStart(onWarn = { warned = it }, onProceed = { proceeded = true })

        withTimeout(5_000) { while (warned == null && !proceeded) kotlinx.coroutines.delay(10) }
        assertTrue(proceeded)
        assertEquals(null, warned)
    }

    /** Denied notifications never get in the way of Start (the trip records; the Active Trip screen explains what is missing). */
    @Test
    fun deniedNotificationsDoNotHoldBackStart() = runBlocking {
        provider.set(allGood.copy(notificationsEnabled = false))
        var proceeded = false

        viewModel.checkBeforeStart(onWarn = { }, onProceed = { proceeded = true })

        withTimeout(5_000) { while (!proceeded) kotlinx.coroutines.delay(10) }
        assertTrue(proceeded)
    }

    /** With no permission at all Start asks for it itself, so there is nothing to warn about first. */
    @Test
    fun withNoLocationPermissionAtAllStartGoesAheadToAskForIt() = runBlocking {
        provider.set(allGood.copy(preciseLocationGranted = false, approximateLocationGranted = false))
        var warned: CapabilityIssue? = null
        var proceeded = false

        viewModel.checkBeforeStart(onWarn = { warned = it }, onProceed = { proceeded = true })

        withTimeout(5_000) { while (warned == null && !proceeded) kotlinx.coroutines.delay(10) }
        assertTrue(proceeded)
        assertEquals(null, warned)
    }

    @Test
    fun approximateOnlyWarnsBeforeStartingBecauseNoRouteWouldBeRecorded() = runBlocking {
        provider.set(allGood.copy(preciseLocationGranted = false))
        var warned: CapabilityIssue? = null

        viewModel.checkBeforeStart(onWarn = { warned = it }, onProceed = { })

        withTimeout(5_000) { while (warned == null) kotlinx.coroutines.delay(10) }
        assertEquals(CapabilityIssue.PRECISE_LOCATION_MISSING, warned)
    }
}
