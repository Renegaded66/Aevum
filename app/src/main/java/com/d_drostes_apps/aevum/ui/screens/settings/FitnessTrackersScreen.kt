package com.d_drostes_apps.aevum.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.automation.garmin.GarminSyncScheduler
import com.d_drostes_apps.aevum.data.garmin.GarminApiClient
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * M18.59: Fitness-Tracker — eigene Einstellungs-Seite.
 *
 * Hier authentifiziert sich JEDER Nutzer mit SEINEN Garmin-Credentials
 * (die App wird später veröffentlicht — kein fest verdrahteter Account).
 * Ablauf:
 *   1. Email + Passwort eingeben → "Verbinden" prüft die Verbindung
 *      (Login läuft über die Aevum-Garmin-Bridge, Passwort wird dort
 *      nach dem Login verworfen und NIE auf dem Gerät gespeichert).
 *   2. Nach erfolgreicher Verbindung: Status-Karte mit "Jetzt
 *      synchronisieren" (manueller Sync) und "Trennen".
 *
 * Die Schlaf-Quelle "Garmin" in Trigger & Erkennung nutzt dieselbe
 * Verbindung — sie wird hier verwaltet, nicht dort.
 */
@HiltViewModel
class FitnessTrackersViewModel @Inject constructor(
    private val api: GarminApiClient,
    private val syncScheduler: GarminSyncScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(FitnessTrackersUiState())
    val uiState: StateFlow<FitnessTrackersUiState> = _uiState

    init {
        refreshStatus()
    }

    /** Prüft den Verbindungsstatus an der Bridge. */
    fun refreshStatus() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(checking = true, error = null)
            val status = api.getStatus()
            _uiState.value = _uiState.value.copy(
                connected = status.connected,
                checking = false,
                error = status.error
            )
        }
    }

    /** Garmin-Login mit den eingegebenen Credentials. */
    fun connect(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Bitte Email und Passwort eingeben")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(working = true, error = null)
            val error = api.connect(email, password)
            if (error == null) {
                _uiState.value = _uiState.value.copy(
                    working = false,
                    connected = true,
                    email = email,
                    password = "",
                    message = "Verbunden! Garmin-Daten werden jetzt synchronisiert."
                )
                // Direkt nach dem Verbinden einmal synchronisieren
                syncNow()
            } else {
                _uiState.value = _uiState.value.copy(
                    working = false,
                    connected = false,
                    error = error
                )
            }
        }
    }

    /** Garmin-Verbindung trennen (Tokens auf der Bridge löschen). */
    fun disconnect() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(working = true, error = null)
            api.disconnect()
            _uiState.value = _uiState.value.copy(
                working = false,
                connected = false,
                email = "",
                message = "Verbindung getrennt."
            )
        }
    }

    /** Manueller Sync — holt Schritte/Kalorien/Distanz + Schlaf + Aktivitäten. */
    fun syncNow() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(syncing = true, error = null)
            syncScheduler.syncNow()
            // Kurz warten, damit der Worker starten kann, dann Status aktualisieren
            kotlinx.coroutines.delay(1500)
            _uiState.value = _uiState.value.copy(
                syncing = false,
                lastSyncAt = api.lastSyncAt,
                message = "Synchronisierung gestartet."
            )
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

data class FitnessTrackersUiState(
    val connected: Boolean = false,
    val checking: Boolean = false,
    val working: Boolean = false,
    val syncing: Boolean = false,
    val email: String = "",
    val password: String = "",
    val lastSyncAt: Long = 0L,
    val message: String? = null,
    val error: String? = null
)

@Composable
fun FitnessTrackersScreen(
    onBack: () -> Unit,
    viewModel: FitnessTrackersViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var email by remember { mutableStateOf(state.email) }
    var password by remember { mutableStateOf("") }
    var showDisconnectDialog by remember { mutableStateOf(false) }

    // Nach erfolgreichem Verbinden: Passwort-Feld leeren
    LaunchedEffect(state.connected) {
        if (state.connected) password = ""
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
                Text("Fitness-Tracker", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Verbinde deine Sport-Apps — Daten fließen automatisch in die Timeline.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Garmin Connect Karte ──────────────────────────────────────
        AevumCard {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF1E3A8A), Color(0xFF3B82F6))
                                    ),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⌚", fontSize = 20.sp)
                        }
                        Spacer(Modifier.size(AevumSpacing.sm))
                        Column {
                            Text("Garmin Connect", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (state.connected) "Verbunden" else "Nicht verbunden",
                                fontSize = 12.sp,
                                color = if (state.connected) Color(0xFF34D399)
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (state.checking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }

                if (state.connected) {
                    // ── Verbunden: Status + Sync ──────────────────────
                    Text(
                        "Deine Garmin-Daten (Schritte, Kalorien, Distanz, Schlaf, Aktivitäten) werden automatisch synchronisiert.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (state.lastSyncAt > 0L) {
                                "Letzter Sync: ${
                                    SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN)
                                        .format(Date(state.lastSyncAt))
                                }"
                            } else "Noch nie synchronisiert",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { viewModel.syncNow() },
                            enabled = !state.syncing
                        ) {
                            if (state.syncing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Jetzt synchronisieren")
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { showDisconnectDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.working
                    ) {
                        Text("Verbindung trennen", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    // ── Nicht verbunden: Login-Formular ───────────────
                    Text(
                        "Melde dich mit deinem Garmin Connect Konto an. Dein Passwort wird nur zur Anmeldung verwendet und nicht gespeichert.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Garmin Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(AevumRadius.md)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Passwort") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(AevumRadius.md)
                    )
                    Button(
                        onClick = { viewModel.connect(email, password) },
                        enabled = !state.working && email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.working) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Verbinden & prüfen")
                        }
                    }
                    Text(
                        "Hinweis: Konten mit Zwei-Faktor-Authentifizierung (2FA) oder Social-Login (Google/Apple) können leider nicht verbunden werden.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Status-Meldungen
                state.error?.let { err ->
                    Text(
                        err,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = AevumSpacing.xs)
                    )
                }
                state.message?.let { msg ->
                    Text(
                        msg,
                        fontSize = 13.sp,
                        color = Color(0xFF34D399),
                        modifier = Modifier.padding(top = AevumSpacing.xs)
                    )
                }
            }
        }

        // ── Weitere Anbieter (Platzhalter, M18.59) ────────────────────
        AevumCard {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Text("Weitere Anbieter", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Weitere Fitness-Tracker folgen. Garmin Connect ist der erste Anbieter.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text("Verbindung trennen?") },
            text = { Text("Deine Garmin-Verbindung wird getrennt. Bereits importierte Daten bleiben in der Timeline erhalten.") },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectDialog = false
                    viewModel.disconnect()
                }) { Text("Trennen") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) { Text("Abbrechen") }
            }
        )
    }
}
