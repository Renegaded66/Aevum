package com.d_drostes_apps.aevum.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * M18: QualityRing — animierter Zeitqualitäts-Ring.
 *
 * Zeigt, wie "wertvoll" die erfasste Zeit heute war:
 * gewichtete Summe aus (Dauer × Positivität) aller Sessions.
 *
 * - Vollständiger Ring = 100 Punkte (jede Minute bei Score 100)
 * - Ring-Farbe: rot → gelb → grün je nach Qualitäts-Score
 * - Animiert beim Aufbau (Spring) und bei jeder Änderung
 * - Innen: Score + Label
 *
 * Design: kein Standard-ProgressIndicator, sondern eigener Canvas-Ring
 * mit Farbverlauf-Sweep — wie ein Apple-Activity-Ring, aber mit
 * "Zeitqualität" statt Kalorien.
 */
@Composable
fun QualityRing(
    qualityScore: Int,      // 0..100
    modifier: Modifier = Modifier,
    ringSize: Dp = 120.dp,
    strokeWidth: Dp = 14.dp,
    label: String = "Zeitqualität",
    // AEVUM-3: Tipp auf den Ring (Güte-Zahl) → Tages-Güte anpassen.
    onClick: (() -> Unit)? = null,
    // AEVUM-3: dezenter Hinweis („✎"), wenn die Güte manuell angepasst wurde.
    overrideBadge: String? = null
) {
    val animatedProgress by animateFloatAsState(
        targetValue = (qualityScore / 100f).coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 150f),
        label = "qualityRing"
    )

    Box(
        modifier = modifier
            .size(ringSize)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(ringSize)) {
            val stroke = strokeWidth.toPx()
            val arcSize = Size(ringSize.toPx() - stroke, ringSize.toPx() - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            val sweep = 360f * animatedProgress

            // Track (Hintergrundring)
            drawArc(
                color = Color.White.copy(alpha = 0.08f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            // Farbiger Fortschrittsring mit Sweep-Verlauf (rot→grün)
            if (animatedProgress > 0f) {
                val startColor = positivityColor(0)
                val endColor = positivityColor(qualityScore)
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(startColor, endColor, startColor),
                        center = center
                    ),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )

                // Glanz am Ende des Bogens (heller Punkt)
                val endAngleRad = Math.toRadians((-90 + sweep).toDouble())
                val radius = arcSize.width / 2
                val glowX = center.x + (radius * Math.cos(endAngleRad)).toFloat()
                val glowY = center.y + (radius * Math.sin(endAngleRad)).toFloat()
                drawCircle(
                    color = endColor.copy(alpha = 0.6f),
                    radius = stroke * 0.9f,
                    center = Offset(glowX, glowY)
                )
            }
        }

        // Innen: Score + Label
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    qualityScore.toString(),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = positivityColor(qualityScore)
                )
                // AEVUM-3: „✎" bei manuell angepasster Tages-Güte.
                if (overrideBadge != null) {
                    Text(
                        overrideBadge,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
            Text(
                label,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
