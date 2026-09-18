package com.mototriptracker.app.feature.favorites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** FAV-001 owns the real Favorites list - this placeholder just completes UI-001's navigation graph. */
@Composable
fun FavoritesPlaceholderScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Favorites are coming soon", style = MaterialTheme.typography.bodyLarge)
    }
}
