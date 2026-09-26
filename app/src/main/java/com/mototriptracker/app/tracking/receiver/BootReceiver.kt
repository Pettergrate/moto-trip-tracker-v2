package com.mototriptracker.app.tracking.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mototriptracker.app.tracking.activityrecognition.ActivityRecognitionRegistrar
import com.mototriptracker.app.tracking.coordinator.TrackingSessionCoordinator
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * DET-001/F0.4 §4.5 (AND-007): re-registers Activity Recognition after
 * `BOOT_COMPLETED` or an app update (`MY_PACKAGE_REPLACED`) — Google
 * documents that the Transition API registration does not survive either.
 * No GPS/foreground work starts here (F0.8 §15's own note on this
 * component: "no deja GPS continuo") — only the passive AR registration.
 *
 * REC-003/F0.10 §10.3: `BOOT_COMPLETED` additionally reconciles an orphaned
 * capture - one still `ACTIVE` from before the reboot is sealed as
 * interrupted (with a visible partial Trip when there is a route). It never
 * starts a location foreground service or claims the trip continued ("no se usa
 * como regla universal para arrancar inmediatamente un location FGS"). An app
 * *update* is not a reboot - the same boot's `elapsedRealtime` still applies -
 * so `MY_PACKAGE_REPLACED` deliberately does not seal anything.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var registrar: ActivityRecognitionRegistrar

    /** `Lazy`: the coordinator needs WorkManager (processing scheduler), initialised in `Application.onCreate` after injection. */
    @Inject lateinit var coordinator: Lazy<TrackingSessionCoordinator>

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i(TAG, "Re-registering Activity Recognition after ${intent.action}")
                registrar.register()
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val outcome = coordinator.get().sealActiveCaptureAfterBoot()
                        Log.i(TAG, "Boot reconciliation: $outcome")
                    } catch (failure: Exception) {
                        // Never let reconciliation take the receiver down; the next app start retries the same check.
                        Log.w(TAG, "Boot reconciliation failed", failure)
                    } finally {
                        pending.finish()
                    }
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Re-registering Activity Recognition after ${intent.action}")
                registrar.register()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
