package com.d_drostes_apps.aevum.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/**
 * M17.4: AnimatedGradientBar.
 *
 * Compose-Canvas-basierte Bar, die von 0 auf die Zielweite animiert,
 * sobald sie zum ersten Mal sichtbar wird. Verwendet einen
 * horizontalen Gradient (accent → surface-variant) für den
 * "futuristischen" Look.
 *
 * - [progress]: 0.0 - 1.0 (relativ zu Maximalwert der Liste)
 * - [color]: Bar-Grundfarbe (für mono-Bars)
 * - [gradientStart]/[gradientEnd]: optionaler expliziter Gradient
 *   (für mehrtönige Bars)
 * - [trackColor]: Hintergrund der Bar (default: surface-variant mit alpha)
 */
@Composable
fun AnimatedGradientBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    gradientStart: Color? = null,
    gradientEnd: Color? = null,
    trackColor: Color = Color.White.copy(alpha = 0.08f),
    height: androidx.compose.ui.unit.Dp = 12.dp,
    cornerRadius: androidx.compose.ui.unit.Dp = 6.dp,
    animationDelayMs: Int = 0
) {
    var visible by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(progress) {
        kotlinx.coroutines.delay(animationDelayMs.toLong())
        visible = 1f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = if (visible > 0f) progress.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "barProgress"
    )

    Canvas(modifier = modifier.height(height)) {
        val cr = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
        val w = size.width
        val h = size.height

        // Track (Hintergrund)
        drawRoundRect(
            color = trackColor,
            topLeft = Offset.Zero,
            size = Size(w, h),
            cornerRadius = cr
        )

        if (animatedProgress > 0f) {
            val barW = w * animatedProgress
            val brush = if (gradientStart != null && gradientEnd != null) {
                Brush.horizontalGradient(
                    colors = listOf(gradientStart, gradientEnd),
                    startX = 0f,
                    endX = barW
                )
            } else {
                Brush.horizontalGradient(
                    colors = listOf(
                        color,
                        color.copy(alpha = 0.7f)
                    ),
                    startX = 0f,
                    endX = barW
                )
            }
            drawRoundRect(
                brush = brush,
                topLeft = Offset.Zero,
                size = Size(barW, h),
                cornerRadius = cr
            )

            // M17.4: Glanz-Layer (subtil) — ein 30% Alpha weißer Strich
            // mittig über die Bar für den "Glow"-Effekt.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.25f), Color.Transparent),
                    startY = 0f,
                    endY = h
                ),
                topLeft = Offset(0f, 0f),
                size = Size(barW, h * 0.5f),
                cornerRadius = cr
            )
        }
    }
}

/**
 * M17.4: AnimatedNumberCounter.
 *
 * Animiert einen Int-Wert von 0 → target. Verwendet für Hero-Header
 * ("Diese Woche 42 Stunden").
 */
@Composable
fun AnimatedNumberCounter(
    target: Int,
    modifier: Modifier = Modifier,
    durationMs: Int = 1200
) {
    var current by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(target) {
        val start = current
        val diff = target - start
        val steps = 30
        val stepMs = durationMs / steps
        for (i in 1..steps) {
            current = start + diff * (i / steps.toFloat())
            kotlinx.coroutines.delay(stepMs.toLong())
        }
        current = target.toFloat()
    }
    androidx.compose.material3.Text(
        text = current.toInt().toString(),
        modifier = modifier,
        style = androidx.compose.material3.MaterialTheme.typography.displayMedium
    )
}
