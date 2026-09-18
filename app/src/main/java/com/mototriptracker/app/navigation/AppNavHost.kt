package com.mototriptracker.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.mototriptracker.app.feature.active.ActiveTripScreen
import com.mototriptracker.app.feature.favorites.FavoritesPlaceholderScreen
import com.mototriptracker.app.feature.history.HistoryPlaceholderScreen
import com.mototriptracker.app.feature.home.HomeScreen
import com.mototriptracker.app.feature.settings.SettingsPlaceholderScreen

/**
 * ADR-011/F0.9 §3: the app shell. `backStack` is a plain
 * `SnapshotStateList<Destination>`, not a saveable/serializable one - see
 * [Destination]'s own KDoc for why that's a deliberate v1 scope decision,
 * not an oversight.
 */
@Composable
fun AppNavHost() {
    val backStack = remember { mutableStateListOf<Destination>(Destination.Home) }

    fun navigateToTab(tab: Destination) {
        // ADR-011: switching tabs replaces the stack rather than pushing -
        // the conventional bottom-nav behavior, not a deep per-tab history.
        backStack.clear()
        backStack.add(tab)
    }

    fun navigateToActiveTrip() {
        // "un destino especial persistente, no una pestaña" - at most one
        // instance in the stack, never duplicated by repeated taps.
        if (backStack.lastOrNull() != Destination.ActiveTrip) {
            backStack.add(Destination.ActiveTrip)
        }
    }

    fun navigateToSettings() {
        if (backStack.lastOrNull() != Destination.Settings) {
            backStack.add(Destination.Settings)
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = { destination ->
            when (destination) {
                Destination.Home -> NavEntry(destination) {
                    MainTabScaffold(current = destination, onTabSelected = ::navigateToTab, onSettingsClick = ::navigateToSettings) {
                        HomeScreen(onViewActiveTrip = ::navigateToActiveTrip)
                    }
                }

                Destination.History -> NavEntry(destination) {
                    MainTabScaffold(current = destination, onTabSelected = ::navigateToTab, onSettingsClick = ::navigateToSettings) {
                        HistoryPlaceholderScreen()
                    }
                }

                Destination.Favorites -> NavEntry(destination) {
                    MainTabScaffold(current = destination, onTabSelected = ::navigateToTab, onSettingsClick = ::navigateToSettings) {
                        FavoritesPlaceholderScreen()
                    }
                }

                Destination.Settings -> NavEntry(destination) {
                    SettingsPlaceholderScreen(onBack = { backStack.removeLastOrNull() })
                }

                Destination.ActiveTrip -> NavEntry(destination) {
                    ActiveTripScreen(onBack = { backStack.removeLastOrNull() })
                }
            }
        }
    )
}

/** F0.9 §3.2/TRP-01's wireframe: the bottom nav + top bar wrap only the three tabs - Active Trip and Settings have their own, simpler chrome. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabScaffold(
    current: Destination,
    onTabSelected: (Destination) -> Unit,
    onSettingsClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Moto Trip Tracker") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                bottomNavDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = destination == current,
                        onClick = { onTabSelected(destination) },
                        icon = { Icon(destination.tabIcon(), contentDescription = null) },
                        label = { Text(destination.tabLabel()) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            content()
        }
    }
}

private fun Destination.tabLabel(): String = when (this) {
    Destination.Home -> "Home"
    Destination.History -> "History"
    Destination.Favorites -> "Favorites"
    Destination.Settings, Destination.ActiveTrip -> error("$this is not a bottom-nav tab")
}

private fun Destination.tabIcon() = when (this) {
    Destination.Home -> Icons.Default.Home
    Destination.History -> Icons.AutoMirrored.Filled.List
    Destination.Favorites -> Icons.Default.Star
    Destination.Settings, Destination.ActiveTrip -> error("$this is not a bottom-nav tab")
}
