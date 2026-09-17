package com.mototriptracker.app.tracking.capability

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.location.LocationManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.mototriptracker.app.core.datastore.AutoTrackingPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNotificationManager
import java.io.File

/**
 * AUTO-001: proves this actually reads real Android permission/service state
 * (via Robolectric's shadows), not just that it compiles against
 * [com.mototriptracker.app.core.model.CapabilityInputs]'s shape.
 * `activityRecognitionGranted`/`backgroundLocationGranted` are deliberately
 * not asserted here - both branch on `Build.VERSION.SDK_INT`, and pinning an
 * SDK level just for this test would make it brittle for no real gain over
 * what's already a simple, directly-readable `if` in the production class.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidCapabilityInputsProviderTest {

    private fun newAutoTrackingPreferences(context: Context) = AutoTrackingPreferences(
        PreferenceDataStoreFactory.create(
            produceFile = { File(context.filesDir, "test-settings-${System.nanoTime()}.preferences_pb") }
        )
    )

    @Test
    fun reportsNothingGrantedAndAutoTrackingOffOnACleanInstall() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = AndroidCapabilityInputsProvider(context, newAutoTrackingPreferences(context))

        val inputs = provider.current()

        assertFalse(inputs.preciseLocationGranted)
        assertFalse(inputs.approximateLocationGranted)
        assertFalse(inputs.autoTrackingEnabledByUser)
    }

    @Test
    fun reflectsGrantedLocationPermissionAndEnabledLocationServices() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val locationManager = context.getSystemService(LocationManager::class.java)
        shadowOf(locationManager).setLocationEnabled(true)
        val autoTrackingPreferences = newAutoTrackingPreferences(context)
        autoTrackingPreferences.setAutoTrackingEnabled(true)

        val inputs = AndroidCapabilityInputsProvider(context, autoTrackingPreferences).current()

        assertTrue(inputs.preciseLocationGranted)
        assertTrue("precise implies approximate", inputs.approximateLocationGranted)
        assertTrue(inputs.locationServicesEnabled)
        assertTrue(inputs.autoTrackingEnabledByUser)
    }

    @Test
    fun reflectsNotificationsDisabledByTheUser() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = context.getSystemService(NotificationManager::class.java)
        (shadowOf(manager) as ShadowNotificationManager).setNotificationsEnabled(false)

        val inputs = AndroidCapabilityInputsProvider(context, newAutoTrackingPreferences(context)).current()

        assertFalse(inputs.notificationsEnabled)
    }
}
