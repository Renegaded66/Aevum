package de.devondroste.aevum.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.devondroste.aevum.ui.theme.AevumRadius
import de.devondroste.aevum.ui.theme.AevumSpacing

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AevumCard(
    modifier: Modifier = Modifier,
    variant: CardVariant = CardVariant.Elevated,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(AevumSpacing.lg),
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val container = when (variant) {
        CardVariant.Elevated -> colors.surface
        CardVariant.Filled -> colors.surfaceVariant.copy(alpha = 0.62f)
        CardVariant.Outlined -> Color.Transparent
        CardVariant.Gradient -> colors.primaryContainer.copy(alpha = 0.30f)
    }
    val border = when (variant) {
        CardVariant.Outlined -> BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.74f))
        CardVariant.Gradient -> BorderStroke(1.dp, colors.primary.copy(alpha = 0.30f))
        else -> BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.18f))
    }
    val clickableModifier = when {
        onClick != null && onLongClick != null ->
            modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        onClick != null -> modifier.clickable(onClick = onClick)
        onLongClick != null -> modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
        else -> modifier
    }

    Surface(
        modifier = clickableModifier.fillMaxWidth(),
        shape = RoundedCornerShape(AevumRadius.xl),
        color = container,
        contentColor = colors.onSurface,
        border = border,
        shadowElevation = if (variant == CardVariant.Elevated || variant == CardVariant.Gradient) 2.dp else 0.dp,
        tonalElevation = 0.dp
    ) {
        androidx.compose.foundation.layout.Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}

enum class CardVariant { Elevated, Filled, Outlined, Gradient }
