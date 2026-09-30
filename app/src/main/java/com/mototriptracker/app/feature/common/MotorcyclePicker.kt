package com.mototriptracker.app.feature.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mototriptracker.app.core.database.entity.MotorcycleEntity

/**
 * MOTO-001/MAP-006/`ADR-024`: shared by Trip Detail's "assign motorcycle" row and Active Trip's "which motorcycle
 * are you riding" row - same picker shape, two different preferences underneath (a Trip's own assignment vs. the
 * live map's "currently selected" motorcycle).
 */
@Composable
internal fun MotorcycleRow(motorcycleName: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Motorcycle", style = MaterialTheme.typography.bodyMedium)
        Text(
            motorcycleName ?: "Assign motorcycle",
            style = MaterialTheme.typography.bodyMedium,
            color = if (motorcycleName == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** "None" always offered first so un-assigning is never harder than assigning. Archived motorcycles are deliberately not offered here (callers pass only `observeActive()`) - an already-assigned archived one still displays correctly via the caller's own state, it just isn't a *new* choice. */
@Composable
internal fun MotorcyclePickerDialog(
    motorcycles: List<MotorcycleEntity>,
    currentMotorcycleId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign motorcycle") },
        text = {
            Column {
                MotorcycleOptionRow("None", selected = currentMotorcycleId == null, onClick = { onSelect(null) })
                motorcycles.forEach { motorcycle ->
                    MotorcycleOptionRow(motorcycle.name, selected = motorcycle.id == currentMotorcycleId, onClick = { onSelect(motorcycle.id) })
                }
                if (motorcycles.isEmpty()) {
                    Text(
                        "No motorcycles yet - add one from Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun MotorcycleOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected")
    }
}
