package com.mototriptracker.app.core.datastore

import com.mototriptracker.app.testing.FailingDataStore
import com.mototriptracker.app.testing.TestOnboardingPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** PERM-001: reaches real file-backed DataStore I/O, like `AutoTrackingPreferencesTest` does. */
@RunWith(RobolectricTestRunner::class)
class OnboardingPreferencesTest {

    @Test
    fun nothingIsRememberedOnAFreshInstall() = runTest {
        val preferences = TestOnboardingPreferences.create()

        assertFalse(preferences.welcomeSeen.first())
        assertFalse(preferences.notificationPromptShown.first())
    }

    @Test
    fun theWelcomeBeingSeenIsRemembered() = runTest {
        val preferences = TestOnboardingPreferences.create()

        preferences.markWelcomeSeen()

        assertTrue(preferences.welcomeSeen.first())
    }

    @Test
    fun theNotificationPromptBeingShownIsRemembered() = runTest {
        val preferences = TestOnboardingPreferences.create()

        preferences.markNotificationPromptShown()

        assertTrue(preferences.notificationPromptShown.first())
    }

    @Test
    fun theTwoMarksAreIndependent() = runTest {
        val preferences = TestOnboardingPreferences.create()

        preferences.markWelcomeSeen()

        assertFalse("seeing the welcome says nothing about notifications", preferences.notificationPromptShown.first())
    }

    /** A store that cannot be read must not break the launch: it reads as "nothing remembered". */
    @Test
    fun anUnreadableStoreReadsAsNothingRememberedInsteadOfFailing() = runTest {
        val preferences = OnboardingPreferences(FailingDataStore())

        assertFalse(preferences.welcomeSeen.first())
        assertFalse(preferences.notificationPromptShown.first())
    }
}
