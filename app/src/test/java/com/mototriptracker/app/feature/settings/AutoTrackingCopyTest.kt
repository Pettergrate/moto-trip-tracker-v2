package com.mototriptracker.app.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.R
import com.mototriptracker.app.domain.capability.AutoTrackingRequirement
import com.mototriptracker.app.domain.capability.AutoTrackingState
import com.mototriptracker.app.domain.capability.CapabilityIssue
import com.mototriptracker.app.feature.home.toReadinessText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** SET-02: what the Auto Tracking screen and Home's card say - checked for honesty, not for wording. */
@RunWith(RobolectricTestRunner::class)
class AutoTrackingCopyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun text(id: Int) = context.getString(id)

    @Test
    fun everyStateHasItsOwnTitleAndExplanationAndShortLabel() {
        val titles = AutoTrackingState.entries.map { text(it.titleRes()) }
        val shorts = AutoTrackingState.entries.map { text(it.shortLabelRes()) }

        assertEquals("each state reads differently", titles.size, titles.toSet().size)
        assertEquals(shorts.size, shorts.toSet().size)
        AutoTrackingState.entries.forEach { assertTrue("$it", text(it.textRes()).isNotBlank()) }
    }

    @Test
    fun everyRequirementSaysWhatItIsForAndTheHandsFreeOnesAreTheOnesTheDocumentNames() {
        AutoTrackingRequirement.entries.forEach { requirement ->
            assertTrue("$requirement title", text(requirement.titleRes()).isNotBlank())
            assertTrue("$requirement purpose", text(requirement.textRes()).isNotBlank())
        }
        assertTrue(text(R.string.autotracking_req_background_text).contains("closed"))
        assertTrue(text(R.string.autotracking_req_notifications_text).contains("Pause and Finish"))
    }

    /** Off is a choice, not a problem: it must say trips are still recorded by hand and not ask for anything. */
    @Test
    fun theOffStateDoesNotNagAndSaysTripsAreStillRecordedByHand() {
        val off = text(R.string.autotracking_state_off_text)

        assertTrue(off, off.contains("only when you start them"))
        assertFalse(off, off.contains("permission", ignoreCase = true))
    }

    @Test
    fun theLimitedStateNeverImpliesTripsAreDetectedHandsFree() {
        val limited = text(R.string.autotracking_state_limited_text)

        assertTrue(limited, limited.contains("manually"))
        assertFalse(limited, limited.contains("automatically"))
    }

    @Test
    fun theNeedsSetupStateStillSaysManualRecordingWorks() {
        assertTrue(text(R.string.autotracking_state_needs_setup_text).contains("manually"))
    }

    /** Home's one-liner and this screen must not tell two stories: a person who switched it on is not told it is off. */
    @Test
    fun homesLineDistinguishesOffFromNeedingSetup() {
        assertTrue(AutoTrackingState.OFF.toReadinessText().contains("off"))
        assertFalse(AutoTrackingState.NEEDS_SETUP.toReadinessText(), AutoTrackingState.NEEDS_SETUP.toReadinessText().contains("is off"))
        assertEquals(AutoTrackingState.entries.size, AutoTrackingState.entries.map { it.toReadinessText() }.toSet().size)
    }

    @Test
    fun theLocationProblemLineBlamesNeitherCauseBecauseThereAreTwo() {
        val line = AutoTrackingState.LOCATION_PROBLEM.toReadinessText()

        assertFalse(line, line.contains("services", ignoreCase = true))
        assertTrue(line.contains("problem above"))
    }

    /** Activity recognition and background location are asked for by the guided setup (with their own explanation first), not by a plain fix. */
    @Test
    fun theTwoSensitiveOnesGoThroughTheGuidedSetupAndTheRestThroughAPlainFix() {
        assertTrue(AutoTrackingRequirement.ACTIVITY_RECOGNITION.usesGuidedSetup())
        assertTrue(AutoTrackingRequirement.BACKGROUND_LOCATION.usesGuidedSetup())
        assertFalse(AutoTrackingRequirement.PRECISE_LOCATION.usesGuidedSetup())
        assertFalse(AutoTrackingRequirement.NOTIFICATIONS.usesGuidedSetup())
        assertFalse(AutoTrackingRequirement.LOCATION_SERVICES.usesGuidedSetup())
        assertNotNull(AutoTrackingRequirement.PRECISE_LOCATION.fix())
        assertEquals(CapabilityIssue.LOCATION_SERVICES_OFF, AutoTrackingRequirement.LOCATION_SERVICES.fix())
        assertEquals(CapabilityIssue.NOTIFICATIONS_DENIED, AutoTrackingRequirement.NOTIFICATIONS.fix())
        assertNull(AutoTrackingRequirement.ACTIVITY_RECOGNITION.fix())
        assertNull(AutoTrackingRequirement.BACKGROUND_LOCATION.fix())
    }

    @Test
    fun theFootnoteSaysItCanBeSwitchedOffAndTheTripsStayOnThePhone() {
        val footnote = text(R.string.autotracking_footnote)

        assertTrue(footnote.contains("off at any time"))
        assertTrue(footnote.contains("on this phone"))
    }
}

