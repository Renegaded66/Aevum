package com.d_drostes_apps.aevum.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * AEVUM-3: Gemeinsamer Dialog zur manuellen Güte-Anpassung.
 *
 * Wird an zwei Stellen genutzt:
 *  - Timeline: Lang-Druck auf eine Session → Override für DIESE Aufzeichnung
 *  - Dashboard: Tipp auf die Güte-Zahl (QualityRing) → Override für den TAG
 *
 * Der Override (0..100) wird nur für die betroffenen Sessions gespeichert
 * (Spalte `activity_session.manual_quality_override`). Am nächsten Tag
 * existieren neue Sessions ohne Override → die automatische Berechnung
 * (Positivity-Score des ActivityTypes) gilt wieder. Der Dialog bietet
 * zusätzlich „Zurücksetzen" (null) an, um den Override sofort zu entfernen.
 *
 * @param title Dialog-Titel (z.B. „Güte anpassen").
 * @param message Erklärender Text (z.B. Session- oder Tages-Kontext).
 * @param initialScore Aktueller effektiver Score (0..100) als Startwert.
 * @param hasOverride Ob aktuell ein manueller Override aktiv ist.
 * @param onDismiss Abbruch — kein Schreiben.
 * @param onSave Neuer Score (0..100); null = Override entfernen.
 */
@Composable
fun QualityOverrideDialog(
    title: String,
    message: String,
    initialScore: Int,
    hasOverride: Boolean,
    onDismiss: () -> Unit,
    onSave: (Int?) -> Unit
) {
    var score by remember { mutableIntStateOf(initialScore.coerceIn(0, 100)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                PositivitySlider(
                    score = score,
                    onScoreChange = { score = it },
                    onValueChangeFinished = null
                )
                if (hasOverride) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Manuell angepasst — am nächsten Tag gilt wieder die automatische Güte.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Gilt nur für diesen Eintrag/Tag — die Aktivitäts-Einstellung bleibt unverändert.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(score) }) { Text("Übernehmen") }
        },
        dismissButton = {
            Column {
                if (hasOverride) {
                    TextButton(onClick = { onSave(null) }) { Text("Zurücksetzen") }
                }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        }
    )
}
