package com.d_drostes_apps.aevum.ui.screens.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

/**
 * M18.59: Kategorien-Seite (Einstellungen → Kategorien).
 *
 * Zwei-Spalten-Usability (hinterfragt, bewusst KEIN Drag&Drop):
 * - Drag&Drop ist auf Touch schwer entdeckbar und fehleranfällig
 *   (langes Drücken, versehentliches Verschieben). Stattdessen:
 *   links die Kategorien (mit Icon + Farbe), rechts die Aktivitäten
 *   der gewählten Kategorie. Zuordnung per Dropdown pro Aktivität —
 *   ein Tipp, sofort sichtbar.
 * - Neue Kategorie: Dialog mit Name, Emoji-Icon-Picker, Farb-Palette.
 * - System-Kategorien: nicht löschbar, aber personalisierbar.
 */
@Composable
fun CategoriesScreen(
    onBack: () -> Unit = {},
    viewModel: CategoriesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            // Header
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        TextButton(onClick = onBack) { Text("Zurück", fontSize = 14.sp) }
                        Text("Kategorien", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Wähle links eine Kategorie — rechts siehst du ihre Aktivitäten. Ordne Aktivitäten per Dropdown zu.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Zwei-Spalten: Kategorien links, Aktivitäten rechts
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
                ) {
                    // ── Linke Spalte: Kategorien ──
                    Column(
                        modifier = Modifier.weight(0.9f),
                        verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
                    ) {
                        Text(
                            "Kategorien",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        state.categories.forEach { category ->
                            CategoryRow(
                                category = category,
                                selected = category.id == state.selectedCategoryId,
                                onClick = { viewModel.selectCategory(category.id) },
                                onEdit = { /* Personalisieren-Dialog */ },
                                onDelete = { viewModel.deleteCategory(category) }
                            )
                        }
                        OutlinedButton(
                            onClick = { showCreateDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("+ Neue Kategorie", fontSize = 13.sp)
                        }
                    }

                    // ── Rechte Spalte: Aktivitäten der Kategorie ──
                    Column(
                        modifier = Modifier.weight(1.1f),
                        verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
                    ) {
                        Text(
                            state.selectedCategory?.name?.uppercase() ?: "Aktivitäten",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.activitiesOfSelected.isEmpty() && state.unassigned.isEmpty()) {
                            Text(
                                "Keine Aktivitäten in dieser Kategorie.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        state.activitiesOfSelected.forEach { type ->
                            ActivityAssignRow(
                                type = type,
                                categories = state.categories,
                                currentCategoryId = type.defaultCategoryId,
                                onAssign = { categoryId -> viewModel.assignActivity(type.id, categoryId) }
                            )
                        }
                        // Unzugeordnete Aktivitäten als eigener Block
                        if (state.unassigned.isNotEmpty()) {
                            Text(
                                "Ohne Kategorie",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = AevumSpacing.sm)
                            )
                            state.unassigned.forEach { type ->
                                ActivityAssignRow(
                                    type = type,
                                    categories = state.categories,
                                    currentCategoryId = null,
                                    onAssign = { categoryId -> viewModel.assignActivity(type.id, categoryId) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateCategoryDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, icon, colorHex ->
                viewModel.createCategory(name, icon, colorHex)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun CategoryRow(
    category: Category,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AevumRadius.md))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = AevumSpacing.sm, vertical = AevumSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(parseColor(category.color).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(category.icon.ifBlank { "•" }, fontSize = 14.sp)
        }
        Text(
            category.name,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (category.isSystem) {
            Text("🔒", fontSize = 11.sp)
        }
    }
}

@Composable
private fun ActivityAssignRow(
    type: ActivityType,
    categories: List<Category>,
    currentCategoryId: String?,
    onAssign: (String?) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AevumRadius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = AevumSpacing.sm, vertical = AevumSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
    ) {
        Text(
            if (type.icon.isBlank()) "•" else type.icon,
            fontSize = 15.sp
        )
        Text(
            type.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Box {
            val currentName = categories.firstOrNull { it.id == currentCategoryId }?.name ?: "—"
            Text(
                currentName,
                fontSize = 12.sp,
                color = if (currentCategoryId != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(AevumRadius.sm))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .clickable { menuOpen = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("— Keine Kategorie —", fontSize = 13.sp) },
                    onClick = { onAssign(null); menuOpen = false }
                )
                categories.forEach { category ->
                    DropdownMenuItem(
                        text = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(category.icon.ifBlank { "•" }, fontSize = 13.sp)
                                Text(category.name, fontSize = 13.sp)
                            }
                        },
                        onClick = { onAssign(category.id); menuOpen = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateCategoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, icon: String, colorHex: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("📁") }
    var colorHex by remember { mutableStateOf("#6366F1") }

    val iconChoices = listOf("📁", "💼", "🧠", "📚", "🏋️", "🚗", "📱", "🎮", "🍽️", "👥", "🧹", "🌙", "✈️", "🎨", "🎵", "🧘", "💻", "🛒", "🏠", "⭐")
    val colorChoices = listOf(
        "#6366F1", "#EC4899", "#F59E0B", "#22C55E", "#06B6D4",
        "#8B5CF6", "#EF4444", "#10B981", "#3B82F6", "#F97316"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neue Kategorie", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (z. B. Sport)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Icon", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    iconChoices.take(10).forEach { choice ->
                        Text(
                            choice,
                            fontSize = 18.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (icon == choice) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { icon = choice }
                                .padding(6.dp)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    iconChoices.drop(10).forEach { choice ->
                        Text(
                            choice,
                            fontSize = 18.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (icon == choice) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { icon = choice }
                                .padding(6.dp)
                        )
                    }
                }
                Text("Farbe", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    colorChoices.forEach { choice ->
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(parseColor(choice))
                                .clickable { colorHex = choice }
                                .then(
                                    if (colorHex == choice) Modifier.padding(2.dp)
                                    else Modifier
                                )
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, icon, colorHex) },
                enabled = name.trim().isNotEmpty()
            ) { Text("Anlegen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

private fun parseColor(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        Color(0xFF6366F1)
    }
}
