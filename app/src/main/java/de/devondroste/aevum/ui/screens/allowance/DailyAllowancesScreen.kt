package de.devondroste.aevum.ui.screens.allowance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.model.DailyAllowance
import de.devondroste.aevum.ui.theme.AevumRadius
import de.devondroste.aevum.ui.theme.AevumSpacing

/**
 * M17.3: DailyAllowancesScreen.
 *
 * Liste der konfigurierten Tagespauschalen. Jede Pauschale hat:
 *  - Name (z.B. "Fertig machen")
 *  - ActivityType (z.B. "leisure")
 *  - Minuten pro Tag (z.B. 30)
 *  - enabled-Flag
 *
 * CRUD via FAB. Erscheint in den AppSettings, nicht auf dem Dashboard.
 */
@Composable
fun DailyAllowancesScreen(
    onBack: () -> Unit,
    viewModel: DailyAllowancesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = AevumSpacing.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = AevumSpacing.xl, bottom = AevumSpacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Default.Close, contentDescription = "Schließen") }
                Spacer(Modifier.width(AevumSpacing.sm))
                Column {
                    Text(
                        "Tagespauschalen",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Erscheinen in der Statistik, nicht in der Timeline",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            if (state.allowances.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = AevumSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
                ) {
                    items(state.allowances, key = { it.id }) { allowance ->
                        AllowanceRow(
                            allowance = allowance,
                            activityTypeName = state.activityTypesById[allowance.activityTypeId]?.name ?: allowance.activityTypeId,
                            onToggle = { viewModel.setEnabled(allowance.id, it) },
                            onDelete = { viewModel.delete(allowance.id) }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(AevumSpacing.lg)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Neue Pauschale")
        }
    }

    if (showAddDialog) {
        AddAllowanceDialog(
            activityTypes = state.activityTypes,
            onConfirm = { name, activityTypeId, minutes ->
                viewModel.insert(name, activityTypeId, minutes)
                showAddDialog = false
            },
            onCancel = { showAddDialog = false }
        )
    }
}

@Composable
private fun AllowanceRow(
    allowance: DailyAllowance,
    activityTypeName: String,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AevumRadius.lg)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier.padding(AevumSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(allowance.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "$activityTypeName · ${allowance.minutesPerDay} min / Tag",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Switch(checked = allowance.enabled, onCheckedChange = onToggle)
            Spacer(Modifier.width(AevumSpacing.sm))
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Löschen")
            }
        }
    }
}

@Composable
private fun AddAllowanceDialog(
    activityTypes: List<ActivityType>,
    onConfirm: (name: String, activityTypeId: String, minutes: Int) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedTypeId by remember { mutableStateOf(activityTypes.firstOrNull()?.id ?: "") }
    var minutes by remember { mutableStateOf(30f) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Neue Tagespauschale") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (z.B. Fertig machen)") },
                    singleLine = true
                )
                Spacer(Modifier.height(AevumSpacing.md))
                Text("Aktivität: ${activityTypes.firstOrNull { it.id == selectedTypeId }?.name ?: "—"}")
                Spacer(Modifier.height(AevumSpacing.sm))
                activityTypes.forEach { type ->
                    TextButton(
                        onClick = { selectedTypeId = type.id },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            type.name + if (type.id == selectedTypeId) " ✓" else "",
                            fontWeight = if (type.id == selectedTypeId) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
                Spacer(Modifier.height(AevumSpacing.md))
                Text("Minuten pro Tag: ${minutes.toInt()}")
                Slider(
                    value = minutes,
                    onValueChange = { minutes = it },
                    valueRange = 5f..240f,
                    steps = 0
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && selectedTypeId.isNotEmpty()) {
                        onConfirm(name, selectedTypeId, minutes.toInt())
                    }
                }
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Abbrechen") } }
    )
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize().padding(AevumSpacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Noch keine Tagespauschalen",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(AevumSpacing.sm))
            Text(
                "Tippe auf +, um eine zu erstellen — z.B. „Fertig machen 30 min/Tag, die in die Statistik einfließen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}
