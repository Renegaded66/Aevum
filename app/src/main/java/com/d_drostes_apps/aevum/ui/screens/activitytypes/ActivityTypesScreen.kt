package com.d_drostes_apps.aevum.ui.screens.activitytypes

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
import androidx.compose.foundation.layout.width
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
import com.d_drostes_apps.aevum.ui.components.GlassCard
import com.d_drostes_apps.aevum.ui.components.PositivitySlider
import com.d_drostes_apps.aevum.ui.components.positivityColor
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

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
    // M18.17: Kategorie-Auswahl beim Anlegen
    var newCategoryId by remember { mutableStateOf<String?>(null) }

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
                            categories = state.categories,
                            onScoreChange = { viewModel.onScoreDragged(row.id, it) },
                            onScoreCommit = { viewModel.commitScore(row.id) },
                            onIconChange = { viewModel.setIcon(row.id, it) },
                            onColorChange = { viewModel.setColor(row.id, it) },
                            onCategoryChange = { viewModel.setCategory(row.id, it) },
                            onCreateCategory = viewModel::createCategory,
                            // M18.51 (User: "nicht alle einen Lösch-Button erhalten
                            // muss nicht sein, das einzige was kaputt gehen könnte
                            // ist Schlaf, von mir aus darf die Activity keinen
                            // Lösch-Button erhalten, aber alles andere schon"):
                            // Geschützt sind nur "sleep" (Schlaf-Erkennung) und
                            // "other" (Fallback "Sonstiges" für Umbuchungen).
                            // Alle anderen Typen sind löschbar — die Auto-Engines
                            // fallen bei gelöschten Typen auf "Sonstiges" zurück.
                            onDelete = if (row.id == "sleep" || row.id == "other") null else { alsoDeleteSessions: Boolean ->
                                viewModel.deleteActivity(row.id, alsoDeleteSessions)
                            }
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
            onDismissRequest = { showCreateDialog = false; newName = ""; newCategoryId = null },
            title = { Text("Neue Aktivität", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Name (z. B. Gitarre)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // M18.17: Kategorie beim Anlegen wählen
                    Text("Kategorie (optional)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.categories.forEach { cat ->
                            val selected = cat.id == newCategoryId
                            Box(
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.large)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    )
                                    .clickable { newCategoryId = if (selected) null else cat.id }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    "${cat.icon} ${cat.name}",
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createActivity(newName, newCategoryId)
                        showCreateDialog = false
                        newName = ""
                        newCategoryId = null
                    },
                    enabled = newName.trim().isNotEmpty()
                ) { Text("Anlegen") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false; newName = ""; newCategoryId = null }) { Text("Abbrechen") }
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
    categories: List<CategoryRow>,
    onScoreChange: (Int) -> Unit,
    onScoreCommit: () -> Unit,
    onIconChange: (String) -> Unit,
    onColorChange: (Long) -> Unit,
    // M18.17: Kategorie-Zuordnung
    onCategoryChange: (String?) -> Unit,
    onCreateCategory: (String) -> Unit,
    // M18.50: Löschen (null = System-Typ, nicht löschbar).
    // Parameter: alsoDeleteSessions (true = Activity + alle Aufzeichnungen).
    onDelete: ((Boolean) -> Unit)? = null
) {
    var showIconPicker by remember { mutableStateOf(false) }
    // M18.17: Kategorie-Picker-Dialog
    var showCategoryPicker by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    // M18.50: Bestätigungsdialog für das Löschen.
    var showDeleteDialog by remember { mutableStateOf(false) }
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
                // M18.50: Lösch-Button (nur eigene Typen) + Score
                if (onDelete != null) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
                            .clickable { showDeleteDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🗑", fontSize = 15.sp)
                    }
                }
                Text(
                    row.score.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = positivityColor(row.score),
                    modifier = Modifier.padding(start = AevumSpacing.sm)
                )
            }

            PositivitySlider(
                score = row.score,
                onScoreChange = onScoreChange,
                onValueChangeFinished = onScoreCommit
            )

            // M18.17: Kategorie-Zuordnung — Chip zeigt aktuelle Kategorie,
            // Tippen öffnet den Picker (inkl. "Keine" + "Neue Kategorie").
            Text(
                "KATEGORIE",
                fontSize = 9.sp,
                letterSpacing = 1.0.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                Box(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.large)
                        .background(
                            if (row.categoryId != null) accent.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                        .clickable { showCategoryPicker = true }
                        .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        row.categoryName ?: "Keine Kategorie",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (row.categoryId != null) accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

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

    // M18.17: Kategorie-Picker — Auswahl + "Keine" + "Neue Kategorie".
    if (showCategoryPicker) {
        AlertDialog(
            onDismissRequest = { showCategoryPicker = false },
            title = { Text("Kategorie für \"${row.name}\"", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // "Keine Kategorie" — Zuordnung entfernen
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (row.categoryId == null) accent.copy(alpha = 0.25f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                            .clickable {
                                onCategoryChange(null)
                                showCategoryPicker = false
                            }
                            .padding(horizontal = AevumSpacing.md, vertical = 10.dp)
                    ) {
                        Text(
                            "Keine Kategorie",
                            fontSize = 14.sp,
                            fontWeight = if (row.categoryId == null) FontWeight.Bold else FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    // Bestehende Kategorien
                    categories.forEach { cat ->
                        val selected = cat.id == row.categoryId
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) accent.copy(alpha = 0.25f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                )
                                .clickable {
                                    onCategoryChange(cat.id)
                                    showCategoryPicker = false
                                }
                                .padding(horizontal = AevumSpacing.md, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(cat.icon, fontSize = 14.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    cat.name,
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    // Neue Kategorie anlegen
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newCategoryName,
                            onValueChange = { newCategoryName = it },
                            label = { Text("Neue Kategorie…") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                val name = newCategoryName.trim()
                                if (name.isNotEmpty()) {
                                    onCreateCategory(name)
                                    newCategoryName = ""
                                }
                            },
                            enabled = newCategoryName.trim().isNotEmpty()
                        ) { Text("Anlegen") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCategoryPicker = false }) { Text("Fertig") }
            }
        )
    }

    // M18.50: Bestätigungsdialog für das Löschen einer eigenen Activity.
    // Zwei Optionen: "Nur Activity löschen" (Aufzeichnungen bleiben, werden
    // auf "Sonstiges" umgebucht) oder "Activity + Aufzeichnungen löschen"
    // (Sessions werden hart entfernt).
    if (showDeleteDialog && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("„${row.name}“ löschen?", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Die Activity wird dauerhaft entfernt und ist danach nicht mehr verfügbar.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Was soll mit den Aufzeichnungen passieren?",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    // Option 1: Nur Activity löschen (Sessions umbuchen)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .clickable {
                                onDelete(false)
                                showDeleteDialog = false
                            }
                            .padding(horizontal = AevumSpacing.md, vertical = 12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Nur Activity löschen", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "Aufzeichnungen bleiben erhalten und werden „Sonstiges“ zugeordnet.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // Option 2: Activity + alle Aufzeichnungen löschen
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
                            .clickable {
                                onDelete(true)
                                showDeleteDialog = false
                            }
                            .padding(horizontal = AevumSpacing.md, vertical = 12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Activity + alle Aufzeichnungen löschen",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                "Alle Sessions dieser Activity werden dauerhaft entfernt.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Abbrechen") }
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
