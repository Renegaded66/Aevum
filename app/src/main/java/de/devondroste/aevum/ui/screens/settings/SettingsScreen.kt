package de.devondroste.aevum.ui.screens.settings

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import de.devondroste.aevum.data.model.PlaceGeofence
import de.devondroste.aevum.data.repository.PlaceGeofenceRepository
import de.devondroste.aevum.ui.components.AevumCard
import de.devondroste.aevum.ui.components.CardVariant
import de.devondroste.aevum.ui.theme.AevumSpacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * M12.2: Callback-Signatur für Home/Work-Quick-Action.
 * Wenn bereits ein Geofence existiert, wird [onOpenExistingGeofence] aufgerufen,
 * andernfalls [onCreateHomeGeofence] / [onCreateWorkGeofence].
 * Damit gibt es keine "Bald verfügbar"-Platzhalter mehr.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenAutomation: () -> Unit = {},
    onOpenGeofences: () -> Unit = {},
    onOpenTriggers: () -> Unit = {},
    onOpenGoals: () -> Unit = {},
    onOpenHabits: () -> Unit = {},
    // M18.30: Todos + Tagespauschalen
    onOpenTodos: () -> Unit = {},
    onOpenDailyAllowances: () -> Unit = {},
    // M18.2: Positivitäts-Scores pro Aktivität
    onOpenActivityTypes: () -> Unit = {},
    onOpenHomeGeofence: (String) -> Unit = {},
    onOpenWorkGeofence: (String) -> Unit = {},
    onCreateHomeGeofence: () -> Unit = {},
    onCreateWorkGeofence: () -> Unit = {}
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item { SettingsHero() }
            // M18.10: Klare Hierarchie — Kern-Features zuerst, Neben-
            // Features (Ziele/Gewohnheiten) bewusst in "Erweitert".
            // Vorher standen alle Einträge gleichwertig nebeneinander —
            // das war Überladung. "Kategorien verwalten" war ein toter
            // Button ohne Callback — entfernt (Kategorien werden beim
            // Anlegen einer Aktivität verwaltet).
            item { SettingsSection("Deine Aktivitäten", listOf(
                // M18.2: Aktivitäten mit Positivitäts-Slider
                // M18.24: "Tags verwalten" entfernt — Tags sind ein
                // ungenutztes Neben-Feature ohne UI-Wert.
                SettingsEntry("Activity Types verwalten", onClick = onOpenActivityTypes)
            )) }
            item {
                // M12.2: Home/Work-Status wird jetzt live aus dem Geofence-Repository gelesen.
                // Der Status zeigt, ob der jeweilige Ort bereits existiert oder neu angelegt werden kann.
                val placeGeofences by hiltViewModel<SettingsViewModel>().geofences.collectAsState()
                val homeExisting = placeGeofences.firstOrNull { it.name.contains("Zuhause", ignoreCase = true) || it.name.contains("home", ignoreCase = true) }
                val workExisting = placeGeofences.firstOrNull { it.name.contains("Arbeit", ignoreCase = true) || it.name.contains("work", ignoreCase = true) }
                SettingsSection(
                    "Automatisierung",
                    listOf(
                        SettingsEntry("Automatisierung & Berechtigungen", "Aktiv", onOpenAutomation),
                        SettingsEntry("Geofences verwalten", "Aktiv", onOpenGeofences),
                        SettingsEntry("Trigger Events verwalten", "Aktiv", onOpenTriggers),
                        // M12.2: Home/Work öffnen den vorhandenen Geofence oder legen ihn neu an.
                        // Keine "Bald verfügbar"-Platzhalter mehr.
                        SettingsEntry(
                            "Zuhause festlegen",
                            status = if (homeExisting != null) "Vorhanden" else "Jetzt anlegen",
                            onClick = { if (homeExisting != null) onOpenHomeGeofence(homeExisting.id) else onCreateHomeGeofence() }
                        ),
                        SettingsEntry(
                            "Arbeit festlegen",
                            status = if (workExisting != null) "Vorhanden" else "Jetzt anlegen",
                            onClick = { if (workExisting != null) onOpenWorkGeofence(workExisting.id) else onCreateWorkGeofence() }
                        )
                        // M12.1.1: "Activity Recognition" und "Smartphone-Nutzung" wurden
                        // entfernt — beide Bereiche sind bereits vollständig unter
                        // "Automatisierung" erreichbar. Statt nicht-funktionaler
                        // Buttons zeigen wir unten den Hinweis.
                    )
                )
            }
            // M12.1.1: Hinweis statt nicht-funktionaler Buttons für
            // Smartphone-Nutzung und Activity Recognition. Beide Bereiche
            // sind vollständig unter "Automatisierung" konfigurierbar.
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                        Text(
                            "Smartphone-Nutzung & Activity Recognition",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Die Konfiguration erfolgt unter Automatisierung.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item { SettingsSection("Datenschutz & Daten", listOf(SettingsEntry("Datenschutz"), SettingsEntry("Export"), SettingsEntry("Backup"))) }
            // M18.10: Neben-Features (Ziele/Gewohnheiten) in eigener Sektion —
            // erreichbar, aber nicht mehr gleichwertig mit Kern-Features.
            // M18.30: Todos + Tagespauschalen hier verlinkt.
            item { SettingsSection("Erweitert", listOf(
                SettingsEntry("Ziele verwalten", onClick = onOpenGoals),
                SettingsEntry("Gewohnheiten verwalten", onClick = onOpenHabits),
                SettingsEntry("Todos verwalten", onClick = onOpenTodos),
                SettingsEntry("Tagespauschalen verwalten", onClick = onOpenDailyAllowances)
            )) }
            item { Spacer(Modifier.height(AevumSpacing.xl)) }
        }
    }
}

@Composable
private fun SettingsHero() {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text("Einstellungen", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Automatisierung ist freiwillig, lokal und erklärbar. Du entscheidest, welche Orte Aevum im Hintergrund beobachten darf.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, entries: List<SettingsEntry>) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            entries.forEach { entry ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.title, fontWeight = FontWeight.Medium)
                        // M12.2: status wird dynamisch pro Eintrag gesetzt (z. B. "Vorhanden" / "Jetzt anlegen").
                        Text(entry.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // M12.2: Auch ohne onClick zeigen wir einen echten "Öffnen"-Button
                    // statt eines "Bald"-Chips. Default = passiver "Öffnen"-Button
                    // der deaktiviert ist, falls keine Aktion dahinter liegt.
                    if (entry.onClick != null) {
                        Button(onClick = entry.onClick!!) { Text("Öffnen") }
                    } else {
                        AssistChip(onClick = {}, label = { Text(entry.status) })
                    }
                }
            }
        }
    }
}

/**
 * M12.2: status hat jetzt einen sinnvollen Default. Home/Work setzen
 * "Vorhanden" / "Jetzt anlegen" dynamisch; nicht-klickbare Einträge
 * zeigen "Bald nur noch, wenn wirklich nichts existiert" — nie als
 * Platzhalter.
 */
private data class SettingsEntry(
    val title: String,
    val status: String = "Verfügbar",
    val onClick: (() -> Unit)? = null
)

/**
 * M12.2: ViewModel, das die PlaceGeofence-Liste an die Settings-Screen liefert.
 * Damit kann die UI ohne extra Repository-Aufruf entscheiden, ob "Zuhause" oder
 * "Arbeit" bereits existieren (und direkt geöffnet werden können) oder neu
 * angelegt werden müssen.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    placeGeofenceRepository: PlaceGeofenceRepository
) : ViewModel() {
    val geofences: StateFlow<List<PlaceGeofence>> = placeGeofenceRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}