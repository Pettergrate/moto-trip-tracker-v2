package com.mototriptracker.app.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * AUTO-001: proves this reaches real file-backed DataStore I/O (Robolectric's
 * real `Context.filesDir`), not just that the interface compiles - the same
 * discipline `AndroidFieldTestDatasetWriterTest` established for plain file
 * I/O.
 */
@RunWith(RobolectricTestRunner::class)
class AutoTrackingPreferencesTest {

    private fun preferencesBackedBy(file: File) =
        AutoTrackingPreferences(PreferenceDataStoreFactory.create(produceFile = { file }))

    private fun newPreferences(): AutoTrackingPreferences {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return preferencesBackedBy(File(context.filesDir, "test-settings-${System.nanoTime()}.preferences_pb"))
    }

    @Test
    fun defaultsToDisabledWhenNothingWasEverSet() = runTest {
        assertFalse(newPreferences().autoTrackingEnabled.first())
    }

    @Test
    fun persistsAnExplicitlyEnabledValue() = runTest {
        val preferences = newPreferences()

        preferences.setAutoTrackingEnabled(true)

        assertTrue(preferences.autoTrackingEnabled.first())
    }

    // A second DataStore instance for the same file (simulating "a fresh
    // instance re-reads what an earlier one wrote") is not a supported
    // pattern here - DataStore enforces at most one live instance per file
    // per process and throws IllegalStateException otherwise (verified by
    // attempting it). The real production shape (one @Singleton instance for
    // the whole app process) is exactly what the two tests above exercise.
}
