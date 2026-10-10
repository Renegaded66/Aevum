package com.d_drostes_apps.aevum.ui.screens.pctracking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.text.SimpleDateFormat
import java.util.Date

/**
 * PC-Tracker — Kopplung mit dem Windows-PC im Heimnetz.
 *
 * Gleiches WLAN vorausgesetzt: Der PC wird per Broadcast gefunden (oder die
 * Adresse manuell eingegeben), die Kopplung läuft über den Kurzcode am PC,
 * danach holt die App die Aufzeichnungen per Pull-Sync ab. Kein Konto,
 * keine Cloud — alles bleibt im Heimnetz und auf dem Gerät.
 */
@Composable
fun PcTrackingScreen(
    onBack: () -> Unit,
    viewModel: PcTrackingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showDisconnectDialog by remember { mutableStateOf(false) }

    // Beim Öffnen automatisch nach PCs im WLAN suchen (wenn nicht gekoppelt)
    LaunchedEffect(state.connected) {
        if (state.connected == null && !state.discoveryDone && !state.discovering) {
            viewModel.search()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("←") }
            Column {
                Text(stringResource(R.string.pc_tracker_title), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.pc_tracker_subtitle),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Statusmeldungen (grün = Erfolg, rot = Fehler)
        state.message?.let { message ->
            AevumCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(message, fontSize = 13.sp, color = Color(0xFF34D399), modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.clearMessages() }) { Text("✕") }
                }
            }
        }
        state.error?.let { error ->
            AevumCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(error, fontSize = 13.sp, color = Color(0xFFF87171), modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.clearMessages() }) { Text("✕") }
                }
            }
        }

        val connection = state.connected
        if (connection != null) {
            ConnectedCard(
                connection = connection,
                syncing = state.syncing,
                onSync = { viewModel.syncNow() },
                onDisconnect = { showDisconnectDialog = true }
            )
        } else {
            SearchCard(state = state, viewModel = viewModel)
            ManualCard(state = state, viewModel = viewModel)
            state.target?.let { target ->
                PairCodeCard(target = target, state = state, viewModel = viewModel)
            }
        }

        Spacer(Modifier.size(AevumSpacing.xxl))
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text(stringResource(R.string.pc_tracker_disconnect_confirm_title)) },
            text = { Text(stringResource(R.string.pc_tracker_disconnect_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectDialog = false
                    viewModel.disconnect()
                }) { Text(stringResource(R.string.pc_tracker_disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text(stringResource(R.string.pc_tracker_cancel))
                }
            }
        )
    }
}

// ── Verbunden: Status + Sync ────────────────────────────────────────────

@Composable
private fun ConnectedCard(
    connection: com.d_drostes_apps.aevum.data.pc.PcTrackerPrefs.StoredConnection,
    syncing: Boolean,
    onSync: () -> Unit,
    onDisconnect: () -> Unit
) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF4C1D95), Color(0xFFA99CFF))),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🖥", fontSize = 20.sp)
                }
                Spacer(Modifier.size(AevumSpacing.sm))
                Column {
                    Text(
                        connection.deviceName.ifBlank { connection.host },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.pc_tracker_connected),
                        fontSize = 12.sp,
                        color = Color(0xFF34D399)
                    )
                }
            }

            Text(
                stringResource(R.string.pc_tracker_sync_info),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (connection.lastSyncAt > 0L) {
                        stringResource(
                            R.string.pc_tracker_last_sync,
                            SimpleDateFormat("dd.MM. HH:mm", com.d_drostes_apps.aevum.util.AppLocale.current)
                                .format(Date(connection.lastSyncAt))
                        )
                    } else {
                        stringResource(R.string.pc_tracker_never_synced)
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onSync, enabled = !syncing) {
                    if (syncing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.pc_tracker_sync_now))
                    }
                }
            }

            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.pc_tracker_disconnect))
            }
        }
    }
}

// ── Suche im WLAN ───────────────────────────────────────────────────────

@Composable
private fun SearchCard(state: PcTrackingUiState, viewModel: PcTrackingViewModel) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Text(
                stringResource(R.string.pc_tracker_search),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )

            if (state.discovering) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(AevumSpacing.sm))
                    Text(
                        stringResource(R.string.pc_tracker_searching),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                state.devices.forEach { pc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { viewModel.chooseDevice(pc) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    Brush.linearGradient(listOf(Color(0xFF334155), Color(0xFF64748B))),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🖥", fontSize = 16.sp)
                        }
                        Spacer(Modifier.size(AevumSpacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pc.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "${pc.host}:${pc.port}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            if (pc.pairingActive) stringResource(R.string.pc_tracker_pairing_active)
                            else stringResource(R.string.pc_tracker_pairing_inactive),
                            fontSize = 11.sp,
                            color = if (pc.pairingActive) Color(0xFF34D399)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (state.discoveryDone && state.devices.isEmpty()) {
                    Text(
                        stringResource(R.string.pc_tracker_no_devices),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!state.discovering && state.devices.isEmpty() && state.discoveryDone) {
                    TextButton(onClick = { viewModel.search() }) {
                        Text(stringResource(R.string.pc_tracker_rescan))
                    }
                }
            }
        }
    }
}

// ── Manuell verbinden (Zweitweg) ────────────────────────────────────────

@Composable
private fun ManualCard(state: PcTrackingUiState, viewModel: PcTrackingViewModel) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            if (!state.manualMode) {
                TextButton(
                    onClick = { viewModel.openManual() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.pc_tracker_manual))
                }
            } else {
                Text(
                    stringResource(R.string.pc_tracker_manual),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.pc_tracker_manual_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = state.manualHost,
                    onValueChange = { viewModel.setManualHost(it) },
                    label = { Text(stringResource(R.string.pc_tracker_host)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.manualPort,
                    onValueChange = { viewModel.setManualPort(it) },
                    label = { Text(stringResource(R.string.pc_tracker_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    Button(
                        onClick = { viewModel.proceedManual() },
                        enabled = state.manualHost.isNotBlank()
                    ) {
                        Text(stringResource(R.string.pc_tracker_next))
                    }
                    TextButton(onClick = { viewModel.backToOverview() }) {
                        Text(stringResource(R.string.pc_tracker_cancel))
                    }
                }
            }
        }
    }
}

// ── Kopplungs-Code ──────────────────────────────────────────────────────

@Composable
private fun PairCodeCard(
    target: PcTarget,
    state: PcTrackingUiState,
    viewModel: PcTrackingViewModel
) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Text(
                stringResource(R.string.pc_tracker_code_title, target.name),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.pc_tracker_code_info),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = state.code,
                onValueChange = { viewModel.setCode(it) },
                label = { Text(stringResource(R.string.pc_tracker_code)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Button(
                    onClick = { viewModel.connect() },
                    enabled = state.code.isNotBlank() && !state.pairing
                ) {
                    if (state.pairing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.pc_tracker_connect))
                    }
                }
                TextButton(onClick = { viewModel.cancelPairing() }) {
                    Text(stringResource(R.string.pc_tracker_cancel))
                }
            }
        }
    }
}
