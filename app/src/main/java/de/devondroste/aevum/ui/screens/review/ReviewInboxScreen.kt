package de.devondroste.aevum.ui.screens.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import de.devondroste.aevum.data.model.ActivityCandidate
import de.devondroste.aevum.domain.time.TimeFormatting
import de.devondroste.aevum.ui.components.AevumCard
import de.devondroste.aevum.ui.components.CardVariant
import de.devondroste.aevum.ui.components.EmptyState
import de.devondroste.aevum.ui.theme.AevumRadius
import de.devondroste.aevum.ui.theme.AevumSpacing
import de.devondroste.aevum.ui.theme.AevumTheme

@Composable
fun ReviewInboxScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenEditor: (ActivityCandidate) -> Unit = {},
    onOpenSession: (String) -> Unit = {},
    viewModel: ReviewInboxViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var multiSelectMode by remember { mutableStateOf(false) }

    ReviewInboxContent(
        modifier = modifier,
        state = state,
        multiSelectMode = multiSelectMode,
        onToggleMultiSelect = { multiSelectMode = !multiSelectMode },
        selectedCount = { viewModel.selectionCount() },
        isSelected = viewModel::isSelected,
        onToggleSelection = { id ->
            viewModel.toggleSelection(id)
            if (viewModel.selectionCount() == 0) multiSelectMode = false
        },
        onSelectAllSafe = {
            viewModel.selectAllSafe(state.candidates)
            multiSelectMode = true
        },
        onBack = onBack,
        onAcceptSingle = { candidate -> viewModel.acceptSingle(candidate, onOpenSession) },
        onDismissSingle = { viewModel.dismissSingle(it) },
        onAcceptSelected = {
            viewModel.acceptSelected()
            multiSelectMode = false
        },
        onDismissSelected = {
            viewModel.dismissSelected()
            multiSelectMode = false
        },
        onAcceptAllSafe = { viewModel.acceptAllSafe() },
        onOpenEditor = onOpenEditor
    )
}

@Composable
private fun ReviewInboxContent(
    modifier: Modifier = Modifier,
    state: ReviewInboxUiState,
    multiSelectMode: Boolean,
    onToggleMultiSelect: () -> Unit,
    selectedCount: () -> Int,
    isSelected: (String) -> Boolean,
    onToggleSelection: (String) -> Unit,
    onSelectAllSafe: () -> Unit,
    onBack: () -> Unit,
    onAcceptSingle: (ActivityCandidate) -> Unit,
    onDismissSingle: (ActivityCandidate) -> Unit,
    onAcceptSelected: () -> Unit,
    onDismissSelected: () -> Unit,
    onAcceptAllSafe: () -> Unit,
    onOpenEditor: (ActivityCandidate) -> Unit
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("REVIEW INBOX", fontSize = 11.sp, letterSpacing = 1.1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Aevum hat etwas vorbereitet", fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
                        Text("Vorschläge zählen erst, wenn du sie übernimmst.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onBack) { Text("✕", fontSize = 24.sp) }
                }

                // "Accept all safe" button (above list)
                if (!state.isEmpty && state.safeAcceptCount > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = AevumSpacing.md),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onAcceptAllSafe) {
                            Text(
                                "${state.safeAcceptCount} sichere Vorschläge übernehmen",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (state.isEmpty) {
                    Box(modifier = Modifier.fillMaxSize().padding(AevumSpacing.md), contentAlignment = Alignment.Center) {
                        BetterEmptyState(onBack = onBack)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().weight(1f),
                        contentPadding = PaddingValues(
                            start = AevumSpacing.md, end = AevumSpacing.md,
                            top = AevumSpacing.sm,
                            bottom = if (multiSelectMode) 100.dp else AevumSpacing.xl
                        ),
                        verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
                    ) {
                        state.candidates.forEach { candidate ->
                            item(key = candidate.id) {
                                ReviewCandidateCard(
                                    candidate = candidate,
                                    multiSelectMode = multiSelectMode,
                                    isSelected = isSelected(candidate.id),
                                    onToggleSelect = { onToggleSelection(candidate.id) },
                                    onLongPress = {
                                        if (!multiSelectMode) {
                                            onToggleMultiSelect()
                                            onToggleSelection(candidate.id)
                                        }
                                    },
                                    onAccept = { onAcceptSingle(candidate) },
                                    onDismiss = { onDismissSingle(candidate) },
                                    onEdit = { onOpenEditor(candidate) }
                                )
                            }
                        }
                        item { Spacer(Modifier.height(AevumSpacing.xl)) }
                    }
                }
            }

            // Bottom bar for multi-select
            AnimatedVisibility(
                visible = multiSelectMode && selectedCount() > 0,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 8.dp,
                    shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${selectedCount()} ausgewählt",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            OutlinedButton(onClick = {
                                onDismissSelected()
                            }) { Text("Verwerfen") }
                            Button(onClick = {
                                onAcceptSelected()
                            }) { Text("Übernehmen") }
                        }
                    }
                }
            }

            // Bottom hint when multi-select but nothing selected
            AnimatedVisibility(
                visible = multiSelectMode && selectedCount() == 0,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Tippe Karten zum Auswählen",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            TextButton(onClick = onSelectAllSafe) {
                                Text("Alle sicheren", fontSize = 14.sp)
                            }
                            TextButton(onClick = onToggleMultiSelect) {
                                Text("Abbrechen", fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewCandidateCard(
    candidate: ActivityCandidate,
    multiSelectMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit
) {
    val color = if (candidate.confidence >= 0.7f) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    val borderMod = if (multiSelectMode && isSelected) {
        Modifier.background(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            RoundedCornerShape(AevumRadius.lg)
        )
    } else Modifier

    AevumCard(
        variant = CardVariant.Elevated,
        contentPadding = PaddingValues(AevumSpacing.md),
        modifier = borderMod,
        onClick = if (multiSelectMode) onToggleSelect else null,
        onLongClick = onLongPress
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Checkbox in multi-select mode
                        if (multiSelectMode) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(AevumRadius.sm)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) Text("✓", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimary)
                            }
                            Spacer(Modifier.width(AevumSpacing.sm))
                        }
                        Column {
                            Text(candidate.suggestedTitle, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${TimeFormatting.formatTime(candidate.startAt)} – ${TimeFormatting.formatTime(candidate.endAt)}",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                if (!multiSelectMode) {
                    Box(
                        modifier = Modifier
                            .padding(start = AevumSpacing.sm)
                            .background(color.copy(alpha = 0.14f), RoundedCornerShape(AevumRadius.full))
                            .padding(horizontal = AevumSpacing.sm, vertical = 4.dp)
                    ) {
                        Text("${(candidate.confidence * 100).toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
                    }
                }
            }

            Text(
                text = candidate.reason ?: "Automatischer Vorschlag aus deinen lokalen Signalen.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )

            // Actions (hidden in multi-select mode)
            if (!multiSelectMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Verwerfen") }
                    OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Bearbeiten") }
                    Button(onClick = onAccept, modifier = Modifier.weight(1f)) { Text("Übernehmen") }
                }
            }
        }
    }
}

@Composable
private fun BetterEmptyState(onBack: () -> Unit) {
    EmptyState(
        title = "Alles geprüft.",
        message = "Keine offenen Vorschläge. Aevum informiert dich, sobald etwas Neues erkannt wurde.",
        actionLabel = "Zurück",
        onActionClick = onBack
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 800)
@Composable
private fun ReviewInboxScreenPreview() {
    AevumTheme(darkTheme = true) {
        ReviewInboxContent(
            state = ReviewInboxUiState(
                candidates = listOf(
                    ActivityCandidate(
                        id = "1",
                        suggestedTitle = "Arbeit",
                        suggestedCategoryId = "work",
                        activityTypeId = "work",
                        startAt = System.currentTimeMillis() - 8 * 60 * 60 * 1000,
                        endAt = System.currentTimeMillis() - 2 * 60 * 60 * 1000,
                        confidence = 0.85f,
                        status = "PENDING",
                        reason = "Zuhause verlassen → Rewe Büro betreten",
                        createdBy = "TRIGGER_PAIR_RULES_V1",
                        createdAt = System.currentTimeMillis() - 8 * 60 * 60 * 1000,
                        resolvedAt = null,
                        resolvedSessionId = null,
                        sourceCandidateId = null
                    ),
                    ActivityCandidate(
                        id = "2",
                        suggestedTitle = "Arbeitsweg",
                        suggestedCategoryId = "transport",
                        activityTypeId = "transport",
                        startAt = System.currentTimeMillis() - 9 * 60 * 60 * 1000,
                        endAt = System.currentTimeMillis() - 8 * 60 * 60 * 1000,
                        confidence = 0.82f,
                        status = "PENDING",
                        reason = "Zuhause → Rewe Büro",
                        createdBy = "TRIGGER_PAIR_RULES_V1",
                        createdAt = System.currentTimeMillis() - 9 * 60 * 60 * 1000,
                        resolvedAt = null,
                        resolvedSessionId = null,
                        sourceCandidateId = null
                    )
                ),
                isEmpty = false,
                safeAcceptCount = 2
            ),
            multiSelectMode = false,
            onToggleMultiSelect = {},
            selectedCount = { 0 },
            isSelected = { false },
            onToggleSelection = {},
            onSelectAllSafe = {},
            onBack = {},
            onAcceptSingle = {},
            onDismissSingle = {},
            onAcceptSelected = {},
            onDismissSelected = {},
            onAcceptAllSafe = {},
            onOpenEditor = {}
        )
    }
}
