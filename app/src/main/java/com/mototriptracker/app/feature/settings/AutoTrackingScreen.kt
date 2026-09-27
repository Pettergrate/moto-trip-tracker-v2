package com.mototriptracker.app.feature.settings

import androidx.annotation.StringRes
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mototriptracker.app.R
import com.mototriptracker.app.domain.capability.AutoTrackingRequirement
import com.mototriptracker.app.domain.capability.AutoTrackingSetup
import com.mototriptracker.app.domain.capability.AutoTrackingState
import com.mototriptracker.app.domain.capability.CapabilityIssue
import com.mototriptracker.app.domain.capability.RequirementStatus
import com.mototriptracker.app.domain.capability.SetupStep
import com.mototriptracker.app.feature.common.LocationExplanationDialog
import com.mototriptracker.app.feature.common.openAppSettings
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

/** PERM-002: the two that are asked for by the guided setup (with their own explanation first) rather than by a plain fix. */
internal fun AutoTrackingRequirement.usesGuidedSetup(): Boolean =
    this == AutoTrackingRequirement.ACTIVITY_RECOGNITION || this == AutoTrackingRequirement.BACKGROUND_LOCATION

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
    val context = LocalContext.current

    // PERM-002 / privacy-permissions.md 19.3: the guided setup. `step` is the one being shown; `attempted` is what the
    // person was already asked in this run (never asked twice, and a "no" ends what depends on it) - a plain set, not
    // state, because the system dialogs answer through callbacks that must see the latest value.
    var step by remember { mutableStateOf<SetupStep?>(null) }
    val attempted = remember { mutableSetOf<SetupStep>() }
    fun advance() = viewModel.nextSetupStep(attempted) { step = it }
    fun startSetup() {
        attempted.clear()
        advance()
    }
    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { advance() }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { advance() }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { advance() }
    // Android 10 still has a system dialog for background location; from 11 the person is taken to Settings.
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { advance() }
    // Turning it on starts the guided setup; turning it off asks nothing.
    fun onSwitch(on: Boolean) {
        viewModel.onToggle(on)
        if (on) startSetup()
    }
    val backgroundNeedsSettings = Build.VERSION.SDK_INT >= AutoTrackingSetup.FIRST_SDK_WITH_BACKGROUND_PERMISSION + 1

    // The notification prompt has no explanation of its own (the same decision as at the first Start, PERM-001): it asks the system at once.
    LaunchedEffect(step) {
        if (step == SetupStep.NOTIFICATIONS) {
            attempted += SetupStep.NOTIFICATIONS
            step = null
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    when (step) {
        SetupStep.ACTIVITY_RECOGNITION -> ActivityRecognitionExplanationDialog(
            onContinue = { attempted += SetupStep.ACTIVITY_RECOGNITION; step = null; activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
            onNotNow = { attempted += SetupStep.ACTIVITY_RECOGNITION; step = null; advance() }
        )
        SetupStep.PRECISE_LOCATION -> LocationExplanationDialog(
            onContinue = {
                attempted += SetupStep.PRECISE_LOCATION
                step = null
                locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            onNotNow = { attempted += SetupStep.PRECISE_LOCATION; step = null; advance() }
        )
        SetupStep.BACKGROUND_LOCATION -> BackgroundLocationDisclosureDialog(
            showSettingsHint = backgroundNeedsSettings,
            onContinue = {
                attempted += SetupStep.BACKGROUND_LOCATION
                step = null
                if (backgroundNeedsSettings) context.openAppSettings() else backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            },
            onNotNow = { attempted += SetupStep.BACKGROUND_LOCATION; step = null; advance() }
        )
        SetupStep.NOTIFICATIONS, null -> Unit
    }

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
                        onClick = { onSwitch(!state.enabled) },
                        showDivider = false,
                        trailing = { Switch(checked = state.enabled, onCheckedChange = ::onSwitch) }
                    )
                }
            }
            item {
                StateCard(
                    state = state.state,
                    // Waiting on a permission: the person can pick the guided setup up again from here, or from a row below.
                    onContinueSetup = if (state.enabled && (state.state == AutoTrackingState.NEEDS_SETUP || state.state == AutoTrackingState.LIMITED)) ::startSetup else null
                )
            }
            item {
                RequirementsSection(
                    title = stringResource(R.string.autotracking_section_detection),
                    requirements = state.requirements.filter { !it.neededForHandsFree },
                    onFix = fixIssue,
                    onSetUp = ::startSetup
                )
            }
            item {
                RequirementsSection(
                    title = stringResource(R.string.autotracking_section_hands_free),
                    requirements = state.requirements.filter { it.neededForHandsFree },
                    onFix = fixIssue,
                    onSetUp = ::startSetup
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
private fun StateCard(state: AutoTrackingState, onContinueSetup: (() -> Unit)?) {
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
                if (onContinueSetup != null) {
                    TextButton(onClick = onContinueSetup) { Text(stringResource(R.string.autotracking_continue_setup)) }
                }
            }
        }
    }
}

@Composable
private fun RequirementsSection(
    title: String,
    requirements: List<RequirementStatus>,
    onFix: (CapabilityIssue) -> Unit,
    onSetUp: () -> Unit
) {
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
                } else if (status.requirement.usesGuidedSetup()) {
                    { TextButton(onClick = onSetUp) { Text(stringResource(R.string.autotracking_action_set_up)) } }
                } else {
                    { Text(stringResource(R.string.autotracking_status_needed), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            )
        }
    }
}
