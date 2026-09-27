package com.mototriptracker.app.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.BuildConfig
import com.mototriptracker.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** SET-001: what the Settings panel says. */
@RunWith(RobolectricTestRunner::class)
class SettingsCopyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theVersionRowStatesTheRealVersionAndBuildNotAPlaceholder() {
        val text = context.getString(R.string.settings_version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        assertTrue(text, text.contains(BuildConfig.VERSION_NAME))
        assertTrue(text, text.contains("build ${BuildConfig.VERSION_CODE}"))
    }

    @Test
    fun everyLabelIsFilledIn() {
        listOf(
            R.string.settings_title, R.string.settings_back, R.string.settings_section_data, R.string.settings_trash_title,
            R.string.settings_trash_subtitle, R.string.settings_section_about, R.string.settings_version_title,
            R.string.settings_diagnostics_title, R.string.settings_diagnostics_subtitle, R.string.settings_section_advanced,
            R.string.settings_field_test_title, R.string.settings_field_test_subtitle
        ).forEach { id -> assertFalse(context.resources.getResourceEntryName(id), context.getString(id).isBlank()) }
    }

    /** The Trash's own dialog promises 30 days; the Settings row must not promise a different period. */
    @Test
    fun theTrashRowMatchesTheRetentionThePersonIsToldWhenDeleting() {
        assertTrue(context.getString(R.string.settings_trash_subtitle).contains("30 days"))
    }

    @Test
    fun theInternalToolsAreLabelledAsInternalSoTheyAreNotMistakenForProductFeatures() {
        assertTrue(context.getString(R.string.settings_field_test_subtitle).contains("Internal"))
        assertEquals("Advanced", context.getString(R.string.settings_section_advanced))
    }
}
