package com.mototriptracker.app.feature.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.mototriptracker.app.domain.capability.CapabilityIssue

/**
 * PERM-003 / `privacy-permissions.md` §9, §19.4: what a person reads when the app cannot do its job as well as it
 * should, and the one thing that fixes it. Plain and specific - it names the cause instead of saying "degraded" (the
 * lesson of the approximate-only finding, where the only thing shown was "No GPS signal").
 *
 * [startAnywayMessage] is used when the person taps START TRIP while this problem is present and it would leave them
 * with a trip that records no route (`blocksRoute`); it says exactly that, once, instead of letting it happen silently.
 * Tone and wording are a baseline; `PERM-001` (onboarding) revisits them with the owner.
 */
data class IssueCopy(val title: String, val message: String, val actionLabel: String, val startAnywayMessage: String?)

object CapabilityIssueCopy {

    fun of(issue: CapabilityIssue): IssueCopy = when (issue) {
        CapabilityIssue.LOCATION_PERMISSION_MISSING -> IssueCopy(
            title = "Location access needed",
            message = "Allow Moto Trip Tracker to use your location so it can record your trips.",
            actionLabel = "Allow location",
            startAnywayMessage = null // Start itself asks for the permission; there is nothing to "start anyway" without it.
        )
        CapabilityIssue.LOCATION_SERVICES_OFF -> IssueCopy(
            title = "Location is off",
            message = "Turn on Location on your phone so your route can be recorded.",
            actionLabel = "Open Location settings",
            startAnywayMessage = "Location is off, so this trip will not record a route. You can turn it on now, or start anyway."
        )
        CapabilityIssue.PRECISE_LOCATION_MISSING -> IssueCopy(
            title = "Precise location needed",
            message = "Only approximate location is allowed, so no usable route can be recorded. Allow precise location.",
            actionLabel = "Allow precise location",
            startAnywayMessage = "Only approximate location is allowed, so this trip will not record a route. You can allow precise location now, or start anyway."
        )
        CapabilityIssue.NOTIFICATIONS_DENIED -> IssueCopy(
            title = "Trip controls are hidden",
            message = "Notifications are off, so the trip notification with Pause and Finish will not show while you ride. Trips still record.",
            actionLabel = "Allow notifications",
            startAnywayMessage = null // does not stop a route from being recorded, so it never gets in the way of Start
        )
    }
}

/**
 * The decision after a permission request came back not granted. A "no" the person gave is respected (§19.4
 * "respetar `Ahora no`/denegación", "no spamear diálogos"). Settings opens only when the system answered *without
 * asking* - it will not show its dialog again, so the person is left with no way to say yes except the phone's
 * Settings, and the button would otherwise look dead. Whether the dialog was shown is told by how fast the answer came:
 * a human cannot respond in [SYSTEM_ANSWER_MS]; the platform refusing to ask does.
 *
 * (`shouldShowRequestPermissionRationale` looks like the obvious signal but is ambiguous right after someone picks
 * "approximate" in Android 12+'s combined dialog - it would open Settings straight after an explicit choice.)
 */
fun shouldOpenSettingsAfterDenial(granted: Boolean, answeredInMs: Long): Boolean = !granted && answeredInMs < SYSTEM_ANSWER_MS

/** Faster than this and no person answered: the system declined to show its dialog. */
const val SYSTEM_ANSWER_MS = 350L

/**
 * Returns what to call when the person taps a problem's action button. Requests the permission when the system will
 * still show its dialog, and otherwise opens the right Settings page; `onAfterAttempt` lets the caller re-check
 * readiness (the answer may have changed either way).
 */
@Composable
fun rememberCapabilityFixer(onAfterAttempt: () -> Unit): (CapabilityIssue) -> Unit {
    val context = LocalContext.current
    val afterAttempt = rememberUpdatedState(onAfterAttempt)

    // When the last request was launched, to tell a person answering from the system declining to ask.
    val launchedAt = remember { longArrayOf(0L) }
    fun answeredInMs() = SystemClock.elapsedRealtime() - launchedAt[0]

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val fine = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        // What was asked for: precise location. Approximate alone is not the fix for either location problem.
        if (shouldOpenSettingsAfterDenial(granted = fine, answeredInMs = answeredInMs())) context.openAppSettings()
        afterAttempt.value()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (shouldOpenSettingsAfterDenial(granted = granted, answeredInMs = answeredInMs())) context.openNotificationSettings()
        afterAttempt.value()
    }

    return { issue ->
        when (issue) {
            CapabilityIssue.LOCATION_SERVICES_OFF -> {
                context.startActivitySafely(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                afterAttempt.value()
            }
            CapabilityIssue.LOCATION_PERMISSION_MISSING, CapabilityIssue.PRECISE_LOCATION_MISSING ->
                {
                    launchedAt[0] = SystemClock.elapsedRealtime()
                    locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            CapabilityIssue.NOTIFICATIONS_DENIED ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launchedAt[0] = SystemClock.elapsedRealtime()
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Below Android 13 there is no runtime permission: the only place to turn them back on is Settings.
                    context.openNotificationSettings()
                    afterAttempt.value()
                }
        }
    }
}

private fun Context.startActivitySafely(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun Context.openAppSettings() =
    startActivitySafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

private fun Context.openNotificationSettings() =
    startActivitySafely(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
