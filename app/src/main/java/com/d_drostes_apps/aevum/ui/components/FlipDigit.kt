package com.d_drostes_apps.aevum.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * M18.93v5 FLIP-CLOCK: Split-Flap-Ziffer wie bei einer analogen Flip-Clock
 * (Bahn-Abfahrtsanzeige / klassischer Wecker).
 *
 * Aufbau (die Karte ist ZWEI Hälften mit Falzlinie in der Mitte):
 *
 *   obere Hälfte: zeigt OBERE Hälfte des aktuellen Zeichens (statisch)
 *   untere Hälfte: zeigt UNTERE Hälfte des aktuellen Zeichens
 *
 * Beim Zeichenwechsel (2 Phasen, je 110ms):
 *   Phase 1: die UNTERE Klappe klappt nach oben (rotationX 0→-90°) — sie
 *            zeigt dabei die untere Hälfte des ALTEN Zeichens.
 *   Halbzzeit: current = neu.
 *   Phase 2: die untere Hälfte zeigt das NEUE Zeichen und klappt von +90°
 *            zurück auf 0° (aus der Falz heraus).
 *
 * Das Zeichen wird per TextMeasurer in ECHTE Hälften gerendert (Canvas
 * drawText mit Clip) — keine Text-Translations-Tricks, keine Glyph-
 * Metrik-Rätsel. Kamera-Perspektive via graphicsLayer(cameraDistance).
 *
 * Hinterfragung der Alternative: AnimatedContent-Slide wäre 10 Zeilen —
 * aber das ist ein SLIDE, kein physisches Umklappen. Der User will
 * explizit die analoge Klapp-Optik; graphicsLayer-rotationX um die
 * Falzlinie ist die einzig richtige Geometrie.
 */
@Composable
fun FlipDigit(
    digit: Char,
    modifier: Modifier = Modifier,
    width: Dp = 24.dp,
    height: Dp = 36.dp,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    var current by remember { mutableStateOf(digit) }
    var flipping by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Char?>(null) }

    // 0f → 1f über beide Phasen (220ms gesamt).
    val progress by animateFloatAsState(
        targetValue = if (flipping) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "flipProgress"
    )

    LaunchedEffect(digit) {
        if (digit != current && !flipping) {
            pending = digit
            flipping = true
        }
    }
    LaunchedEffect(flipping) {
        if (!flipping) return@LaunchedEffect
        // Halbzzeit-Swap: wenn die Klappe die Falzlinie passiert (90°),
        // wird das verdeckte Zeichen sichtbar geschaltet.
        if (progress >= 0.5f && current != pending) {
            current = pending ?: current
        }
        if (progress >= 1f) {
            current = pending ?: current
            pending = null
            flipping = false
        }
    }

    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = height.value.sp * 0.82f
    )
    val textMeasurer = rememberTextMeasurer()
    val cardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    val textColor = MaterialTheme.colorScheme.onSurface
    val halfH = height / 2

    // Phase bestimmen: 0..0.5 = untere Klappe hoch (alt), 0.5..1 = untere
    // Klappe runter (neu). rotationX der klappenden Hälfte.
    val lowerAngle = when {
        !flipping -> 0f
        progress < 0.5f -> -(progress / 0.5f) * 90f   // 0 → -90 (klappt hoch)
        else -> (1f - (progress - 0.5f) / 0.5f) * 90f // +90 → 0 (klappt runter)
    }
    val showNewOnLower = flipping && progress >= 0.5f
    val lowerChar = if (showNewOnLower) (pending ?: current) else current

    Column(modifier = modifier) {
        // ── Obere Hälfte (statisch — zeigt die obere Hälfte von current) ──
        Box(
            modifier = Modifier
                .width(width)
                .height(halfH)
                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(cardColor, cardColor.copy(alpha = 0.5f))
                    )
                ),
            contentAlignment = Alignment.TopCenter
        ) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier.width(width).height(halfH)
            ) {
                val layout = textMeasurer.measure(current.toString(), textStyle)
                // Zeichen vertikal so platzieren, dass die OBERE Hälfte der
                // Glyphe in dieser Box sichtbar ist (Baseline unter der Mitte).
                drawText(
                    textLayoutResult = layout,
                    color = textColor,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        (size.width - layout.size.width) / 2f,
                        -layout.size.height / 2f + size.height * 0.5f
                    )
                )
            }
        }
        // ── Untere Hälfte (klappt) ──
        Box(
            modifier = Modifier
                .width(width)
                .height(halfH)
                .graphicsLayer {
                    cameraDistance = 12f * density
                    // Falzlinie als Transform-Origin (oben Mitte der Box).
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                    rotationX = lowerAngle
                }
                .clip(RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(cardColor.copy(alpha = 0.5f), cardColor)
                    )
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier.width(width).height(halfH)
            ) {
                val layout = textMeasurer.measure(lowerChar.toString(), textStyle)
                drawText(
                    textLayoutResult = layout,
                    color = textColor,
                    // Untere Hälfte der Glyphe: Text um die halbe Höhe nach oben.
                    topLeft = androidx.compose.ui.geometry.Offset(
                        (size.width - layout.size.width) / 2f,
                        -layout.size.height / 2f
                    )
                )
            }
        }
        // ── Falzlinie (dezente Trennung — der Flip-Clock-Charakter) ──
        Box(
            modifier = Modifier
                .width(width)
                .height(1.dp)
                .background(accent.copy(alpha = 0.25f))
        )
    }
}
/**
 * Flip-Time-Text: "HH:MM:SS" als Flip-Clock-Zeile. Sekunden ändern sich
 * jede Sekunde — nur die geänderte Ziffer klappt (nicht der ganze Text).
 */
@Composable
fun FlipTimeText(
    timeText: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        timeText.forEachIndexed { index, ch ->
            if (ch == ':') {
                // Doppelpunkt statisch (dezent, blinkt nicht — ruhige Optik).
                androidx.compose.material3.Text(
                    ":",
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = accent.copy(alpha = 0.7f),
                    modifier = Modifier.width(8.dp)
                )
            } else {
                FlipDigit(
                    digit = ch,
                    width = 22.dp,
                    height = 34.dp,
                    accent = accent
                )
                if (index < timeText.length - 1) {
                    Box(modifier = Modifier.width(2.dp))
                }
            }
        }
    }
}
