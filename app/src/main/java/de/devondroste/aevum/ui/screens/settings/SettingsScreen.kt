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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import de.devondroste.aevum.ui.components.AevumCard
import de.devondroste.aevum.ui.components.CardVariant
import de.devondroste.aevum.ui.theme.AevumSpacing

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenAutomation: () -> Unit = {},
    onOpenGeofences: () -> Unit = {},
    onOpenTriggers: () -> Unit = {}
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
            item { SettingsSection("Struktur", listOf(SettingsEntry("Kategorien verwalten"), SettingsEntry("Activity Types verwalten"), SettingsEntry("Tags verwalten"))) }
            item {
                SettingsSection(
                    "Automatisierung",
                    listOf(
                        SettingsEntry("Automatisierung & Berechtigungen", "Aktiv", onOpenAutomation),
                        SettingsEntry("Geofences verwalten", "Aktiv", onOpenGeofences),
                        SettingsEntry("Trigger Events verwalten", "Aktiv", onOpenTriggers),
                        SettingsEntry("Zuhause festlegen"),
                        SettingsEntry("Arbeit festlegen"),
                        SettingsEntry("Activity Recognition")
                    )
                )
            }
            item { SettingsSection("Datenquellen", listOf(SettingsEntry("Schlaf"), SettingsEntry("Smartphone-Nutzung"))) }
            item { SettingsSection("Datenschutz & Daten", listOf(SettingsEntry("Datenschutz"), SettingsEntry("Export"), SettingsEntry("Backup"))) }
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
                        Text(entry.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (entry.onClick != null) Button(onClick = entry.onClick) { Text("Öffnen") } else AssistChip(onClick = {}, label = { Text("Bald") })
                }
            }
        }
    }
}

private data class SettingsEntry(
    val title: String,
    val status: String = "Geplant",
    val onClick: (() -> Unit)? = null
)
