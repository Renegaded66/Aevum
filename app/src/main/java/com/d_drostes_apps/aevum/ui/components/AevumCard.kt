package com.d_drostes_apps.aevum.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

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
        CardVariant.Elevated -> colors.surface.copy(alpha = 0.96f)
        CardVariant.Filled -> colors.surfaceVariant.copy(alpha = 0.72f)
        CardVariant.Outlined -> Color.Transparent
        CardVariant.Gradient -> Color.Transparent
    }
    val border = when (variant) {
        CardVariant.Outlined -> BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.82f))
        CardVariant.Gradient -> BorderStroke(1.dp, colors.primary.copy(alpha = 0.38f))
        else -> BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.30f))
    }
    val surfaceModifier = if (variant == CardVariant.Gradient) {
        Modifier.background(
            Brush.linearGradient(
                colors = listOf(
                    colors.primaryContainer.copy(alpha = 0.70f),
                    colors.surfaceVariant.copy(alpha = 0.82f),
                    colors.secondaryContainer.copy(alpha = 0.32f)
                )
            )
        )
    } else {
        Modifier
    }
    val clickableModifier = when {
        onClick != null && onLongClick != null ->
            modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        onClick != null -> modifier.clickable(onClick = onClick)
        onLongClick != null -> modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
        else -> modifier
    }

    Surface(
        modifier = clickableModifier.fillMaxWidth().then(surfaceModifier),
        shape = RoundedCornerShape(AevumRadius.xl),
        color = container,
        contentColor = colors.onSurface,
        border = border,
        shadowElevation = if (variant == CardVariant.Elevated || variant == CardVariant.Gradient) 2.dp else 0.dp,
        tonalElevation = 0.dp
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}

enum class CardVariant { Elevated, Filled, Outlined, Gradient }
