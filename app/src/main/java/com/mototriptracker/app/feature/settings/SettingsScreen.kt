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
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mototriptracker.app.BuildConfig
import com.mototriptracker.app.R
import com.mototriptracker.app.core.theme.Appearance

/**
 * SET-001 / `ux-navigation.md` §14 (SET-01). Sections in the order the document gives them, showing only what works
 * today: no toggle that does nothing. Tracking (Auto Tracking) and Units/Notifications join when they exist; Appearance (SET-002)
 * comes with `SET-002`. The two internal tools sit under "Advanced" (`EXP-001`'s acceptance: internal, not Core UX).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenFieldTestHarness: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAutoTracking: () -> Unit,
    onOpenMotorcycles: () -> Unit,
    appearanceViewModel: AppearanceViewModel = hiltViewModel(),
    autoTrackingViewModel: AutoTrackingViewModel = hiltViewModel(),
    motorcyclesViewModel: MotorcyclesViewModel = hiltViewModel()
) {
    val appearance by appearanceViewModel.appearance.collectAsStateWithLifecycle()
    val autoTracking by autoTrackingViewModel.uiState.collectAsStateWithLifecycle()
    val motorcycles by motorcyclesViewModel.motorcycles.collectAsStateWithLifecycle()
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
                SettingsSection(stringResource(R.string.settings_section_tracking)) {
                    SettingsRow(
                        icon = Icons.Filled.TwoWheeler,
                        title = stringResource(R.string.autotracking_title),
                        subtitle = autoTracking?.let { stringResource(it.state.shortLabelRes()) },
                        onClick = onOpenAutoTracking,
                        showDivider = false
                    )
                }
            }
            item {
                SettingsSection(stringResource(R.string.settings_section_vehicles)) {
                    SettingsRow(
                        icon = Icons.Filled.TwoWheeler,
                        title = stringResource(R.string.settings_motorcycles_title),
                        subtitle = if (motorcycles.isEmpty()) {
                            stringResource(R.string.settings_motorcycles_subtitle_empty)
                        } else {
                            stringResource(R.string.settings_motorcycles_subtitle_count, motorcycles.size)
                        },
                        onClick = onOpenMotorcycles,
                        showDivider = false
                    )
                }
            }
            item {
                SettingsSection(stringResource(R.string.settings_section_appearance)) {
                    val current = appearance ?: Appearance.Default
                    SettingsRow(
                        icon = Icons.Filled.Palette,
                        title = stringResource(R.string.settings_theme_title),
                        subtitle = stringResource(
                            R.string.settings_theme_value,
                            stringResource(current.base.labelRes()),
                            stringResource(current.accent.labelRes)
                        ),
                        onClick = onOpenAppearance,
                        showDivider = false
                    )
                }
            }
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
