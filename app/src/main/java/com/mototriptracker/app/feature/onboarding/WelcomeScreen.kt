package com.mototriptracker.app.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mototriptracker.app.R

/**
 * PERM-001 / ONB-01 (`ux-navigation.md` §16): one screen that says what the app records, that it works by hand
 * whether or not Auto Tracking is ever enabled, and that the data is local. It asks for **no** permission - those
 * come later, in context, at the first Start (`privacy-permissions.md` §19.1-19.2).
 */
@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Scrolls, so a large font size or a small screen never pushes the button off the screen.
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium)
                WelcomePoint(stringResource(R.string.welcome_records_title), stringResource(R.string.welcome_records_text))
                WelcomePoint(stringResource(R.string.welcome_control_title), stringResource(R.string.welcome_control_text))
                WelcomePoint(stringResource(R.string.welcome_local_title), stringResource(R.string.welcome_local_text))
            }
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.welcome_continue))
            }
        }
    }
}

@Composable
private fun WelcomePoint(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
