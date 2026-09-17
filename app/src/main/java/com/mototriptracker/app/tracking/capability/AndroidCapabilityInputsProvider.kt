package com.mototriptracker.app.tracking.capability

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.mototriptracker.app.core.datastore.AutoTrackingPreferences
import com.mototriptracker.app.core.model.CapabilityInputs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * F0.11 §5's real permission/service matrix, read straight from Android -
 * the actual input side `CapabilityResolver` has been waiting for since
 * CAP-001. No production caller before AUTO-001.
 *
 * `ACTIVITY_RECOGNITION`/`ACCESS_BACKGROUND_LOCATION` only exist as runtime
 * (dangerous) permissions from API 29 onward; below that, Android has no
 * separate grant for either, so this reports them as implicitly available
 * rather than inventing a permission check that can't exist on those OS
 * versions.
 */
class AndroidCapabilityInputsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val autoTrackingPreferences: AutoTrackingPreferences
) : CapabilityInputsProvider {

    override suspend fun current(): CapabilityInputs {
        val preciseLocationGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val approximateLocationGranted = preciseLocationGranted || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val activityRecognitionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            true
        }
        val backgroundLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            approximateLocationGranted
        }
        val locationManager = context.getSystemService(LocationManager::class.java)
        val locationServicesEnabled = locationManager?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false

        return CapabilityInputs(
            preciseLocationGranted = preciseLocationGranted,
            approximateLocationGranted = approximateLocationGranted,
            activityRecognitionGranted = activityRecognitionGranted,
            backgroundLocationGranted = backgroundLocationGranted,
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            locationServicesEnabled = locationServicesEnabled,
            autoTrackingEnabledByUser = autoTrackingPreferences.autoTrackingEnabled.first()
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
