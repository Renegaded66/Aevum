package de.devondroste.aevum.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.devondroste.aevum.ui.theme.AevumRadius
import de.devondroste.aevum.ui.theme.AevumSpacing

@Composable
fun TimelineItem(
    modifier: Modifier = Modifier,
    time: String,
    title: String,
    category: String,
    duration: String,
    source: String = "MANUAL",
    confidence: Float = 1.0f,
    isCurrent: Boolean = false,
    isConflict: Boolean = false,
    onClick: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val accent = categoryColor(category)
    val statusColor = when {
        isConflict -> Color(0xFFF59E0B)
        isCurrent -> colors.secondary
        else -> accent
    }

    AevumCard(
        modifier = modifier.fillMaxWidth(),
        variant = if (isCurrent) CardVariant.Gradient else CardVariant.Elevated,
        onClick = onClick
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier.width(54.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(time, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
                    Text(duration, fontSize = 11.sp, color = colors.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.width(AevumSpacing.md))
                Box(
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .size(14.dp)
                        .background(statusColor.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(statusColor, CircleShape)
                            .border(1.dp, colors.surface, CircleShape)
                    )
                }
                Spacer(Modifier.width(AevumSpacing.md))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                        if (isCurrent) {
                            Text("LIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = colors.secondary)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        CategoryChip(categoryId = category)
                        SourceBadge(source = source)
                        if (confidence < 1f) ConfidenceBadge(confidence = confidence)
                    }
                }
            }
            if (isConflict) {
                Text("Konflikt prüfen", fontSize = 12.sp, color = Color(0xFFF59E0B), modifier = Modifier.padding(start = 70.dp))
            }
            if (onEdit != null || onDismiss != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (onEdit != null) TextButton(onClick = onEdit) { Text("Bearbeiten") }
                    if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Verwerfen", color = colors.error) }
                }
            }
        }
    }
}

@Composable
fun ConfidenceBadge(confidence: Float) {
    val color = when {
        confidence >= 0.85f -> MaterialTheme.colorScheme.secondary
        confidence >= 0.65f -> MaterialTheme.colorScheme.tertiary
        else -> Color(0xFFF59E0B)
    }
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(AevumRadius.full),
        color = color.copy(alpha = 0.12f),
        contentColor = color
    ) {
        Text(
            text = "${(confidence * 100).toInt()}%",
            modifier = Modifier.padding(horizontal = AevumSpacing.sm, vertical = AevumSpacing.xs),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
