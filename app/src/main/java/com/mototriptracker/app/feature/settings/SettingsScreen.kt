package com.mototriptracker.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mototriptracker.app.BuildConfig
import com.mototriptracker.app.R

/**
 * SET-001 / `ux-navigation.md` §14 (SET-01). Sections in the order the document gives them, showing only what works
 * today: no toggle that does nothing. Tracking (Auto Tracking) and Units/Notifications join when they exist; Appearance
 * comes with `SET-002`. The two internal tools sit under "Advanced" (`EXP-001`'s acceptance: internal, not Core UX).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenFieldTestHarness: () -> Unit, onOpenTrash: () -> Unit, onOpenDebug: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                SettingsSection(stringResource(R.string.settings_section_data)) {
                    SettingsRow(
                        icon = Icons.Filled.Delete,
                        title = stringResource(R.string.settings_trash_title),
                        subtitle = stringResource(R.string.settings_trash_subtitle),
                        onClick = onOpenTrash,
                        showDivider = false
                    )
                }
            }
            item {
                SettingsSection(stringResource(R.string.settings_section_about)) {
                    SettingsRow(
                        icon = Icons.Filled.Info,
                        title = stringResource(R.string.settings_version_title),
                        subtitle = versionText()
                    )
                    SettingsRow(
                        icon = Icons.Filled.MonitorHeart,
                        title = stringResource(R.string.settings_diagnostics_title),
                        subtitle = stringResource(R.string.settings_diagnostics_subtitle),
                        onClick = onOpenDebug,
                        showDivider = false
                    )
                }
            }
            item {
                SettingsSection(stringResource(R.string.settings_section_advanced)) {
                    SettingsRow(
                        icon = Icons.Filled.Science,
                        title = stringResource(R.string.settings_field_test_title),
                        subtitle = stringResource(R.string.settings_field_test_subtitle),
                        onClick = onOpenFieldTestHarness,
                        showDivider = false
                    )
                }
            }
        }
    }
}

@Composable
private fun versionText(): String = stringResource(R.string.settings_version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
