package com.mototriptracker.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mototriptracker.app.R
import com.mototriptracker.app.feature.common.boldMarked

/**
 * PERM-002 / `privacy-permissions.md` §8.3: said before the system asks for Activity Recognition, with a way out - a
 * "not now" simply leaves Auto Tracking waiting for it and manual trips untouched.
 */
@Composable
fun ActivityRecognitionExplanationDialog(onContinue: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text(stringResource(R.string.setup_activity_title)) },
        text = { Text(stringResource(R.string.setup_activity_text)) },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.setup_continue)) } },
        dismissButton = { TextButton(onClick = onNotNow) { Text(stringResource(R.string.setup_not_now)) } }
    )
}

/**
 * PERM-002 / `privacy-permissions.md` §7.3: the **prominent disclosure** that must come immediately before background
 * location is requested. It says "location" and "even when the app is closed or not in use" in bold, offers Continue and
 * Not now, states what the data is (and is not) used for, and looks nothing like the system's own dialog. From Android 11
 * there is no system dialog for this permission - the person is taken to the app's settings page - so, when
 * [showSettingsHint], it also says what to choose there.
 */
@Composable
fun BackgroundLocationDisclosureDialog(showSettingsHint: Boolean, onContinue: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text(stringResource(R.string.setup_background_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(boldMarked(stringResource(R.string.setup_background_text), SpanStyle(fontWeight = FontWeight.Bold)))
                if (showSettingsHint) {
                    Text(
                        stringResource(R.string.setup_background_settings_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.setup_continue)) } },
        dismissButton = { TextButton(onClick = onNotNow) { Text(stringResource(R.string.setup_not_now)) } }
    )
}
