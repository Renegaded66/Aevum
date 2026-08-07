package de.devondroste.aevum.ui.screens.activitytypes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.devondroste.aevum.ui.components.GlassCard
import de.devondroste.aevum.ui.components.PositivitySlider
import de.devondroste.aevum.ui.components.positivityColor
import de.devondroste.aevum.ui.theme.AevumSpacing

/**
 * M18.2: Aktivitäten & Positivität — zentrale Stelle, wo der User
 * jeder Activity einen Positivitäts-Score (0-100) zuweist.
 *
 * M18.12: Erweitert um:
 *  - Icon-Picker (Emoji-Grid) pro Aktivität
 *  - Farb-Picker (Palette) pro Aktivität
 *  - "Neue Aktivität" manuell anlegen
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityTypesScreen(
    onBack: () -> Unit,
    viewModel: ActivityTypesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AevumSpacing.sm, vertical = AevumSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Zurück")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Aktivitäten & Positivität",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Was ist dir deine Zeit wert?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // M18.12: Neue Aktivität anlegen
                OutlinedButton(onClick = { showCreateDialog = true }) {
                    Text("+ Neu", fontSize = 12.sp)
                }
            }

            if (state.activityTypes.isEmpty()) {
                Spacer(Modifier.height(AevumSpacing.xl))
                Text(
                    "Noch keine Aktivitäten.",
                    modifier = Modifier.padding(AevumSpacing.lg),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = AevumSpacing.lg,
                        vertical = AevumSpacing.sm
                    ),
                    verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
                ) {
                    items(state.activityTypes, key = { it.id }) { row ->
                        ActivityTypeCard(
                            row = row,
                            onScoreChange = { viewModel.onScoreDragged(row.id, it) },
                            onScoreCommit = { viewModel.commitScore(row.id) },
                            onIconChange = { viewModel.setIcon(row.id, it) },
                            onColorChange = { viewModel.setColor(row.id, it) }
                        )
                    }
                    item { Spacer(Modifier.height(AevumSpacing.xl)) }
                }
            }
        }
    }

    // M18.12: Dialog für neue Aktivität
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false; newName = "" },
            title = { Text("Neue Aktivität", fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name (z. B. Gitarre)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createActivity(newName)
                        showCreateDialog = false
                        newName = ""
                    },
                    enabled = newName.trim().isNotEmpty()
                ) { Text("Anlegen") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false; newName = "" }) { Text("Abbrechen") }
            }
        )
    }
}

/**
 * M18.12: Eine Aktivitäts-Karte mit:
 *  - Icon (Emoji) in farbigem Kreis — Tippen öffnet Icon-Picker
 *  - Name + Score
 *  - PositivitySlider
 *  - Farb-Palette (8 Farben) — Tippen setzt custom Farbe
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityTypeCard(
    row: ActivityTypeRow,
    onScoreChange: (Int) -> Unit,
    onScoreCommit: () -> Unit,
    onIconChange: (String) -> Unit,
    onColorChange: (Long) -> Unit
) {
    var showIconPicker by remember { mutableStateOf(false) }
    val accent = if (row.color != 0L) Color(row.color) else positivityColor(row.score)

    GlassCard(accentColor = accent) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon in farbigem Kreis — Tippen öffnet Icon-Picker
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.18f))
                        .clickable { showIconPicker = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (row.icon.isBlank()) "•" else row.icon,
                        fontSize = 22.sp
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = AevumSpacing.sm)) {
                    Text(
                        row.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!row.isSystem) {
                        Text(
                            "Eigen",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    row.score.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = positivityColor(row.score)
                )
            }

            PositivitySlider(
                score = row.score,
                onScoreChange = onScoreChange,
                onValueChangeFinished = onScoreCommit
            )

            // M18.12: Farb-Palette — custom Farbe pro Aktivität
            Text(
                "FARBE",
                fontSize = 9.sp,
                letterSpacing = 1.0.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ACTIVITY_COLORS.forEach { c ->
                    val selected = row.color == c
                    Box(
                        modifier = Modifier
                            .size(if (selected) 30.dp else 26.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .clickable { onColorChange(if (selected) 0L else c) }
                            .then(
                                if (selected) Modifier.padding(2.dp) else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                        }
                    }
                }
            }
        }
    }

    // M18.12: Icon-Picker (Emoji-Grid)
    if (showIconPicker) {
        AlertDialog(
            onDismissRequest = { showIconPicker = false },
            title = { Text("Icon wählen", fontWeight = FontWeight.SemiBold) },
            text = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ACTIVITY_ICONS.forEach { icon ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (icon == row.icon) accent.copy(alpha = 0.25f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                )
                                .clickable {
                                    onIconChange(icon)
                                    showIconPicker = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(icon, fontSize = 22.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIconPicker = false }) { Text("Fertig") }
            }
        )
    }
}

/** M18.12: Icon-Auswahl (Emojis) für Aktivitäten. */
private val ACTIVITY_ICONS = listOf(
    "💼", "🧠", "🌙", "🏋️", "📚", "📖", "🧘", "🍽️", "👥", "🧹",
    "🚗", "🚆", "📱", "🎮", "✨", "🎸", "🎨", "🏃", "🚴", "⛰️",
    "🌊", "☕", "🍺", "🎬", "🎵", "✍️", "💻", "🛠️", "🛒", "👶",
    "🐕", "🌱", "💊", "🛌", "🚿", "🧺", "📞", "💬", "🤝", "🎓"
)

/** M18.12: Farb-Palette für Aktivitäten (ARGB-Ints). */
private val ACTIVITY_COLORS = listOf(
    0xFF5C6BC0, 0xFF7E57C2, 0xFFEC407A, 0xFFEF5350, 0xFFFFA726,
    0xFFFDD835, 0xFF43A047, 0xFF26A69A, 0xFF29B6F6, 0xFF78909C
).map { it.toLong() }
