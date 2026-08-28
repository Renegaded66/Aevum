package com.d_drostes_apps.aevum.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// GEMEINSAME BAUSTEINE
// ---------------------------------------------------------------------------

@Composable
private fun DataScreenScaffold(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item {
                AevumCard(variant = CardVariant.Gradient) {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            subtitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
            item { content() }
            item { Spacer(Modifier.height(AevumSpacing.xl)) }
        }
    }
}

@Composable
private fun DataActionCard(
    title: String,
    description: String,
    actionLabel: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onAction: () -> Unit
) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(AevumSpacing.xs))
            Button(
                onClick = onAction,
                enabled = enabled,
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun DataStatusMessage(
    message: String?,
    isError: Boolean,
    isWorking: Boolean
) {
    if (isWorking) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.settings_working), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else if (message != null) {
        Text(
            message,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            lineHeight = 20.sp
        )
    }
}

/** Erzeugt einen Dateinamen mit Zeitstempel, z. B. aevum-export-2026-08-08-1430.json */
private fun timestampedFileName(prefix: String, extension: String): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
    return "$prefix-$stamp.$extension"
}

// ---------------------------------------------------------------------------
// 1. DATENSCHUTZ
// ---------------------------------------------------------------------------

@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    viewModel: DataSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showFinalDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Nach erfolgreichem Löschen: App neu starten
    LaunchedEffect(state.needsRestart) {
        if (state.needsRestart) {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(context.packageName)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Runtime.getRuntime().exit(0)
            }
        }
    }

    DataScreenScaffold(
        title = stringResource(R.string.settings_privacy_title),
        subtitle = stringResource(R.string.settings_privacy_subtitle)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            AevumCard {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    Text(stringResource(R.string.settings_privacy_local_processing), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.settings_privacy_local_processing_body),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                }
            }
            AevumCard {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    Text(stringResource(R.string.settings_privacy_your_control), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.settings_privacy_your_control_body),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                }
            }
            DataActionCard(
                title = stringResource(R.string.settings_privacy_delete_all),
                description = stringResource(R.string.settings_privacy_delete_all_desc),
                actionLabel = stringResource(R.string.settings_privacy_delete_all),
                destructive = true,
                onAction = { showDeleteDialog = true }
            )
            DataStatusMessage(state.message, state.isError, state.isWorking)
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.settings_privacy_delete_confirm_title)) },
            text = { Text(stringResource(R.string.settings_privacy_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    showFinalDialog = true
                }) { Text(stringResource(R.string.common_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
    if (showFinalDialog) {
        AlertDialog(
            onDismissRequest = { showFinalDialog = false },
            title = { Text(stringResource(R.string.settings_privacy_final_warning_title)) },
            text = { Text(stringResource(R.string.settings_privacy_final_warning_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showFinalDialog = false
                    viewModel.deleteAllData()
                }) { Text(stringResource(R.string.settings_privacy_delete_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showFinalDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// 2. EXPORT
// ---------------------------------------------------------------------------

@Composable
fun ExportScreen(
    onBack: () -> Unit,
    viewModel: DataSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) viewModel.exportJson(uri)
    }

    DataScreenScaffold(
        title = stringResource(R.string.common_export),
        subtitle = stringResource(R.string.settings_export_subtitle)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            DataActionCard(
                title = stringResource(R.string.settings_export_all_json),
                description = stringResource(R.string.settings_export_all_json_desc),
                actionLabel = stringResource(R.string.settings_export_start),
                enabled = !state.isWorking,
                onAction = {
                    viewModel.clearMessage()
                    exportLauncher.launch(timestampedFileName("aevum-export", "json"))
                }
            )
            DataStatusMessage(state.message, state.isError, state.isWorking)
        }
    }
}

// ---------------------------------------------------------------------------
// 3. BACKUP
// ---------------------------------------------------------------------------

@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: DataSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Nach erfolgreichem Restore: App neu starten. Kritisch, weil Room die
    // ersetzte DB-Datei offen hält und DataStore/ViewModel-Caches veraltet
    // sind — ohne Neustart würden alte Daten angezeigt bzw. der nächste
    // DB-Zugriff könnte die wiederhergestellte Datei korrumpieren.
    LaunchedEffect(state.needsRestart) {
        if (state.needsRestart) {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(context.packageName)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Runtime.getRuntime().exit(0)
            }
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) viewModel.createBackup(uri)
    }

    // M18.73: Bestätigungs-Popup VOR dem Restore. Erst ZIP auswählen,
    // dann warnen (alle Daten werden unwiderruflich ersetzt), dann erst
    // einspielen. Verhindert versehentliches Überschreiben.
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) pendingRestoreUri = uri
    }

    DataScreenScaffold(
        title = stringResource(R.string.common_backup),
        subtitle = stringResource(R.string.settings_backup_subtitle)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            DataActionCard(
                title = stringResource(R.string.settings_backup_create),
                description = stringResource(R.string.settings_backup_create_desc),
                actionLabel = stringResource(R.string.settings_backup_create),
                enabled = !state.isWorking,
                onAction = {
                    viewModel.clearMessage()
                    backupLauncher.launch(timestampedFileName("aevum-backup", "zip"))
                }
            )
            DataActionCard(
                title = stringResource(R.string.settings_backup_restore),
                description = stringResource(R.string.settings_backup_restore_desc),
                actionLabel = stringResource(R.string.settings_backup_choose),
                enabled = !state.isWorking,
                onAction = {
                    viewModel.clearMessage()
                    restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                }
            )
            DataStatusMessage(state.message, state.isError, state.isWorking)
        }
    }

    // M18.73: Warn-Dialog vor dem Einspielen — alle aktuellen Daten werden
    // unwiderruflich durch den Backup-Inhalt ersetzt.
    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text(stringResource(R.string.settings_backup_confirm_title)) },
            text = { Text(stringResource(R.string.settings_backup_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestoreUri = null
                    viewModel.restoreBackup(uri)
                }) { Text(stringResource(R.string.settings_backup_confirm_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}
