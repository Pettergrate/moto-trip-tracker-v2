package com.mototriptracker.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mototriptracker.app.core.theme.MotoTripTrackerTheme
import com.mototriptracker.app.core.theme.ThemeBase
import com.mototriptracker.app.feature.settings.AppearanceViewModel
import androidx.compose.ui.graphics.Color as ComposeColor
import com.mototriptracker.app.feature.onboarding.OnboardingGate
import com.mototriptracker.app.feature.onboarding.OnboardingViewModel
import com.mototriptracker.app.feature.onboarding.WelcomeScreen
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
            val appearanceViewModel: AppearanceViewModel = hiltViewModel()
            val savedAppearance by appearanceViewModel.appearance.collectAsStateWithLifecycle()
            val appearance = savedAppearance
            // SET-002: a light base needs dark system-bar icons and a dark base light ones - the call in onCreate above
            // is only the dark default, so it is made again whenever the base changes.
            if (appearance != null) {
                DisposableEffect(appearance.base) {
                    val barStyle = if (appearance.base == ThemeBase.DARK) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    }
                    enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
                    onDispose { }
                }
            }
            // The theme is read in a few milliseconds; until then just black (the default base), never Home in a colour
            // that is about to change.
            if (appearance == null) {
                Box(modifier = Modifier.fillMaxSize().background(ComposeColor.Black))
                return@setContent
            }
            MotoTripTrackerTheme(appearance) {
                val onboarding: OnboardingViewModel = hiltViewModel()
                val gate by onboarding.gate.collectAsStateWithLifecycle()
                when {
                    // The preference is read in a few milliseconds; until then, just the background - never a flash of Home.
                    gate == OnboardingGate.LOADING -> Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                    // PERM-001 / ONB-01: once, on a normal launch. Arriving from the tracking notification means a trip is
                    // already being recorded, so nothing is put in front of it.
                    gate == OnboardingGate.WELCOME && initialDestination == Destination.Home ->
                        WelcomeScreen(onContinue = onboarding::onWelcomeContinue)
                    else -> AppNavHost(initialDestination = initialDestination)
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_ACTIVE_TRIP = "com.mototriptracker.app.extra.OPEN_ACTIVE_TRIP"
    }
}
