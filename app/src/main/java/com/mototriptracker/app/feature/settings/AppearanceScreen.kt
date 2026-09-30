package com.mototriptracker.app.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mototriptracker.app.R
import com.mototriptracker.app.core.theme.AccentColor
import com.mototriptracker.app.core.theme.Appearance
import com.mototriptracker.app.core.theme.ThemeBase
import com.mototriptracker.app.core.theme.appearanceColorScheme
import com.mototriptracker.app.core.theme.contrastRatio

@StringRes
internal fun ThemeBase.labelRes(): Int = if (this == ThemeBase.DARK) R.string.base_dark else R.string.base_light

/**
 * SET-002: a base and an accent, changed live - the screen itself is drawn in the theme being chosen, and the base
 * tiles show how each base looks with the current accent, so nothing has to be imagined.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit, viewModel: AppearanceViewModel = hiltViewModel()) {
    val saved by viewModel.appearance.collectAsStateWithLifecycle()
    val appearance = saved ?: Appearance.Default
    val defaultVehicleType by viewModel.defaultVehicleType.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.appearance_title)) },
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
            item { SettingsSection(stringResource(R.string.appearance_section_preview)) { Preview() } }
            item {
                SettingsSection(stringResource(R.string.appearance_section_theme)) {
                    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ThemeBase.entries.forEach { base ->
                            BaseTile(
                                base = base,
                                accent = appearance.accent,
                                selected = appearance.base == base,
                                onClick = { viewModel.onBaseSelected(base) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            item {
                SettingsSection(stringResource(R.string.appearance_section_accent)) {
                    AccentPalette(
                        selected = appearance.accent,
                        onSelected = viewModel::onAccentSelected,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            item {
                SettingsSection(stringResource(R.string.motorcycle_vehicle_type_label)) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        VehicleTypePicker(selected = defaultVehicleType, onSelected = viewModel::onDefaultVehicleTypeSelected)
                    }
                }
            }
            item {
                TextButton(onClick = viewModel::onResetToDefault, enabled = appearance != Appearance.Default) {
                    Text(stringResource(R.string.appearance_reset))
                }
            }
        }
    }
}

/** A stand-in for what the person sees everywhere: a card, its text and the two kinds of button. */
@Composable
private fun Preview() {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.appearance_preview_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.appearance_preview_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {}, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.appearance_preview_primary)) }
            OutlinedButton(onClick = {}, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.appearance_preview_secondary)) }
        }
    }
}

/** One of the two bases, drawn in its own colours (with the current accent) so choosing it needs no imagination. */
@Composable
private fun BaseTile(base: ThemeBase, accent: AccentColor, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = appearanceColorScheme(Appearance(base, accent))
    val label = stringResource(base.labelRes())
    Column(
        modifier = modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .border(
                BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                MaterialTheme.shapes.medium
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(64.dp).background(scheme.background, MaterialTheme.shapes.small).padding(8.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth(0.6f).height(10.dp).background(scheme.primary, MaterialTheme.shapes.extraSmall))
                Box(Modifier.fillMaxWidth().height(22.dp).background(scheme.surfaceVariant, MaterialTheme.shapes.extraSmall))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
/** The tick on a swatch: black or white, whichever reads better on that colour. */
private fun swatchCheckColor(accent: AccentColor): Color =
    if (contrastRatio(Color.Black, accent.color) >= contrastRatio(Color.White, accent.color)) Color.Black else Color.White

@Composable
private fun AccentPalette(selected: AccentColor, onSelected: (AccentColor) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AccentColor.entries.forEach { accent ->
            val isSelected = accent == selected
            val label = stringResource(accent.labelRes)
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(accent.color, MaterialTheme.shapes.small)
                    .then(
                        if (isSelected) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface), MaterialTheme.shapes.small) else Modifier
                    )
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelected(accent) })
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, tint = swatchCheckColor(accent), modifier = Modifier.size(24.dp))
            }
        }
    }
}
