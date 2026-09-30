package com.mototriptracker.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mototriptracker.app.core.database.entity.MotorcycleEntity
import com.mototriptracker.app.core.model.VehicleType
import com.mototriptracker.app.feature.common.formatDistanceKm

/** MOTO-001/`FR-MOTO-001/002/003`/`ADR-024`: list, add, edit and archive - archiving, never a hard delete, matches F0.7 §11's "no debe volver ilegible el historial". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotorcyclesScreen(onBack: () -> Unit, viewModel: MotorcyclesViewModel = hiltViewModel()) {
    val motorcycles by viewModel.motorcycles.collectAsStateWithLifecycle()
    val distanceById by viewModel.distanceMetersById.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MotorcycleEntity?>(null) }

    val (active, archived) = motorcycles.partition { !it.isArchived }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Motorcycles") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add motorcycle")
            }
        }
    ) { innerPadding ->
        if (motorcycles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No motorcycles yet. Add one to track distance per vehicle and show its icon on the map.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (active.isNotEmpty()) {
                    item { Text("Active", style = MaterialTheme.typography.titleSmall) }
                    items(active) { motorcycle ->
                        MotorcycleCard(
                            motorcycle = motorcycle,
                            distanceMeters = distanceById[motorcycle.id],
                            onClick = { editing = motorcycle },
                            onArchiveToggle = { viewModel.setArchived(motorcycle.id, true) },
                            archiveActionLabel = "Archive"
                        )
                    }
                }
                if (archived.isNotEmpty()) {
                    item { Text("Archived", style = MaterialTheme.typography.titleSmall) }
                    items(archived) { motorcycle ->
                        MotorcycleCard(
                            motorcycle = motorcycle,
                            distanceMeters = distanceById[motorcycle.id],
                            onClick = { editing = motorcycle },
                            onArchiveToggle = { viewModel.setArchived(motorcycle.id, false) },
                            archiveActionLabel = "Unarchive"
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        MotorcycleFormDialog(
            title = "Add motorcycle",
            initial = null,
            onConfirm = { name, make, model, year, vehicleType ->
                viewModel.addMotorcycle(name, make, model, year, vehicleType)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    editing?.let { motorcycle ->
        MotorcycleFormDialog(
            title = "Edit motorcycle",
            initial = motorcycle,
            onConfirm = { name, make, model, year, vehicleType ->
                viewModel.updateMotorcycle(motorcycle.id, name, make, model, year, vehicleType)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun MotorcycleCard(
    motorcycle: MotorcycleEntity,
    distanceMeters: Double?,
    onClick: () -> Unit,
    onArchiveToggle: () -> Unit,
    archiveActionLabel: String
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(vehicleTypeEmoji(motorcycle.vehicleType), style = MaterialTheme.typography.headlineSmall)
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(motorcycle.name, style = MaterialTheme.typography.titleMedium)
                val subtitle = listOfNotNull(motorcycle.make, motorcycle.model, motorcycle.year?.toString()).joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${formatDistanceKm(distanceMeters ?: 0.0)} total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onArchiveToggle) { Text(archiveActionLabel) }
        }
    }
}

@Composable
private fun MotorcycleFormDialog(
    title: String,
    initial: MotorcycleEntity?,
    onConfirm: (name: String, make: String?, model: String?, year: Int?, vehicleType: VehicleType) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var make by remember { mutableStateOf(initial?.make ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var year by remember { mutableStateOf(initial?.year?.toString() ?: "") }
    var vehicleType by remember { mutableStateOf(initial?.vehicleType ?: VehicleType.MOTORCYCLE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Name") })
                OutlinedTextField(value = make, onValueChange = { make = it }, singleLine = true, placeholder = { Text("Make (optional)") })
                OutlinedTextField(value = model, onValueChange = { model = it }, singleLine = true, placeholder = { Text("Model (optional)") })
                OutlinedTextField(
                    value = year,
                    onValueChange = { input -> year = input.filter { it.isDigit() }.take(4) },
                    singleLine = true,
                    placeholder = { Text("Year (optional)") }
                )
                VehicleTypePicker(selected = vehicleType, onSelected = { vehicleType = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, make.ifBlank { null }, model.ifBlank { null }, year.toIntOrNull(), vehicleType) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** MAP-006/`ADR-024`: shared by this dialog and Appearance's own "default vehicle icon" picker - a plain dropdown, not a full palette, since there are only four values. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VehicleTypePicker(selected: VehicleType, onSelected: (VehicleType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = "${vehicleTypeEmoji(selected)} ${vehicleTypeLabel(selected)}",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VehicleType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text("${vehicleTypeEmoji(type)} ${vehicleTypeLabel(type)}") },
                    onClick = {
                        onSelected(type)
                        expanded = false
                    }
                )
            }
        }
    }
}

internal fun vehicleTypeEmoji(vehicleType: VehicleType): String = when (vehicleType) {
    VehicleType.MOTORCYCLE -> "🏍️"
    VehicleType.CAR -> "🚗"
    VehicleType.TRUCK -> "🚚"
    VehicleType.BICYCLE -> "🚲"
    VehicleType.NONE -> "•"
}

internal fun vehicleTypeLabel(vehicleType: VehicleType): String = when (vehicleType) {
    VehicleType.MOTORCYCLE -> "Motorcycle"
    VehicleType.CAR -> "Car"
    VehicleType.TRUCK -> "Truck"
    VehicleType.BICYCLE -> "Bicycle"
    VehicleType.NONE -> "Dot"
}
