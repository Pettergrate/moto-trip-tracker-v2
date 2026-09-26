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
import com.mototriptracker.app.feature.favorites.FavoritesScreen
import com.mototriptracker.app.feature.debug.DebugScreen
import com.mototriptracker.app.feature.fieldtest.FieldTestHarnessScreen
import com.mototriptracker.app.feature.history.HistoryScreen
import com.mototriptracker.app.feature.home.HomeScreen
import com.mototriptracker.app.feature.settings.SettingsPlaceholderScreen
import com.mototriptracker.app.feature.trash.TrashScreen
import com.mototriptracker.app.feature.split.SplitScreen
import com.mototriptracker.app.feature.tripdetail.TripDetailScreen
import com.mototriptracker.app.feature.trim.TrimScreen

/**
 * ADR-011/F0.9 §3: the app shell. `backStack` is a plain
 * `SnapshotStateList<Destination>`, not a saveable/serializable one - see
 * [Destination]'s own KDoc for why that's a deliberate v1 scope decision,
 * not an oversight.
 */
@Composable
fun AppNavHost(initialDestination: Destination = Destination.Home) {
    // Home always sits underneath so Back from a deep-linked destination
    // (e.g. the tracking notification's content intent opening Active Trip
    // directly) lands on Home instead of exiting the app.
    val backStack = remember {
        mutableStateListOf<Destination>(Destination.Home).apply {
            if (initialDestination != Destination.Home) add(initialDestination)
        }
    }

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

    fun navigateToTripDetail(tripId: String) {
        backStack.add(Destination.TripDetail(tripId))
    }

    fun navigateToSplit(tripId: String) {
        backStack.add(Destination.Split(tripId))
    }

    fun navigateToTrim(tripId: String) {
        backStack.add(Destination.Trim(tripId))
    }

    fun navigateToFieldTestHarness() {
        if (backStack.lastOrNull() != Destination.FieldTestHarness) {
            backStack.add(Destination.FieldTestHarness)
        }
    }

    fun navigateToDebug() {
        if (backStack.lastOrNull() != Destination.Debug) {
            backStack.add(Destination.Debug)
        }
    }

    fun navigateToTrash() {
        if (backStack.lastOrNull() != Destination.Trash) {
            backStack.add(Destination.Trash)
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = { destination ->
            when (destination) {
                Destination.Home -> NavEntry(destination) {
                    MainTabScaffold(current = destination, onTabSelected = ::navigateToTab, onSettingsClick = ::navigateToSettings) {
                        HomeScreen(onViewActiveTrip = ::navigateToActiveTrip, onOpenTripDetail = ::navigateToTripDetail)
                    }
                }

                Destination.History -> NavEntry(destination) {
                    MainTabScaffold(current = destination, onTabSelected = ::navigateToTab, onSettingsClick = ::navigateToSettings) {
                        HistoryScreen(onOpenTripDetail = ::navigateToTripDetail)
                    }
                }

                Destination.Favorites -> NavEntry(destination) {
                    MainTabScaffold(current = destination, onTabSelected = ::navigateToTab, onSettingsClick = ::navigateToSettings) {
                        FavoritesScreen(onOpenTripDetail = ::navigateToTripDetail)
                    }
                }

                Destination.Settings -> NavEntry(destination) {
                    SettingsPlaceholderScreen(
                        onBack = { backStack.removeLastOrNull() },
                        onOpenFieldTestHarness = ::navigateToFieldTestHarness,
                        onOpenTrash = ::navigateToTrash
,
                        onOpenDebug = ::navigateToDebug
                    )
                }

                Destination.FieldTestHarness -> NavEntry(destination) {
                    FieldTestHarnessScreen(onBack = { backStack.removeLastOrNull() })
                }

                Destination.Debug -> NavEntry(destination) {
                    DebugScreen(onBack = { backStack.removeLastOrNull() })
                }

                Destination.Trash -> NavEntry(destination) {
                    TrashScreen(onBack = { backStack.removeLastOrNull() })
                }

                Destination.ActiveTrip -> NavEntry(destination) {
                    ActiveTripScreen(onBack = { backStack.removeLastOrNull() })
                }

                is Destination.TripDetail -> NavEntry(destination) {
                    TripDetailScreen(
                        tripId = destination.tripId,
                        onBack = { backStack.removeLastOrNull() },
                        onSplit = ::navigateToSplit,
                        onTrim = ::navigateToTrim
                    )
                }

                is Destination.Trim -> NavEntry(destination) {
                    TrimScreen(
                        tripId = destination.tripId,
                        onBack = { backStack.removeLastOrNull() },
                        // A successful trim supersedes the Trip both this screen and the
                        // Trip Detail beneath it were about - pop both.
                        onTrimDone = {
                            backStack.removeLastOrNull()
                            backStack.removeLastOrNull()
                        }
                    )
                }

                is Destination.Split -> NavEntry(destination) {
                    SplitScreen(
                        tripId = destination.tripId,
                        onBack = { backStack.removeLastOrNull() },
                        // A successful split supersedes the Trip both this screen and
                        // the Trip Detail beneath it were about - pop both.
                        onSplitDone = {
                            backStack.removeLastOrNull()
                            backStack.removeLastOrNull()
                        }
                    )
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
    Destination.Settings, Destination.ActiveTrip, Destination.FieldTestHarness, Destination.Trash, Destination.Debug, is Destination.TripDetail, is Destination.Split, is Destination.Trim ->
        error("$this is not a bottom-nav tab")
}

private fun Destination.tabIcon() = when (this) {
    Destination.Home -> Icons.Default.Home
    Destination.History -> Icons.AutoMirrored.Filled.List
    Destination.Favorites -> Icons.Default.Star
    Destination.Settings, Destination.ActiveTrip, Destination.FieldTestHarness, Destination.Trash, Destination.Debug, is Destination.TripDetail, is Destination.Split, is Destination.Trim ->
        error("$this is not a bottom-nav tab")
}
