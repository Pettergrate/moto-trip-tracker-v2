package com.mototriptracker.app.feature.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * `TrackingForegroundService.onStartCommand`'s `startForeground(..., FOREGROUND_SERVICE_TYPE_LOCATION)`
 * throws a `SecurityException` at the OS level when neither location
 * permission is granted - a real crash this project hit for real (2026-09-22,
 * recorded in `MET-001`'s backlog entry) because nothing anywhere guarded the
 * two places that start a capture fresh (Home's Start Trip button, the
 * field-test harness's own Start button). `PERM-001`'s full onboarding flow
 * is a separate, larger task - this is only the narrow guard so tapping
 * Start can never crash the app again, with an honest explanation on denial
 * rather than a silent no-op (F0.9 §16: "denegar no crea un dead end").
 *
 * @return a function to call in place of starting the capture directly: it
 * runs [onGranted] immediately if location permission is already held, or
 * requests it first and runs [onGranted] only if the user grants it -
 * [onDenied] otherwise.
 */
@Composable
fun rememberStartWithLocationPermission(onGranted: () -> Unit, onDenied: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) onGranted() else onDenied()
    }
    return {
        val alreadyGranted = hasLocationPermission(context = context)
        if (alreadyGranted) {
            onGranted()
        } else {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Shared by every [rememberStartWithLocationPermission] caller so a denial explains itself instead of a silent no-op (F0.9 §16: "denegar no crea un dead end"). */
@Composable
fun LocationPermissionDeniedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Location permission needed") },
        text = { Text("Moto Trip Tracker needs location access to record your trips. You can grant it from your phone's Settings and try again.") },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}
