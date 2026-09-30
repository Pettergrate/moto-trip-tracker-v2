package com.mototriptracker.app.testing

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.datastore.MapMarkerPreferences
import java.io.File

/** Real, file-backed [MapMarkerPreferences] (Robolectric's `filesDir`), one fresh file per call - never shared between tests. */
object TestMapMarkerPreferences {
    fun create(): MapMarkerPreferences {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "test-map-marker-${System.nanoTime()}.preferences_pb")
        return MapMarkerPreferences(PreferenceDataStoreFactory.create(produceFile = { file }))
    }
}
