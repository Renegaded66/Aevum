package com.d_drostes_apps.aevum.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.ui.theme.PmPalette

/**
 * M19.1: Der Einstieg ins Projektmanagement — der kleine Knopf in der Ecke.
 *
 * ANWENDUNGSFALL (wörtlich): „im vorhandenen Dashboard soll oben in einer Ecke
 * ein kleiner Button sein, der ein neues Fragment öffnet."
 *
 * DER KNOPF IST ZUGLEICH ANZEIGE: Auf dem gezeichneten Juwel sitzt ein Zähler
 * mit den NOCH OFFENEN Todos des Tages. Damit weiß Devon beim Öffnen der App
 * sofort, was noch aussteht, ohne irgendwo hineinzuklicken — und wenn der
 * Zähler verschwindet, ist der Tag sauber. Kein Level, keine Punkte: nur Arbeit,
 * die sichtbar weniger wird.
 *
 * Zustand im Detail:
 *  - offene Todos vorhanden → das Juwel atmet (langsamer Puls) und der Zähler
 *    ist da
 *  - nichts offen        → ruhiger Knopf ohne Zähler
 *  - alles erledigt (und der Tag hatte Todos) → goldener Rand
 */
@Composable
fun PmEntryButton(
    pendingCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hasPlan: Boolean = false,
    size: Dp = 42.dp
) {
    val transition = rememberInfiniteTransition(label = "pm-entry")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000),
            repeatMode = RepeatMode.Restart
        ),
        label = "pm-entry-pulse"
    )
    val allDone = hasPlan && pendingCount == 0
    val accent = if (allDone) PmPalette.complete else MaterialTheme.colorScheme.primary
    val counterScale by animateFloatAsState(
        targetValue = if (pendingCount > 0) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.45f),
        label = "pm-entry-badge"
    )

    Box(modifier = modifier.size(size + 8.dp), contentAlignment = Alignment.Center) {
        val interaction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.90f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
                        )
                    )
                )
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClickLabel = "Projektmanagement öffnen",
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            // Achtung: Farblogik MUSS vor dem Canvas gelesen werden —
            // MaterialTheme ist @Composable und im Zeichenblock nicht erlaubt.
            val gemColor = if (allDone) PmPalette.complete else MaterialTheme.colorScheme.primary
            Canvas(modifier = Modifier.size(size)) {
                val r = this.size.minDimension / 2f
                val center = Offset(r, r)
                // Rand (atmet, solange etwas offen ist)
                drawCircle(
                    color = accent.copy(alpha = if (pendingCount > 0) 0.25f + 0.35f * (1f - pulse) else 0.35f),
                    radius = r - 1f,
                    center = center,
                    style = Stroke(width = 1.6f)
                )
                // Juwel
                drawPmGem(
                    center = center,
                    radius = r * 0.56f,
                    color = gemColor,
                    rotation = -8f,
                    glow = if (allDone) 0.7f else 0.15f
                )
                // Funke auf der Tafel
                drawLine(
                    color = Color.White.copy(alpha = 0.75f),
                    start = center + Offset(-r * 0.16f, -r * 0.30f),
                    end = center + Offset(r * 0.02f, -r * 0.42f),
                    strokeWidth = 1.4f,
                    cap = StrokeCap.Round
                )
            }
        }

        if (counterScale > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size((size.value * 0.46f).dp.coerceAtLeast(16.dp))
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.tertiary,
                                PmPalette.rollover
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (pendingCount > 99) "99+" else pendingCount.toString(),
                    fontSize = if (pendingCount > 99) 8.sp else 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2A1D00),
                    modifier = Modifier.padding(horizontal = 1.dp)
                )
            }
        }
    }
}
