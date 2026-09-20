package com.mototriptracker.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The real SET-01/SET-02 (Auto Tracking readiness, units, notifications,
 * diagnostics) is a separate, not-yet-built task family - this placeholder
 * exists only so UI-001's Settings entry point (F0.9 §3.2) is a complete,
 * navigable gesture rather than a dead end.
 *
 * The field-test harness entry (EXP-002) lives here rather than as its own
 * tab or Home affordance - EXP-001's acceptance criterion is that this stays
 * internal tooling, not user-facing Core UX.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPlaceholderScreen(onBack: () -> Unit, onOpenFieldTestHarness: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Settings are coming soon", style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = onOpenFieldTestHarness, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 24.dp)) {
                Text("Field test harness (internal)")
            }
        }
    }
}
