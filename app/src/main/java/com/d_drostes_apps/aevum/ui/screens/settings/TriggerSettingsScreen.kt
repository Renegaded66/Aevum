package com.d_drostes_apps.aevum.ui.screens.settings

import android.util.Log
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.d_drostes_apps.aevum.data.model.AutomationSettings
import com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * M18.44: Trigger-Settings — eine eigene Einstellungsseite für ALLE
 * automatischen Erkennungen. Der User entscheidet pro Trigger-Quelle,
 * ob sie aktiv ist:
 *
 *   🏠 Geofences          — Zuhause/Arbeit/Gym betreten & verlassen
 *   🚗 Autofahren          — Android Activity Recognition (IN_VEHICLE)
 *   🚶 Walking / Laufen    — Activity Recognition (WALKING/RUNNING, 5-Min-Regel)
 *   🚴 Radfahren           — Activity Recognition (ON_BICYCLE)
 *   🌙 Schlaf-Erkennung    — 3-Signal-Fusion (Screen + STILL + Bildschirmzeit)
 *
 * Die Toggles sind ECHTE Gates (nicht nur Anzeige): Deaktivierte Quellen
 * werden in den Receivern/Workern übersprungen (GeofenceBroadcastReceiver,
 * ActivityTransitionReceiver, SleepFusionWorker) und Geofences zusätzlich
 * beim System deregistriert.
 *
 * Zukunftssicher: Die AutomationSettings-Entity ist bewusst erweiterbar —
 * neue Trigger-Arten ergänzen einfach ein Feld + einen Eintrag hier.
 */
@Composable
fun TriggerSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: TriggerSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item { TriggerSettingsHero(onBack) }

            // ── Ortsbasiert ──────────────────────────────────────────
            item {
                TriggerGroup(
                    title = "Ortsbasiert",
                    items = listOf(
                        TriggerToggle(
                            icon = "📍",
                            title = "Geofences",
                            description = "Zuhause, Arbeit & Co. erkennen — Betreten startet, Verlassen stoppt die Aktivität",
                            accent = Color(0xFF4F9CF9),
                            checked = state.settings.geofencingEnabled,
                            onCheckedChange = viewModel::setGeofencing
                        )
                    )
                )
            }

            // ── Bewegung ─────────────────────────────────────────────
            item {
                TriggerGroup(
                    title = "Bewegung",
                    items = listOf(
                        TriggerToggle(
                            icon = "🚗",
                            title = "Autofahren",
                            description = "Automatisch starten, wenn Android eine Fahrt erkennt — stoppt beim Aussteigen",
                            accent = Color(0xFFF59E0B),
                            checked = state.settings.drivingDetectionEnabled,
                            onCheckedChange = viewModel::setDriving
                        ),
                        TriggerToggle(
                            icon = "🚶",
                            title = "Walking & Laufen",
                            description = "Trigger erst nach 5 Minuten am Stück (kein False-Trigger bei kurzen Wegen)",
                            accent = Color(0xFF10B981),
                            checked = state.settings.walkingDetectionEnabled,
                            onCheckedChange = viewModel::setWalking
                        ),
                        TriggerToggle(
                            icon = "🚴",
                            title = "Radfahren",
                            description = "Sofort-Trigger bei erkannten Fahrrad-Fahrten",
                            accent = Color(0xFF8B5CF6),
                            checked = state.settings.bicycleDetectionEnabled,
                            onCheckedChange = viewModel::setBicycle
                        )
                    )
                )
            }

            // ── Schlaf ───────────────────────────────────────────────
            item {
                TriggerGroup(
                    title = "Schlaf",
                    items = listOf(
                        TriggerToggle(
                            icon = "🌙",
                            title = "Schlaf-Erkennung",
                            description = "3-Signal-Fusion aus Bildschirm, STILL-Erkennung & Bildschirmzeit",
                            accent = Color(0xFF6366F1),
                            checked = state.settings.sleepFusionEnabled,
                            onCheckedChange = viewModel::setSleepFusion
                        )
                    )
                )
            }

            // ── Ausblick ─────────────────────────────────────────────
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                        Text("Mehr Trigger folgen", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Die Architektur ist offen für weitere Trigger-Arten (z.B. Smart-Home, Kopfhörer, App-Nutzung). " +
                                "Neue Quellen erscheinen automatisch hier, sobald sie verfügbar sind.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(AevumSpacing.xl)) }
        }
    }
}

@Composable
private fun TriggerSettingsHero(onBack: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text("Trigger & Erkennung", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Entscheide pro Quelle, was Aevum automatisch erkennen darf. " +
                    "Alles läuft lokal auf deinem Gerät.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun TriggerGroup(title: String, items: List<TriggerToggle>) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            items.forEach { toggle ->
                TriggerToggleRow(toggle)
            }
        }
    }
}

@Composable
private fun TriggerToggleRow(toggle: TriggerToggle) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
    ) {
        // Icon-Badge
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(toggle.accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(toggle.icon, fontSize = 18.sp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(toggle.title, fontWeight = FontWeight.SemiBold)
            Text(toggle.description, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
        }
        Switch(checked = toggle.checked, onCheckedChange = toggle.onCheckedChange)
    }
}

private data class TriggerToggle(
    val icon: String,
    val title: String,
    val description: String,
    val accent: Color,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit
)

/** M18.44: ViewModel für die Trigger-Settings-Seite. */
@HiltViewModel
class TriggerSettingsViewModel @Inject constructor(
    private val settingsRepository: AutomationSettingsRepository,
    private val geofenceRegistrar: com.d_drostes_apps.aevum.automation.geofence.GeofenceRegistrar
) : ViewModel() {

    val uiState: StateFlow<TriggerSettingsUiState> = settingsRepository.get()
        .map { settings -> TriggerSettingsUiState(settings ?: AutomationSettings()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TriggerSettingsUiState())

    fun setGeofencing(enabled: Boolean) {
        upsert { it.copy(geofencingEnabled = enabled) }
        // M18.44: Sofort wirksam — bei Deaktivierung werden alle Geofences
        // beim System deregistriert, bei Aktivierung neu registriert.
        viewModelScope.launch {
            try {
                geofenceRegistrar.refreshRegisteredGeofences()
            } catch (_: Exception) { /* Berechtigungen/Systemfehler — kein Crash */ }
        }
    }

    fun setDriving(enabled: Boolean) = upsert { it.copy(drivingDetectionEnabled = enabled) }
    fun setWalking(enabled: Boolean) = upsert { it.copy(walkingDetectionEnabled = enabled) }
    fun setBicycle(enabled: Boolean) = upsert { it.copy(bicycleDetectionEnabled = enabled) }
    fun setSleepFusion(enabled: Boolean) = upsert { it.copy(sleepFusionEnabled = enabled) }

    private fun upsert(transform: (AutomationSettings) -> AutomationSettings) {
        val current = uiState.value.settings
        viewModelScope.launch {
            try {
                settingsRepository.upsert(transform(current))
            } catch (e: Exception) {
                // M18.56: Fehler sichtbar machen statt schlucken — vorher
                // sprangen Toggles stillschweigend zurück, weil DB-Exceptions
                // von viewModelScope.launch verschluckt wurden.
                Log.e("TriggerSettings", "upsert fehlgeschlagen", e)
            }
        }
    }
}

data class TriggerSettingsUiState(
    val settings: AutomationSettings = AutomationSettings()
)
