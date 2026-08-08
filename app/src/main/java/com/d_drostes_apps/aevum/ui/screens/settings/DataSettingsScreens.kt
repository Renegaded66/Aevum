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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
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
            Text("Arbeite …", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        title = "Datenschutz",
        subtitle = "Aevum arbeitet komplett lokal. Deine Daten verlassen niemals dein Gerät — es gibt keine Server, keine Konten und keine Analyse durch Dritte."
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            AevumCard {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    Text("Lokale Datenverarbeitung", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "• Alle Aufzeichnungen, Trigger und Einstellungen liegen nur in der App-Datenbank auf deinem Gerät.\n" +
                            "• Es werden keine Daten an Server übertragen, keine Werbe-IDs verwendet und keine Tracking-SDKs eingebunden.\n" +
                            "• Standort- und Aktivitätsdaten werden ausschließlich für die automatische Erkennung genutzt.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                }
            }
            AevumCard {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    Text("Deine Kontrolle", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "• Automatische Erkennung ist freiwillig — jede Quelle lässt sich in den Einstellungen abschalten.\n" +
                            "• Du kannst jederzeit ein Backup erstellen, Daten exportieren oder alles löschen.\n" +
                            "• Berechtigungen (Standort, Aktivität, Benachrichtigungen) kannst du jederzeit in den Systemeinstellungen entziehen.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                }
            }
            DataActionCard(
                title = "Alle Daten löschen",
                description = "Entfernt dauerhaft alle Aufzeichnungen, Trigger, Ziele und Einstellungen von diesem Gerät. Die App startet danach neu. Dieser Schritt kann nicht rückgängig gemacht werden — erstelle vorher ein Backup.",
                actionLabel = "Alle Daten löschen",
                destructive = true,
                onAction = { showDeleteDialog = true }
            )
            DataStatusMessage(state.message, state.isError, state.isWorking)
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Wirklich alles löschen?") },
            text = { Text("Alle deine Aufzeichnungen und Einstellungen werden unwiderruflich gelöscht. Möchtest du fortfahren?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    showFinalDialog = true
                }) { Text("Weiter") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Abbrechen") }
            }
        )
    }
    if (showFinalDialog) {
        AlertDialog(
            onDismissRequest = { showFinalDialog = false },
            title = { Text("Letzte Warnung") },
            text = { Text("Dies ist endgültig. Es gibt keine Möglichkeit, die Daten wiederherzustellen. Wirklich löschen?") },
            confirmButton = {
                TextButton(onClick = {
                    showFinalDialog = false
                    viewModel.deleteAllData()
                }) { Text("Ja, alles löschen") }
            },
            dismissButton = {
                TextButton(onClick = { showFinalDialog = false }) { Text("Abbrechen") }
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
        title = "Export",
        subtitle = "Exportiere alle deine Daten als JSON-Datei. Du kannst sie in jeder App öffnen, die JSON unterstützt — perfekt für die Archivierung oder die Übergabe an andere Tools."
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            DataActionCard(
                title = "Alle Daten als JSON exportieren",
                description = "Erstellt eine vollständige Kopie aller Tabellen (Aktivitäten, Trigger, Ziele, Gewohnheiten, Todos, …) als eine JSON-Datei. Der Export enthält keine Passwörter oder Zugangsdaten.",
                actionLabel = "Export starten",
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

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) viewModel.createBackup(uri)
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) viewModel.restoreBackup(uri)
    }

    DataScreenScaffold(
        title = "Backup",
        subtitle = "Sichere deine komplette Datenbank als ZIP-Datei — z. B. auf Google Drive, in deine Cloud oder auf deinen Computer. Mit dem Backup kannst du Aevum jederzeit vollständig wiederherstellen."
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            DataActionCard(
                title = "Backup erstellen",
                description = "Erstellt eine ZIP-Datei mit deiner gesamten Datenbank. Empfohlen: regelmäßig ein Backup anlegen, z. B. vor Updates oder auf Reisen.",
                actionLabel = "Backup erstellen",
                enabled = !state.isWorking,
                onAction = {
                    viewModel.clearMessage()
                    backupLauncher.launch(timestampedFileName("aevum-backup", "zip"))
                }
            )
            DataActionCard(
                title = "Backup wiederherstellen",
                description = "Wählt eine zuvor erstellte ZIP-Datei aus und stellt alle Daten wieder her. Die App startet danach automatisch neu. Die aktuelle Datenbank wird vorher als Sicherung abgelegt.",
                actionLabel = "Backup auswählen",
                enabled = !state.isWorking,
                onAction = {
                    viewModel.clearMessage()
                    restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                }
            )
            DataStatusMessage(state.message, state.isError, state.isWorking)
        }
    }
}
