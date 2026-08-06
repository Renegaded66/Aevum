package de.devondroste.aevum.ui.screens.activitytypes

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
 * Jede Aktivität bekommt eine eigene GlassCard mit:
 *  - Name + aktueller Score (farbig)
 *  - Custom PositivitySlider (rot→gelb→grün, Emoji-Anchorpoints)
 *  - Drag → nur UI-Update, Loslassen → DB-Write
 */
@Composable
fun ActivityTypesScreen(
    onBack: () -> Unit,
    viewModel: ActivityTypesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
                Column {
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
                        GlassCard(
                            accentColor = positivityColor(row.score)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
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
                                Spacer(Modifier.height(AevumSpacing.sm))
                                PositivitySlider(
                                    score = row.score,
                                    onScoreChange = { newScore ->
                                        viewModel.onScoreDragged(row.id, newScore)
                                    },
                                    onValueChangeFinished = {
                                        // M18.6: KEIN row.score hier — Stale Closure!
                                        // Der ViewModel kennt den letzten Drag-Wert.
                                        viewModel.commitScore(row.id)
                                    }
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(AevumSpacing.xl)) }
                }
            }
        }
    }
}
