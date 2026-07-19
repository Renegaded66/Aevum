package de.devondroste.aevum.ui.screens.review

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
    val state = viewModel.uiState.collectAsState()
    ReviewInboxContent(
        modifier = modifier,
        state = state.value,
        onBack = onBack,
        onAccept = { candidate -> viewModel.accept(candidate, onOpenSession) },
        onDismiss = viewModel::dismiss,
        onOpenEditor = onOpenEditor
    )
}

@Composable
private fun ReviewInboxContent(
    modifier: Modifier = Modifier,
    state: ReviewInboxUiState,
    onBack: () -> Unit,
    onAccept: (ActivityCandidate) -> Unit,
    onDismiss: (ActivityCandidate) -> Unit,
    onOpenEditor: (ActivityCandidate) -> Unit
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
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

            if (state.isEmpty) {
                Box(modifier = Modifier.fillMaxSize().padding(AevumSpacing.md), contentAlignment = Alignment.Center) {
                    BetterEmptyState(onBack = onBack)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
                ) {
                    state.candidates.forEach { candidate ->
                        item(key = candidate.id) {
                            ReviewCandidateCard(
                                candidate = candidate,
                                onAccept = { onAccept(candidate) },
                                onDismiss = { onDismiss(candidate) },
                                onEdit = { onOpenEditor(candidate) }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(AevumSpacing.xl)) }
                }
            }
        }
    }
}

@Composable
private fun ReviewCandidateCard(
    candidate: ActivityCandidate,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit
) {
    val color = if (candidate.confidence >= 0.7f) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

    AevumCard(variant = CardVariant.Elevated, contentPadding = PaddingValues(AevumSpacing.md)) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(candidate.suggestedTitle, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${TimeFormatting.formatTime(candidate.startAt)} – ${TimeFormatting.formatTime(candidate.endAt)}",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(start = AevumSpacing.sm)
                        .background(color.copy(alpha = 0.14f), RoundedCornerShape(AevumRadius.full))
                        .padding(horizontal = AevumSpacing.sm, vertical = 4.dp)
                ) {
                    Text("${(candidate.confidence * 100).toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
                }
            }

            Text(
                text = candidate.reason ?: "Automatischer Vorschlag aus deinen lokalen Signalen.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )

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
                        reason = "Zuhause verlassen → Büro betreten",
                        createdBy = "TRIGGER_PAIR_RULES_V1",
                        createdAt = System.currentTimeMillis() - 8 * 60 * 60 * 1000,
                        resolvedAt = null,
                        resolvedSessionId = null,
                        sourceCandidateId = null
                    )
                ),
                isEmpty = false
            ),
            onBack = {},
            onAccept = {},
            onDismiss = {},
            onOpenEditor = {}
        )
    }
}
