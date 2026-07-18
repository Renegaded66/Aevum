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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.devondroste.aevum.ui.components.AevumCard
import de.devondroste.aevum.ui.components.CardVariant
import de.devondroste.aevum.ui.theme.AevumSpacing

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier
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
            item { SettingsSection("Struktur", listOf("Kategorien verwalten", "Activity Types verwalten", "Tags verwalten")) }
            item { SettingsSection("Automatisierung", listOf("Geofences", "Trigger Events", "Zuhause", "Arbeit", "Activity Recognition")) }
            item { SettingsSection("Datenquellen", listOf("Schlaf", "Smartphone-Nutzung")) }
            item { SettingsSection("Datenschutz & Daten", listOf("Datenschutz", "Export", "Backup")) }
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
                "M5.5 bereitet die künftige Verwaltungsstruktur vor. Noch nicht alle Ziele sind aktiv, aber die Informationsarchitektur steht.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, entries: List<String>) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            entries.forEach { entry ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry, fontWeight = FontWeight.Medium)
                        Text("Geplant", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AssistChip(onClick = {}, label = { Text("Bald") })
                }
            }
        }
    }
}
