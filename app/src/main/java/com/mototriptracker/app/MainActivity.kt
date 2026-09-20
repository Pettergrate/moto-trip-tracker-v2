package com.mototriptracker.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mototriptracker.app.core.theme.MotoTripTrackerTheme
import com.mototriptracker.app.navigation.AppNavHost
import com.mototriptracker.app.navigation.Destination
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The black & orange theme (2026-09-19) is always dark, regardless of
        // the system day/night setting - force light system-bar icons so
        // they stay visible against it, rather than the OS defaulting to
        // dark icons on a black status bar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        // NOT-001/F0.9 §7: tapping the tracking notification's body opens
        // Active Trip directly rather than just Home - only handled for a
        // cold/recreated launch (this `onCreate` reading the extra); if the
        // app is already running elsewhere in the foreground, Android simply
        // brings the existing task forward without forcing this navigation,
        // a deliberately simpler scope than a full `onNewIntent`-driven
        // back-stack push for what F0.9 treats as a UX nicety, not a tested
        // acceptance criterion.
        val initialDestination = if (intent?.getBooleanExtra(EXTRA_OPEN_ACTIVE_TRIP, false) == true) {
            Destination.ActiveTrip
        } else {
            Destination.Home
        }
        setContent {
            MotoTripTrackerTheme {
                AppNavHost(initialDestination = initialDestination)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_ACTIVE_TRIP = "com.mototriptracker.app.extra.OPEN_ACTIVE_TRIP"
    }
}
