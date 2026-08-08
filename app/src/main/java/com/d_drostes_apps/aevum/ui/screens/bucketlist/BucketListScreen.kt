package com.d_drostes_apps.aevum.ui.screens.bucketlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.data.model.BucketListItem
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * M18.39: Bucket-List-Screen — "Was willst du im Leben unbedingt machen?"
 *
 * Design (nach Internet-Recherche der besten Bucket-List-Apps):
 *  - Hero mit Fortschritts-Ring (X von Y geschafft)
 *  - Filter-Chips: Alle / Offen / Erledigt
 *  - Karten mit Icon, Titel, Ort, Kategorie-Chip, optionalem Datum
 *  - Erledigte Eintraege: durchgestrichen + gruener Haken + "geschafft am"
 *  - FAB fuer neue Eintraege, Stift zum Bearbeiten
 */
@Composable
fun BucketListScreen(
    modifier: Modifier = Modifier,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit = {},
    viewModel: BucketListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var filter by remember { mutableStateOf(BucketFilter.ALL) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = "Neuer Eintrag")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(
                start = AevumSpacing.md,
                end = AevumSpacing.md,
                top = AevumSpacing.lg,
                bottom = 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item {
                BucketHero(
                    done = state.doneCount,
                    total = state.totalCount,
                    progress = state.progress,
                    level = state.level,
                    levelTitle = state.levelTitle,
                    levelProgress = state.levelProgress,
                    totalXp = state.totalXp,
                    xpIntoLevel = state.xpIntoLevel,
                    xpForNextLevel = state.xpForNextLevel
                )
            }

            if (state.items.isEmpty()) {
                item { BucketEmptyState(onCreate = onCreate) }
            } else {
                item { BucketFilterChips(filter = filter, onSelect = { filter = it }) }

                val visible = when (filter) {
                    BucketFilter.ALL -> state.items
                    BucketFilter.OPEN -> state.items.filter { !it.completed }
                    BucketFilter.DONE -> state.items.filter { it.completed }
                }
                if (visible.isEmpty()) {
                    item {
                        Text(
                            if (filter == BucketFilter.DONE) "Noch nichts geschafft — aber der Weg ist das Ziel! 🚀" else "Alles erledigt! 🎉",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = AevumSpacing.md)
                        )
                    }
                } else {
                    items(visible, key = { it.id }) { item ->
                        BucketCard(
                            item = item,
                            onToggle = { viewModel.toggleCompleted(item) },
                            onEdit = { onEdit(item.id) },
                            onDelete = { viewModel.delete(item) },
                            onDifficulty = { diff -> viewModel.setDifficulty(item, diff) }
                        )
                    }
                }
            }
        }
    }
}

private enum class BucketFilter { ALL, OPEN, DONE }

@Composable
private fun BucketHero(
    done: Int,
    total: Int,
    progress: Float,
    level: Int,
    levelTitle: String,
    levelProgress: Float,
    totalXp: Int,
    xpIntoLevel: Int,
    xpForNextLevel: Int
) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.lg)
            ) {
                // Fortschritts-Ring
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.size(72.dp)) {
                        val stroke = 8.dp.toPx()
                        val arcSize = size.minDimension - stroke
                        drawArc(
                            color = Color.White.copy(alpha = 0.2f),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = Color.White,
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        "${(progress * 100).toInt()}%",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("BUCKET LIST", fontSize = 11.sp, letterSpacing = 1.1.sp, color = Color.White.copy(alpha = 0.8f))
                    Text(
                        "Was willst du im Leben unbedingt machen?",
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        "$done von $total geschafft",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
            // M18.43: Level + XP-Leiste (Gamification)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⭐", fontSize = 15.sp)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "Level $level · $levelTitle",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            "$xpIntoLevel / $xpForNextLevel XP",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                    // XP-Fortschrittsbalken
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(levelProgress.coerceIn(0f, 1f))
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFD54F))
                        )
                    }
                }
            }
            Text(
                "Gesamt: $totalXp XP gesammelt",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun BucketFilterChips(filter: BucketFilter, onSelect: (BucketFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        BucketFilter.entries.forEach { f ->
            val selected = filter == f
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AevumRadius.full))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                    .clickable { onSelect(f) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    when (f) {
                        BucketFilter.ALL -> "Alle"
                        BucketFilter.OPEN -> "Offen"
                        BucketFilter.DONE -> "Erledigt"
                    },
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BucketCard(
    item: BucketListItem,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDifficulty: (Int) -> Unit
) {
    val accentColor = if (item.completed) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)),
        color = if (item.completed) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        }
    ) {
        Column(
            modifier = Modifier.padding(AevumSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                // Checkbox (custom, fancy)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (item.completed) accentColor
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                        )
                        .clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.completed) {
                        Text("✓", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                // Icon
                if (!item.icon.isNullOrBlank()) {
                    Text(item.icon!!, fontSize = 24.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.title,
                        fontSize = 16.sp,
                        fontWeight = if (item.completed) FontWeight.Normal else FontWeight.SemiBold,
                        textDecoration = if (item.completed) TextDecoration.LineThrough else null,
                        color = if (item.completed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Meta-Zeile: Ort + Kategorie + Datum
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (!item.location.isNullOrBlank()) {
                            Text("📍 ${item.location}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!item.category.isNullOrBlank()) {
                            Text("·", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            Text(item.category!!, fontSize = 11.sp, color = accentColor, fontWeight = FontWeight.Medium)
                        }
                        if (!item.targetDate.isNullOrBlank()) {
                            Text("·", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            Text(
                                "bis ${formatDate(item.targetDate!!)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (item.completed && item.completedAt != null) {
                        Text(
                            "Geschafft am ${formatTimestamp(item.completedAt!!)} 🎉",
                            fontSize = 11.sp,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Medium
                        )
                    }
                    // M18.43: Schwierigkeits-Sterne (1-5) — Tippen setzt den
                    // Grad, bestimmt die XP-Belohnung beim Abhaken.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Schwierigkeit", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        (1..5).forEach { star ->
                            val filled = star <= item.difficulty
                            Text(
                                if (filled) "★" else "☆",
                                fontSize = 14.sp,
                                color = if (filled) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onDifficulty(star) }
                                    .padding(horizontal = 1.dp, vertical = 2.dp)
                            )
                        }
                        if (!item.completed) {
                            Text(
                                "+${item.xpReward} XP",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFB300)
                            )
                        }
                    }
                }
                // Aktionen
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Bearbeiten", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Löschen", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun BucketEmptyState(onCreate: () -> Unit) {
    AevumCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            Text("🌍", fontSize = 40.sp)
            Text("Deine Bucket List ist noch leer", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Was willst du unbedingt machen, bevor du stirbst?",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Tippe auf + um deinen ersten Traum festzuhalten",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun formatDate(iso: String): String {
    return runCatching {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d. MMM yyyy", Locale.GERMANY))
    }.getOrDefault(iso)
}

private fun formatTimestamp(ts: Long): String {
    return runCatching {
        java.time.Instant.ofEpochMilli(ts)
            .atZone(java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("d. MMM yyyy", Locale.GERMANY))
    }.getOrDefault("")
}
