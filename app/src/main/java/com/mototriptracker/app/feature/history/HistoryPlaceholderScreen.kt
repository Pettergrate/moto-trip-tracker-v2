package com.mototriptracker.app.feature.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** HIS-001 owns the real History list (search, filters, multi-select merge) - this placeholder just completes UI-001's navigation graph. */
@Composable
fun HistoryPlaceholderScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("History is coming soon", style = MaterialTheme.typography.bodyLarge)
    }
}
