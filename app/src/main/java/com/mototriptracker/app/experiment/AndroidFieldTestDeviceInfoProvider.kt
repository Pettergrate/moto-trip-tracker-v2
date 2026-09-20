package com.mototriptracker.app.experiment

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.location.LocationManagerCompat
import com.mototriptracker.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** F0.6 §6.1's device-derivable fields, read straight from Android — same pattern as `AndroidCapabilityInputsProvider`. */
class AndroidFieldTestDeviceInfoProvider @Inject constructor(
    @param:ApplicationContext private val context: Context
) : FieldTestDeviceInfoProvider {

    override fun current(): FieldTestDeviceSnapshot {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val locationManager = context.getSystemService(LocationManager::class.java)
        val locationEnabled = locationManager?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false

        return FieldTestDeviceSnapshot(
            appVersion = BuildConfig.VERSION_NAME,
            phoneManufacturer = Build.MANUFACTURER,
            phoneModel = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            playServicesVersion = null,
            batterySaverState = if (powerManager?.isPowerSaveMode == true) "enabled" else "disabled",
            locationSettingsState = if (locationEnabled) "enabled" else "disabled",
            notificationPermissionState = if (NotificationManagerCompat.from(context).areNotificationsEnabled()) "enabled" else "disabled",
            screenStateAtStart = if (powerManager?.isInteractive == true) "on" else "off"
        )
    }
}
