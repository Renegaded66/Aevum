package com.d_drostes_apps.aevum.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * M18.93v6 FLIP-CLOCK: Split-Flap-Ziffer wie bei einer analogen Flip-Clock.
 *
 * Geometrie: Die Karte ist zwei flush Hälften mit Falzlinie. Das Zeichen
 * wird GANZ zentriert über die volle Kartenhöhe gedacht und in ECHTE
 * Hälften geclippt:
 *   - obere Box zeigt Glyphen-Zeilen [glyphTop .. glyphTop + halfH)
 *   - untere Box zeigt Glyphen-Zeilen [glyphTop + halfH .. glyphTop + H)
 * (v5-Fehler behoben: dort war der Y-Offset falsch — beide Boxen zeigten
 * die Glyphen-Mitte, was wie zwei übereinanderliegende Textfelder aussah.)
 *
 * Ablauf beim Zeichenwechsel (Animatable, 2×115ms):
 *   Phase 1: untere Klappe rotiert um die Falz 0°→-90° (zeigt altes Zeichen)
 *   Halbzzeit: current = neu
 *   Phase 2: untere Klappe +90°→0° (zeigt neues Zeichen)
 *
 * v5-Bug "klappt nur einmal": Der alte Fortschritts-Poller war keine
 * Schleife und setzte flipping nie zurück. Jetzt fährt ein einzelner
 * Animatable-Treiber die zwei Phasen deterministisch; eintreffende
 * Sekunden-Wechsels werden nach dem laufenden Flip abgearbeitet
 * (Warteschleife, kein verlorener Tick).
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
    val phase = remember { Animatable(0f) }

    // Eingang: wartet, bis ein laufender Flip fertig ist, dann startet er.
    LaunchedEffect(digit) {
        if (digit == current) return@LaunchedEffect
        while (flipping) delay(16)
        pending = digit
        flipping = true
    }
    // Flip-Treiber: Phase 1 hoch → swap → Phase 2 runter → fertig.
    LaunchedEffect(flipping) {
        if (!flipping) return@LaunchedEffect
        phase.snapTo(0f)
        phase.animateTo(1f, tween(115))
        current = pending ?: current
        phase.snapTo(0f)
        phase.animateTo(1f, tween(115))
        current = pending ?: current
        pending = null
        flipping = false
        phase.snapTo(0f)
    }

    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = height.value.sp * 0.72f
    )
    val textMeasurer = rememberTextMeasurer()
    val cardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    val textColor = MaterialTheme.colorScheme.onSurface
    val halfH = height / 2

    // rotationX der unteren Klappe: Phase 1 (phase 0→1) = 0→-90 (hoch, alt
    // sichtbar), Phase 2 (phase wieder 0→1) = +90→0 (runter, neu sichtbar).
    // Der Treiber setzt vor Phase 2 snapTo(0) — die Klappe "erscheint" bei
    // +90° (Rückseite) und fällt auf 0°. Zeichenwechsel unten: erst nach
    // Phase 1 (Klappe ist an der Falz, ~unsichtbar), also lowerChar einfach
    // = current NACH dem Swap; während Phase 1 zeigt die Klappe das alte
    // Zeichen (current ist noch alt), während Phase 2 das neue.
    val lowerAngle = when {
        !flipping -> 0f
        else -> -phase.value * 90f
    }
    val lowerChar = current

    Column(modifier = modifier) {
        // ── Obere Hälfte: OBERE Hälfte des aktuellen Zeichens ──
        Box(
            modifier = Modifier
                .width(width)
                .height(halfH)
                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                .background(
                    Brush.verticalGradient(listOf(cardColor, cardColor.copy(alpha = 0.5f)))
                ),
            contentAlignment = Alignment.TopCenter
        ) {
            Canvas(modifier = Modifier.width(width).height(halfH)) {
                val layout = textMeasurer.measure(current.toString(), textStyle)
                // Glyph zentriert über die VOLLKARTE platzieren; diese Box
                // clippt automatisch auf die obere Hälfte.
                val glyphTop = (size.height * 2f - layout.size.height) / 2f
                drawText(
                    textLayoutResult = layout,
                    color = textColor,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        (size.width - layout.size.width) / 2f,
                        glyphTop
                    )
                )
            }
        }
        // ── Untere Hälfte (die Klappe) ──
        Box(
            modifier = Modifier
                .width(width)
                .height(halfH)
                .graphicsLayer {
                    cameraDistance = 14f * density
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
            Canvas(modifier = Modifier.width(width).height(halfH)) {
                val layout = textMeasurer.measure(lowerChar.toString(), textStyle)
                // Gleiche Glyph-Position wie oben, aber um halfH nach oben
                // verschoben — sichtbar ist die UNTERE Glyph-Hälfte.
                val glyphTop = (size.height * 2f - layout.size.height) / 2f - size.height
                drawText(
                    textLayoutResult = layout,
                    color = textColor,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        (size.width - layout.size.width) / 2f,
                        glyphTop
                    )
                )
            }
        }
        // Falzlinie (Flip-Clock-Charakter).
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
                Text(
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