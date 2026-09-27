package com.mototriptracker.app.feature.settings

import android.content.Context
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.R
import com.mototriptracker.app.feature.common.boldMarked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PERM-002: what the app says before it asks for Activity Recognition and, above all, before background location.
 * `privacy-permissions.md` §7.3 makes the disclosure a release gate (Google Play), so its required content is pinned here.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionDisclosureCopyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun text(id: Int) = context.getString(id)

    @Test
    fun theBackgroundDisclosureSaysLocationAndEvenWhenTheAppIsClosedOrNotInUse() {
        val text = text(R.string.setup_background_text)

        assertTrue(text, text.contains("location"))
        assertTrue(text, text.contains("even when the app is closed or not in use"))
    }

    /** §7.3: the two phrases must stand out, not be buried - so they are the ones marked bold. */
    @Test
    fun thoseTwoPhrasesAreTheOnesDrawnInBold() {
        val bold = SpanStyle(fontWeight = FontWeight.Bold)
        val annotated = boldMarked(text(R.string.setup_background_text), bold)

        val boldParts = annotated.spanStyles.map { annotated.text.substring(it.start, it.end) }
        assertEquals(listOf("location", "even when the app is closed or not in use"), boldParts)
        assertFalse("the marks are not shown", annotated.text.contains("**"))
    }

    @Test
    fun theBackgroundDisclosureSaysWhatTheDataIsAndIsNotUsedFor() {
        val text = text(R.string.setup_background_text)

        assertTrue(text.contains("stored locally on your device"))
        assertTrue(text.contains("not used for advertising"))
    }

    @Test
    fun decliningBackgroundLocationIsPresentedAsHarmlessBecauseManualRecordingStays() {
        assertTrue(text(R.string.setup_background_text).contains("keep recording trips manually"))
    }

    /** §7.3: it must not pass for the system's own dialog, so it is titled for the feature, not for the permission's system name. */
    @Test
    fun theDisclosureIsTitledForTheFeatureNotLikeTheSystemDialog() {
        val title = text(R.string.setup_background_title)

        assertTrue(title.contains("Auto Tracking"))
        assertFalse(title.contains("Allow", ignoreCase = true))
    }

    @Test
    fun theSettingsHintTellsThePersonExactlyWhatToChooseOnAndroid11AndUp() {
        assertTrue(text(R.string.setup_background_settings_hint).contains("Allow all the time"))
    }

    @Test
    fun everyExplanationOffersAWayOut() {
        assertEquals("Not now", text(R.string.setup_not_now))
        assertEquals("Continue", text(R.string.setup_continue))
    }

    /** §8.1: Activity Recognition is for detecting a possible trip, not for a general history of walking or standing. */
    @Test
    fun theActivityExplanationStatesItsOnePurposeAndThatManualStartStillWorks() {
        val text = text(R.string.setup_activity_text)

        assertTrue(text, text.contains("detect a possible trip"))
        assertTrue(text, text.contains("start trips manually"))
        assertFalse(text, text.contains("history", ignoreCase = true))
    }
}
