package com.mototriptracker.app.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mototriptracker.app.R
import com.mototriptracker.app.domain.capability.AutoTrackingRequirement
import com.mototriptracker.app.domain.capability.AutoTrackingState
import com.mototriptracker.app.domain.capability.CapabilityIssue
import com.mototriptracker.app.domain.capability.RequirementStatus
import com.mototriptracker.app.feature.common.rememberCapabilityFixer

@StringRes
internal fun AutoTrackingState.shortLabelRes(): Int = when (this) {
    AutoTrackingState.OFF -> R.string.autotracking_short_off
    AutoTrackingState.READY -> R.string.autotracking_short_ready
    AutoTrackingState.LIMITED -> R.string.autotracking_short_limited
    AutoTrackingState.NEEDS_SETUP -> R.string.autotracking_short_needs_setup
    AutoTrackingState.LOCATION_PROBLEM -> R.string.autotracking_short_location_problem
}

@StringRes
internal fun AutoTrackingState.titleRes(): Int = when (this) {
    AutoTrackingState.OFF -> R.string.autotracking_state_off_title
    AutoTrackingState.READY -> R.string.autotracking_state_ready_title
    AutoTrackingState.LIMITED -> R.string.autotracking_state_limited_title
    AutoTrackingState.NEEDS_SETUP -> R.string.autotracking_state_needs_setup_title
    AutoTrackingState.LOCATION_PROBLEM -> R.string.autotracking_state_location_problem_title
}

@StringRes
internal fun AutoTrackingState.textRes(): Int = when (this) {
    AutoTrackingState.OFF -> R.string.autotracking_state_off_text
    AutoTrackingState.READY -> R.string.autotracking_state_ready_text
    AutoTrackingState.LIMITED -> R.string.autotracking_state_limited_text
    AutoTrackingState.NEEDS_SETUP -> R.string.autotracking_state_needs_setup_text
    AutoTrackingState.LOCATION_PROBLEM -> R.string.autotracking_state_location_problem_text
}

@StringRes
internal fun AutoTrackingRequirement.titleRes(): Int = when (this) {
    AutoTrackingRequirement.PRECISE_LOCATION -> R.string.autotracking_req_precise_title
    AutoTrackingRequirement.LOCATION_SERVICES -> R.string.autotracking_req_services_title
    AutoTrackingRequirement.ACTIVITY_RECOGNITION -> R.string.autotracking_req_activity_title
    AutoTrackingRequirement.NOTIFICATIONS -> R.string.autotracking_req_notifications_title
    AutoTrackingRequirement.BACKGROUND_LOCATION -> R.string.autotracking_req_background_title
}

@StringRes
internal fun AutoTrackingRequirement.textRes(): Int = when (this) {
    AutoTrackingRequirement.PRECISE_LOCATION -> R.string.autotracking_req_precise_text
    AutoTrackingRequirement.LOCATION_SERVICES -> R.string.autotracking_req_services_text
    AutoTrackingRequirement.ACTIVITY_RECOGNITION -> R.string.autotracking_req_activity_text
    AutoTrackingRequirement.NOTIFICATIONS -> R.string.autotracking_req_notifications_text
    AutoTrackingRequirement.BACKGROUND_LOCATION -> R.string.autotracking_req_background_text
}

/**
 * The fix the app can already offer for a missing requirement, or `null` when there is none *yet*: the two that need
 * Activity Recognition and background location are requested by the guided flow of `PERM-002`, which this screen
 * leaves as "Needed" until then rather than showing a button that goes nowhere.
 */
internal fun AutoTrackingRequirement.fix(): CapabilityIssue? = when (this) {
    AutoTrackingRequirement.PRECISE_LOCATION -> CapabilityIssue.PRECISE_LOCATION_MISSING
    AutoTrackingRequirement.LOCATION_SERVICES -> CapabilityIssue.LOCATION_SERVICES_OFF
    AutoTrackingRequirement.NOTIFICATIONS -> CapabilityIssue.NOTIFICATIONS_DENIED
    AutoTrackingRequirement.ACTIVITY_RECOGNITION, AutoTrackingRequirement.BACKGROUND_LOCATION -> null
}

/** The short button label that fits at the end of a row (the full ones of `PERM-003` are for cards and dialogs). */
@StringRes
private fun CapabilityIssue.shortActionRes(): Int =
    if (this == CapabilityIssue.LOCATION_SERVICES_OFF) R.string.autotracking_action_turn_on else R.string.autotracking_action_allow

/**
 * SET-02 / `ux-navigation.md` §15: "traduce permisos/capacidades técnicos a estados comprensibles" - it explains the
 * capability the person gets, and lists what is missing for it, instead of a raw list of permissions as the main thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoTrackingScreen(onBack: () -> Unit, viewModel: AutoTrackingViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val fixIssue = rememberCapabilityFixer(onAfterAttempt = {})

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.autotracking_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                }
            )
        }
    ) { innerPadding ->
        val state = uiState
        if (state == null) return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                SettingsSection(stringResource(R.string.autotracking_title)) {
                    SettingsRow(
                        icon = Icons.Filled.TwoWheeler,
                        title = stringResource(R.string.autotracking_title),
                        subtitle = stringResource(if (state.enabled) R.string.autotracking_switch_on else R.string.autotracking_switch_off),
                        onClick = { viewModel.onToggle(!state.enabled) },
                        showDivider = false,
                        trailing = { Switch(checked = state.enabled, onCheckedChange = viewModel::onToggle) }
                    )
                }
            }
            item { StateCard(state.state) }
            item {
                RequirementsSection(
                    title = stringResource(R.string.autotracking_section_detection),
                    requirements = state.requirements.filter { !it.neededForHandsFree },
                    onFix = fixIssue
                )
            }
            item {
                RequirementsSection(
                    title = stringResource(R.string.autotracking_section_hands_free),
                    requirements = state.requirements.filter { it.neededForHandsFree },
                    onFix = fixIssue
                )
            }
            item {
                Text(
                    stringResource(R.string.autotracking_footnote),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun StateCard(state: AutoTrackingState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = when (state) {
                    AutoTrackingState.READY -> Icons.Filled.CheckCircle
                    AutoTrackingState.LOCATION_PROBLEM -> Icons.Filled.ErrorOutline
                    else -> Icons.Filled.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = when (state) {
                    AutoTrackingState.READY -> MaterialTheme.colorScheme.primary
                    AutoTrackingState.LOCATION_PROBLEM -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(state.titleRes()), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(state.textRes()), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun RequirementsSection(title: String, requirements: List<RequirementStatus>, onFix: (CapabilityIssue) -> Unit) {
    SettingsSection(title) {
        requirements.forEachIndexed { index, status ->
            val fix = status.requirement.fix()
            SettingsRow(
                icon = if (status.met) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                title = stringResource(status.requirement.titleRes()),
                subtitle = stringResource(status.requirement.textRes()),
                showDivider = index != requirements.lastIndex,
                trailing = if (status.met) {
                    { Text(stringResource(R.string.autotracking_status_allowed), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                } else if (fix != null) {
                    { TextButton(onClick = { onFix(fix) }) { Text(stringResource(fix.shortActionRes())) } }
                } else {
                    { Text(stringResource(R.string.autotracking_status_needed), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            )
        }
    }
}
