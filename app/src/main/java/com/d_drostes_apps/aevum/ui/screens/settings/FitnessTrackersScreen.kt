package com.d_drostes_apps.aevum.ui.screens.settings

import android.app.Application
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.R
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
 * (kein fest verdrahteter Account). Ablauf:
 *   1. Email + Passwort eingeben → "Verbinden" prüft die Verbindung
 *      (Login läuft DIREKT gegen Garmin SSO, Passwort wird NIE auf
 *      dem Gerät gespeichert).
 *   2. Nach erfolgreicher Verbindung: Status-Karte mit "Jetzt
 *      synchronisieren" (manueller Sync) und "Trennen".
 *
 * Die Schlaf-Quelle "Garmin" in Trigger & Erkennung nutzt dieselbe
 * Verbindung — sie wird hier verwaltet, nicht dort.
 *
 * M18.87: Bridge-Ära beendet — die App spricht seit M18.66-FIX11
 * direkt mit Garmin (DirectGarminClient). Alle Bridge-/Tunnel-Reste
 * (URL-Override, X-Aevum-Key) wurden für die Play-Store-Veröffentlichung
 * entfernt.
 */
@HiltViewModel
class FitnessTrackersViewModel @Inject constructor(
    private val app: Application,
    private val api: GarminApiClient,
    private val syncScheduler: GarminSyncScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(FitnessTrackersUiState())
    val uiState: StateFlow<FitnessTrackersUiState> = _uiState

    init {
        refreshStatus()
    }

    /** Prüft den Verbindungsstatus bei Garmin. */
    fun refreshStatus() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(checking = true, error = null)
            val status = api.getStatus()
            _uiState.value = _uiState.value.copy(
                connected = status.connected,
                checking = false,
                error = status.error,
                // M18.59-FIX (User: "nach Seitenwechsel steht wieder 'Noch
                // nie synchronisiert'"): lastSyncAt ist in SharedPreferences
                // persistiert, wurde aber beim Status-Refresh nie geladen —
                // beim Neuerstellen des ViewModels stand es wieder auf 0.
                lastSyncAt = api.lastSyncAt
            )
        }
    }

    /** Garmin-Login mit den eingegebenen Credentials. */
    fun connect(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(error = app.getString(R.string.settings_fitness_enter_credentials))
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
                    message = app.getString(R.string.settings_fitness_connected_msg)
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

    /** Garmin-Verbindung trennen (lokale Tokens löschen). */
    fun disconnect() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(working = true, error = null)
            api.disconnect()
            _uiState.value = _uiState.value.copy(
                working = false,
                connected = false,
                email = "",
                message = app.getString(R.string.settings_fitness_disconnected_msg)
            )
        }
    }

    /** Manueller Sync — holt Schritte/Kalorien/Distanz + Schlaf + Aktivitäten. */
    fun syncNow() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(syncing = true, error = null)
            syncScheduler.syncNow()
            // M18.59-FIX (User: "Textfeld der letzten Synchronisation ändert
            // sich nicht, erst nach zurück+rauf"): Der Worker läuft asynchron
            // und braucht oft 10-30s (Garmin-Aufrufe). Vorher wurde nur 1,5s
            // gewartet — lastSyncAt war noch der alte Wert. Jetzt wird bis
            // zu 45s gepollt, bis der Worker den Zeitstempel geschrieben hat.
            val target = api.lastSyncAt
            var waited = 0
            while (waited < 45_000) {
                kotlinx.coroutines.delay(1_000)
                waited += 1_000
                val fresh = api.lastSyncAt
                if (fresh > target) {
                    _uiState.value = _uiState.value.copy(
                        syncing = false,
                        lastSyncAt = fresh,
                        message = app.getString(R.string.settings_fitness_sync_complete)
                    )
                    return@launch
                }
            }
            // Timeout — Status trotzdem aktualisieren (letzter bekannter Wert)
            _uiState.value = _uiState.value.copy(
                syncing = false,
                lastSyncAt = api.lastSyncAt,
                message = app.getString(R.string.settings_fitness_sync_started)
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
                Text(stringResource(R.string.settings_fitness_title), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.settings_fitness_subtitle),
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
                                if (state.connected) stringResource(R.string.settings_fitness_connected)
                                else stringResource(R.string.settings_fitness_not_connected),
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
                        stringResource(R.string.settings_fitness_sync_info),
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
                                stringResource(
                                    R.string.settings_fitness_last_sync,
                                    SimpleDateFormat("dd.MM. HH:mm", com.d_drostes_apps.aevum.util.AppLocale.current)
                                        .format(Date(state.lastSyncAt))
                                )
                            } else stringResource(R.string.settings_fitness_never_synced),
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
                                Text(stringResource(R.string.settings_fitness_sync_now))
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { showDisconnectDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.working
                    ) {
                        Text(stringResource(R.string.settings_fitness_disconnect), color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    // ── Nicht verbunden: Login-Formular ───────────────
                    Text(
                        stringResource(R.string.settings_fitness_login_info),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(R.string.settings_fitness_email_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(AevumRadius.md)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.settings_fitness_password_label)) },
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
                            Text(stringResource(R.string.settings_fitness_connect_check))
                        }
                    }
                    Text(
                        stringResource(R.string.settings_fitness_2fa_note),
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

        // M18.66-FIX12: Bridge-Server-Karte entfernt.
        // Die App spricht jetzt direkt mit Garmin Connect (DirectGarminClient) —
        // kein Bridge-Server, kein Tunnel, keine URL-Eingabe mehr nötig.

        // ── Weitere Anbieter (Platzhalter, M18.59) ────────────────────
        AevumCard {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Text(stringResource(R.string.settings_fitness_more_providers), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.settings_fitness_more_providers_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text(stringResource(R.string.settings_fitness_disconnect_title)) },
            text = { Text(stringResource(R.string.settings_fitness_disconnect_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectDialog = false
                    viewModel.disconnect()
                }) { Text(stringResource(R.string.settings_fitness_disconnect_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}
