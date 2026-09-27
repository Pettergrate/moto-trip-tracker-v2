package com.mototriptracker.app.testing

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.datastore.OnboardingPreferences
import java.io.File

/** Real, file-backed [OnboardingPreferences] (Robolectric's `filesDir`), one fresh file per call - never shared between tests. */
object TestOnboardingPreferences {
    fun create(): OnboardingPreferences {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "test-onboarding-${System.nanoTime()}.preferences_pb")
        return OnboardingPreferences(PreferenceDataStoreFactory.create(produceFile = { file }))
    }
}
