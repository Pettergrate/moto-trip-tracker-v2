package com.mototriptracker.app.feature.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** PERM-001: what the welcome and the pre-permission explanation say - checked for honesty, not for wording. */
@RunWith(RobolectricTestRunner::class)
class OnboardingCopyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun text(id: Int) = context.getString(id)

    @Test
    fun theWelcomeCoversWhatIsRecordedThatItWorksByHandAndThatTheDataIsLocal() {
        // ux-navigation.md 16 / ONB-01: exactly these three things.
        assertTrue(text(R.string.welcome_records_text).contains("route"))
        assertTrue(text(R.string.welcome_control_text).contains("start and finish each trip yourself"))
        assertTrue(text(R.string.welcome_local_text).contains("stored on this phone"))
    }

    /** The app has the INTERNET permission for its map: "nothing leaves your phone" would be untrue, so it is not said. */
    @Test
    fun theWelcomeDoesNotClaimMoreThanTheAppDoesAboutTheNetwork() {
        val local = text(R.string.welcome_local_text)

        assertTrue("says recording works without internet", local.contains("without internet"))
        assertTrue("is upfront that the map is downloaded", local.contains("map"))
        assertFalse(local, local.contains("nothing", ignoreCase = true))
        assertFalse(local, local.contains("never", ignoreCase = true))
    }

    @Test
    fun automaticDetectionIsPresentedAsOptionalAndOffNotAsSomethingToTurnOnNow() {
        val control = text(R.string.welcome_control_text)

        assertTrue(control.contains("optional"))
        assertTrue(control.contains("off by default"))
    }

    @Test
    fun theWelcomeAsksForNothing() {
        // §19.1: "No pedir permisos automáticamente". Its only action is Continue, and it names no permission.
        val everything = listOf(
            R.string.welcome_title, R.string.welcome_records_text, R.string.welcome_control_text,
            R.string.welcome_local_text, R.string.welcome_continue
        ).joinToString(" ") { text(it) }

        assertFalse(everything, everything.contains("allow", ignoreCase = true))
        assertFalse(everything, everything.contains("permission", ignoreCase = true))
        assertEquals("Continue", text(R.string.welcome_continue))
    }

    @Test
    fun theLocationExplanationSaysWhyPreciseAndWhatApproximateCostsAndOffersAWayOut() {
        // privacy-permissions.md 6.3.
        val explanation = text(R.string.location_explanation_text)

        assertTrue(explanation.contains("precise location"))
        assertTrue(explanation.contains("route"))
        assertTrue("says what approximate location costs", explanation.contains("approximate location"))
        assertEquals("Not now", text(R.string.location_explanation_not_now))
        assertEquals("Continue", text(R.string.location_explanation_continue))
    }
}
