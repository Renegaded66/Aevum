package com.d_drostes_apps.aevum.ui.screens.allowance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.DailyAllowance
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

/**
 * M18.29: DailyAllowancesScreen — Upgrade.
 *
 *  - Edit-Funktion (Stift-Icon öffnet denselben Dialog vorbefüllt)
 *  - ActivityType-Icon + Farbe in den Karten (vorher nur Text)
 *  - Fancy: farbiger Icon-Kreis, Minuten-Chip, Akzentbalken
 *  - ActivityType-Picker als horizontal scrollbare Chips mit Icon+Farbe
 */
@Composable
fun DailyAllowancesScreen(
    onBack: () -> Unit,
    viewModel: DailyAllowancesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingAllowance by remember { mutableStateOf<DailyAllowance?>(null) }

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
                            activityType = state.activityTypesById[allowance.activityTypeId],
                            onToggle = { viewModel.setEnabled(allowance.id, it) },
                            onEdit = { editingAllowance = allowance },
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

    // M18.29: Ein Dialog für Neu UND Edit (vorbefüllt)
    if (showAddDialog || editingAllowance != null) {
        val editing = editingAllowance
        AddAllowanceDialog(
            activityTypes = state.activityTypes,
            initial = editing,
            onConfirm = { name, activityTypeId, minutes ->
                if (editing != null) {
                    viewModel.update(editing.id, name, activityTypeId, minutes)
                } else {
                    viewModel.insert(name, activityTypeId, minutes)
                }
                showAddDialog = false
                editingAllowance = null
            },
            onCancel = {
                showAddDialog = false
                editingAllowance = null
            }
        )
    }
}

@Composable
private fun AllowanceRow(
    allowance: DailyAllowance,
    activityType: ActivityType?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    // M18.29: Aktivitätsfarbe + Icon für die Karte
    val accentColor = if (activityType?.color != null && activityType.color != 0L) {
        Color(activityType.color)
    } else {
        MaterialTheme.colorScheme.secondary
    }
    val icon = activityType?.icon?.takeIf { it.isNotBlank() } ?: "•"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier.padding(AevumSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            // Akzentbalken
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentColor)
            )
            // Icon in farbigem Kreis
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, fontSize = 20.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(allowance.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        activityType?.name ?: allowance.activityTypeId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    // Minuten-Chip
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(AevumRadius.full))
                            .background(accentColor.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${allowance.minutesPerDay} min/Tag",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = accentColor
                        )
                    }
                }
            }
            Switch(checked = allowance.enabled, onCheckedChange = onToggle)
            // M18.29: Edit-Button
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Bearbeiten",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Löschen",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AddAllowanceDialog(
    activityTypes: List<ActivityType>,
    initial: DailyAllowance?,
    onConfirm: (name: String, activityTypeId: String, minutes: Int) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var selectedTypeId by remember {
        mutableStateOf(
            initial?.activityTypeId
                ?: activityTypes.firstOrNull()?.id
                ?: ""
        )
    }
    var minutes by remember { mutableStateOf((initial?.minutesPerDay ?: 30).toFloat()) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (initial == null) "Neue Tagespauschale" else "Pauschale bearbeiten") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (z.B. Fertig machen)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(AevumSpacing.md))
                Text(
                    "Aktivität",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(AevumSpacing.sm))
                // M18.29: Fancy ActivityType-Picker mit Icon + Farbe
                LazyColumn(
                    modifier = Modifier.height(180.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(activityTypes, key = { it.id }) { type ->
                        val selected = type.id == selectedTypeId
                        val typeColor = if (type.color != null && type.color != 0L) Color(type.color)
                        else MaterialTheme.colorScheme.secondary
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) typeColor.copy(alpha = 0.22f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                                .clickable { selectedTypeId = type.id }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(typeColor.copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    type.icon?.takeIf { it.isNotBlank() } ?: "•",
                                    fontSize = 14.sp
                                )
                            }
                            Text(
                                type.name,
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) {
                                Text("✓", fontSize = 14.sp, color = typeColor, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(AevumSpacing.md))
                Text(
                    "Minuten pro Tag: ${minutes.toInt()}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
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
