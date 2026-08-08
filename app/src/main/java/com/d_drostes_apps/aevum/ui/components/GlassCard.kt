package com.d_drostes_apps.aevum.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.d_drostes_apps.aevum.ui.theme.AevumRadius

/**
 * M17.4: GlassCard.
 *
 * Glassmorphism-Surface: semi-transparenter Gradient-Hintergrund +
 * Gradient-Border. Nutzt [androidx.compose.material3.Surface] für
 * das Border, einen modifizierten Hintergrund für den Glass-Effekt.
 *
 * Standard für die Insights-Screen: ersetzt das stumpfe Material-Card.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accentColor: Color? = null,
    borderWidth: Dp = 1.dp,
    cornerRadius: Dp = AevumRadius.lg,
    contentPadding: Dp = 16.dp,
    content: @Composable () -> Unit
) {
    val accent = accentColor ?: MaterialTheme.colorScheme.primary
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        color = Color.Transparent,
        border = BorderStroke(
            width = borderWidth,
            brush = Brush.linearGradient(
                colors = listOf(
                    accent.copy(alpha = 0.6f),
                    accent.copy(alpha = 0.2f),
                    accent.copy(alpha = 0.05f)
                )
            )
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        )
                    )
                )
                .padding(contentPadding)
        ) {
            content()
        }
    }
}
