package com.d_drostes_apps.aevum.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * M18.93v3: ChargingRing — "Energie laden"-Visual (öffentlich, geteilt vom
 * Dashboard-Live-Banner UND der LiveActivityCard).
 *
 * Ein Fortschritts-Ring füllt sich (fraction 0..1) mit Sweep-Gradient;
 * ein Glow-Komet wandert an der Ring-Spitze mit (Orbit); im Kern pulsiert
 * das Activity-Emoji radial. Semantik im Banner: fraction = aktive
 * Aufzeichnungszeit / 60 min — nach einer vollen Stunde bleibt der Ring
 * "voll" (kein irritierender Reset). Bei Pause friert der Ring ein
 * (Fraction konstant), konsistent zum eingefrorenen Timer.
 */
@Composable
fun ChargingRing(
    fraction: Float,
    accent: Color,
    pulseAlpha: Float,
    emoji: String,
    modifier: Modifier = Modifier
) {
    val size = 76.dp
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = 5.dp.toPx()
            val strokeGlow = 10.dp.toPx()
            val diameter = size.toPx() - strokeGlow
            val topLeft = androidx.compose.ui.geometry.Offset(
                (size.toPx() - diameter) / 2f, (size.toPx() - diameter) / 2f
            )
            val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)

            // 1) Weicher Glow-Kreis hinter allem (pulsierend).
            drawCircle(
                color = accent.copy(alpha = 0.10f + 0.08f * pulseAlpha),
                radius = diameter / 2f + 6.dp.toPx(),
                center = center
            )
            // 2) Track-Ring (leise).
            drawArc(
                color = Color.White.copy(alpha = 0.14f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // 3) Fortschritts-Bogen (Gradient gefüllt bis fraction).
            drawArc(
                brush = Brush.sweepGradient(
                    0f to accent.copy(alpha = 0.45f),
                    1f to accent
                ),
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // 4) Glow-Komet an der Ring-Spitze (Orbit).
            val angle = Math.toRadians((360f * fraction - 90f).toDouble())
            val orbitR = diameter / 2f
            val cometX = center.x + orbitR * cos(angle).toFloat()
            val cometY = center.y + orbitR * sin(angle).toFloat()
            drawCircle(
                color = accent.copy(alpha = 0.30f * pulseAlpha),
                radius = strokeGlow,
                center = androidx.compose.ui.geometry.Offset(cometX, cometY)
            )
            drawCircle(
                color = accent,
                radius = stroke * 0.9f,
                center = androidx.compose.ui.geometry.Offset(cometX, cometY)
            )
        }
        // Icon-Kern (pulsierend).
        Box(
            modifier = Modifier
                .size(44.dp)
                .scale(0.94f + 0.06f * pulseAlpha)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.10f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(emoji, fontSize = 22.sp)
        }
    }
}