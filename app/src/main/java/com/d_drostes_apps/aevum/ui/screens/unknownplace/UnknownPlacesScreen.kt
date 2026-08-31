package com.d_drostes_apps.aevum.ui.screens.unknownplace

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.UnknownPlaceSession
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M17.2: Unknown Places Screen.
 *
 * Zeigt alle offenen (noch nicht aufgelösten) unbekannten Orte und
 * ermöglicht:
 *  - Namenszuweisung (Dialog mit TextField)
 *  - Geofence-Erstellung (Dialog mit TextField + Radius-Slider)
 *  - Verwerfen (Swipe-to-dismiss / Button)
 */
@Composable
fun UnknownPlacesScreen(
    onBack: () -> Unit,
    viewModel: UnknownPlacesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showNameDialog by remember { mutableStateOf<UnknownPlaceSession?>(null) }
    var showGeofenceDialog by remember { mutableStateOf<UnknownPlaceSession?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = AevumSpacing.lg)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = AevumSpacing.xl, bottom = AevumSpacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                }
                Spacer(Modifier.width(AevumSpacing.sm))
                Column {
                    Text(
                        stringResource(R.string.unknownplace_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.unknownplace_open_count, state.openEntries.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            if (state.openEntries.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.unknownplace_empty_title),
                    description = stringResource(R.string.unknownplace_empty_desc)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = AevumSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
                ) {
                    items(state.openEntries, key = { it.id }) { entry ->
                        UnknownPlaceCard(
                            entry = entry,
                            onName = { showNameDialog = entry },
                            onConvert = { showGeofenceDialog = entry },
                            onDismiss = { viewModel.dismiss(entry.id) }
                        )
                    }
                }
            }
        }
    }

    showNameDialog?.let { entry ->
        NameDialog(
            entry = entry,
            onConfirm = { name ->
                viewModel.assignName(entry.id, name)
                showNameDialog = null
            },
            onCancel = { showNameDialog = null }
        )
    }

    showGeofenceDialog?.let { entry ->
        GeofenceCreateDialog(
            entry = entry,
            onConfirm = { name, radius ->
                viewModel.convertToGeofence(entry.id, name, radius)
                showGeofenceDialog = null
            },
            onCancel = { showGeofenceDialog = null }
        )
    }
}

@Composable
private fun UnknownPlaceCard(
    entry: UnknownPlaceSession,
    onName: () -> Unit,
    onConvert: () -> Unit,
    onDismiss: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("dd.MM. HH:mm", com.d_drostes_apps.aevum.util.AppLocale.current) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AevumRadius.lg))
            .clickable { onName() },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(modifier = Modifier.padding(AevumSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(AevumSpacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entry.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${timeFormat.format(Date(entry.startAt))} · ${TimeFormatting.formatDuration(entry.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Spacer(Modifier.height(AevumSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Button(
                    onClick = onName,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(stringResource(R.string.unknownplace_name_button))
                }
                OutlinedButton(
                    onClick = onConvert,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.unknownplace_geofence_button))
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_discard))
                }
            }
        }
    }
}

@Composable
private fun NameDialog(
    entry: UnknownPlaceSession,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.unknownplace_name_dialog_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.unknownplace_name_hint)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) }
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun GeofenceCreateDialog(
    entry: UnknownPlaceSession,
    onConfirm: (String, Float) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(150f) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.unknownplace_geofence_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.common_name)) },
                    singleLine = true
                )
                Spacer(Modifier.height(AevumSpacing.md))
                Text(stringResource(R.string.unknownplace_radius, radius.toInt()))
                Slider(
                    value = radius,
                    onValueChange = { radius = it },
                    valueRange = 50f..500f,
                    steps = 8
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name, radius) }
            ) { Text(stringResource(R.string.common_create)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun EmptyState(title: String, description: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(AevumSpacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(AevumSpacing.md))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(AevumSpacing.sm))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}
