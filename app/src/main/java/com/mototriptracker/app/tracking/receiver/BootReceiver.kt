package com.mototriptracker.app.tracking.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mototriptracker.app.tracking.activityrecognition.ActivityRecognitionRegistrar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * DET-001/F0.4 §4.5 (AND-007): re-registers Activity Recognition after
 * `BOOT_COMPLETED` or an app update (`MY_PACKAGE_REPLACED`) — Google
 * documents that the Transition API registration does not survive either.
 * No GPS/foreground work starts here (F0.8 §15's own note on this
 * component: "no deja GPS continuo") — only the passive AR registration.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var registrar: ActivityRecognitionRegistrar

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Re-registering Activity Recognition after ${intent.action}")
                registrar.register()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
