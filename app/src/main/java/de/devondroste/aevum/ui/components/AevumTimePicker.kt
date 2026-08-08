package de.devondroste.aevum.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.devondroste.aevum.ui.theme.AevumRadius
import de.devondroste.aevum.ui.theme.AevumSpacing
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * AevumTimePicker — Custom-designed Time Picker im Aevum-Stil.
 *
 * Kein Android-Material-TimePicker, sondern eine eigene Visualisierung:
 * - 24-Stunden-Analog-Uhr mit Minuten-Ring
 * - Snap auf 5 Minuten
 * - Zwei Modi: Start (Sonnengelb) / Ende (Sekundärfarbe)
 * - Große, lesbare HH:MM-Anzeige darunter
 *
 * Der User kann:
 * - Den Stundenzeiger per Drag setzen
 * - Auf den Minuten-Ring tippen/draggen, um Minuten zu wählen
 * - Mit den Tasten +/− feinjustieren
 */
@Composable
fun AevumTimePicker(
    modifier: Modifier = Modifier,
    initialHour: Int,
    initialMinute: Int,
    accent: Color = MaterialTheme.colorScheme.primary,
    onTimeChange: (hour: Int, minute: Int) -> Unit,
    label: String? = null,
    showDigitalDisplay: Boolean = true
) {
    // M18.48 (User: "die Stunde einzutragen funktioniert nicht richtig" /
    // "start und zielzeit im Popup wählen"): Einmalige Initialisierung statt
    // remember(initialHour, initialMinute)-Key. Der alte Key setzte den
    // lokalen Zustand bei JEDER externen Änderung zurück: onTimeChange →
    // Parent aktualisiert den Form-State → initialHour/Minute ändern sich →
    // remember-Key ist neu → Stunde/Minute springen zurück und der Drag bricht
    // ab. Jetzt wird nur beim ersten Compose initialisiert; Drags bleiben
    // stabil und jede Nutzeränderung feuert exakt einen onTimeChange.
    var hour by remember { mutableStateOf(initialHour.coerceIn(0, 23)) }
    var minute by remember { mutableStateOf(initialMinute.coerceIn(0, 59)) }
    var isMinuteMode by remember { mutableStateOf(false) }
    val rotationHour = remember { Animatable(hourToDeg24(initialHour.coerceIn(0, 23))) }
    val rotationMinute = remember { Animatable(minuteToDeg(initialMinute.coerceIn(0, 59))) }

    // Animation und Änderungsausgabe sind bewusst getrennt: genau ein Callback
    // pro sichtbarem HH:MM-Zustand, statt je ein Callback für Stunde und Minute.
    var lastEmitted by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(hour) {
        rotationHour.animateTo(hourToDeg24(hour), spring(stiffness = 200f))
    }
    LaunchedEffect(minute) {
        rotationMinute.animateTo(minuteToDeg(minute), spring(stiffness = 200f))
    }
    LaunchedEffect(hour, minute) {
        val value = hour to minute
        if (lastEmitted != value) {
            lastEmitted = value
            onTimeChange(hour, minute)
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (label != null) {
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.height(AevumSpacing.sm))
        }

        // Digitale Anzeige
        if (showDigitalDisplay) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
            ) {
                TimeDigitCluster(
                    value = hour,
                    accent = if (!isMinuteMode) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f),
                    onClick = { isMinuteMode = false }
                )
                Text(
                    ":",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TimeDigitCluster(
                    value = minute,
                    accent = if (isMinuteMode) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f),
                    onClick = { isMinuteMode = true }
                )
            }
            Spacer(Modifier.height(AevumSpacing.md))
        }

        // Mode-Switch (Stunde / Minute)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(AevumRadius.full))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ModeChip("Stunde", !isMinuteMode) { isMinuteMode = false }
            ModeChip("Minute", isMinuteMode) { isMinuteMode = true }
        }

        Spacer(Modifier.height(AevumSpacing.md))

        // Clock face
        BoxWithConstraints(
            modifier = Modifier
                .size(220.dp)
                .pointerInput(Unit) {
                    // M18.49 (User: "Ich muss genau den Zeiger treffen um ihn
                    // zu verschieben. Und der ist so Mini"): Tap-to-set statt
                    // Zeiger-Jagd. Beim Drücken springt der Zeiger SOFORT an
                    // die angetippte Position (kein Treffen nötig). Beim
                    // Loslassen wechselt der Picker automatisch in den
                    // Minuten-Modus (Google-Kalender-Prinzip: erst Stunde
                    // wählen, dann Minute).
                    detectDragGestures(
                        onDragStart = { pos ->
                            val (cx, cy) = centerPx(size)
                            val dx = pos.x - cx
                            val dy = pos.y - cy
                            val angle = ((atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI) + 90.0 + 360.0) % 360.0
                            if (isMinuteMode) {
                                val newMin = snapMinute(((angle / 360.0) * 60.0).toInt())
                                if (newMin != minute) minute = newMin
                            } else {
                                val newHour = snapHour(((angle / 360.0) * 24.0).toInt())
                                if (newHour != hour) hour = newHour
                            }
                        },
                        onDrag = { change, _ ->
                            val (cx, cy) = centerPx(size)
                            val dx = change.position.x - cx
                            val dy = change.position.y - cy
                            val angle = ((atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI) + 90.0 + 360.0) % 360.0
                            if (isMinuteMode) {
                                val newMin = snapMinute(((angle / 360.0) * 60.0).toInt())
                                if (newMin != minute) minute = newMin
                            } else {
                                val newHour = snapHour(((angle / 360.0) * 24.0).toInt())
                                if (newHour != hour) hour = newHour
                            }
                        },
                        onDragEnd = {
                            // Loslassen: vom Stunden- in den Minuten-Modus.
                            if (!isMinuteMode) isMinuteMode = true
                        },
                        onDragCancel = {
                            if (!isMinuteMode) isMinuteMode = true
                        }
                    )
                }
                .pointerInput(Unit) {
                    // M18.49: Tap-to-set. Ein Tap setzt die gewählte Einheit
                    // an der angetippten Position und wechselt danach in den
                    // Minuten-Modus — der Zeiger muss nie getroffen werden.
                    detectTapGestures { pos ->
                        val (cx, cy) = centerPx(size)
                        val dx = pos.x - cx
                        val dy = pos.y - cy
                        val angle = ((atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI) + 90.0 + 360.0) % 360.0
                        if (isMinuteMode) {
                            val newMin = snapMinute(((angle / 360.0) * 60.0).toInt())
                            if (newMin != minute) minute = newMin
                        } else {
                            val newHour = snapHour(((angle / 360.0) * 24.0).toInt())
                            if (newHour != hour) hour = newHour
                            // Nach dem Tippen sofort Minuten wählen können.
                            isMinuteMode = true
                        }
                    }
                }
        ) {
            ClockFaceCanvas(
                hour = hour,
                minute = minute,
                isMinuteMode = isMinuteMode,
                accent = accent,
                hourRotation = rotationHour.value,
                minuteRotation = rotationMinute.value
            )
        }

        Spacer(Modifier.height(AevumSpacing.md))

        // Bump-Buttons für Feinjustierung
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BumpButton("−1h") { hour = ((hour + 23) % 24) }
            BumpButton("−5") { minute = ((minute + 55) % 60) }
            BumpButton("+5") { minute = ((minute + 5) % 60) }
            BumpButton("+1h") { hour = ((hour + 1) % 24) }
        }
    }
}

@Composable
private fun TimeDigitCluster(value: Int, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AevumRadius.md))
            .background(accent.copy(alpha = 0.10f))
            .padding(horizontal = AevumSpacing.sm, vertical = AevumSpacing.xs)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "%02d".format(value),
            fontSize = 38.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = accent
        )
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AevumRadius.full))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .padding(horizontal = AevumSpacing.md, vertical = 6.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun BumpButton(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { onClick() })
        },
        shape = RoundedCornerShape(AevumRadius.full),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = AevumSpacing.md, vertical = 8.dp),
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ClockFaceCanvas(
    hour: Int,
    minute: Int,
    isMinuteMode: Boolean,
    accent: Color,
    hourRotation: Float,
    minuteRotation: Float
) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
    val tickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
    val numberColor = MaterialTheme.colorScheme.onSurface
    val secondaryAccent = MaterialTheme.colorScheme.secondary
    val surfaceColor = MaterialTheme.colorScheme.surface

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val outerR = size.minDimension / 2f - 4.dp.toPx()

            // Outer minute ring
            drawCircle(
                color = trackColor,
                radius = outerR,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Hour ticks
            for (i in 0 until 24) {
                val angle = (i / 24f) * 2.0 * PI
                val tickInner = outerR - (if (i % 6 == 0) 18.dp.toPx() else 10.dp.toPx())
                val tx = cx + (cos(angle) * tickInner).toFloat()
                val ty = cy + (sin(angle) * tickInner).toFloat()
                val txEnd = cx + (cos(angle) * outerR).toFloat()
                val tyEnd = cy + (sin(angle) * outerR).toFloat()
                drawLine(
                    color = if (i % 6 == 0) tickColor else tickColor.copy(alpha = 0.30f),
                    start = Offset(tx, ty),
                    end = Offset(txEnd, tyEnd),
                    strokeWidth = if (i % 6 == 0) 2.dp.toPx() else 1.dp.toPx()
                )
            }

            // Hour numbers (0, 6, 12, 18) — drawn via native canvas
            val numberRadius = outerR - 36.dp.toPx()
            val nativeCanvas = drawContext.canvas.nativeCanvas
            val textPaint = android.graphics.Paint().apply {
                color = numberColor.copy(alpha = 0.62f).toArgb()
                textSize = 12.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL)
            }
            for (i in 0 until 24 step 6) {
                val angle = (i / 24f) * 2.0 * PI - PI / 2.0
                val nx = cx + (cos(angle) * numberRadius).toFloat()
                val ny = cy + (sin(angle) * numberRadius).toFloat() + 6.dp.toPx()
                nativeCanvas.drawText("%02d".format(i), nx, ny, textPaint)
            }

            // Hour hand (rotated)
            rotate(degrees = hourRotation, pivot = Offset(cx, cy)) {
                drawLine(
                    color = if (isMinuteMode) tickColor.copy(alpha = 0.42f) else accent,
                    start = Offset(cx, cy),
                    end = Offset(cx, cy - outerR * 0.55f),
                    strokeWidth = 4.dp.toPx()
                )
                drawCircle(
                    color = if (isMinuteMode) tickColor.copy(alpha = 0.42f) else accent,
                    radius = 6.dp.toPx(),
                    center = Offset(cx, cy)
                )
            }

            // Minute hand (rotated)
            rotate(degrees = minuteRotation, pivot = Offset(cx, cy)) {
                val minuteLen = outerR * (if (isMinuteMode) 0.78f else 0.42f)
                drawLine(
                    color = if (isMinuteMode) secondaryAccent else tickColor.copy(alpha = 0.42f),
                    start = Offset(cx, cy),
                    end = Offset(cx, cy - minuteLen),
                    strokeWidth = if (isMinuteMode) 3.dp.toPx() else 2.dp.toPx()
                )
            }

            // Center cap
            drawCircle(
                color = surfaceColor,
                radius = 4.dp.toPx(),
                center = Offset(cx, cy)
            )
        }
    }
}

private fun centerPx(size: androidx.compose.ui.unit.IntSize): Pair<Float, Float> {
    return Pair(size.width / 2f, size.height / 2f)
}

private fun hourToDeg24(hour: Int): Float = (hour / 24f) * 360f
private fun minuteToDeg(minute: Int): Float = (minute / 60f) * 360f
private fun snapMinute(raw: Int): Int {
    val snapped = (raw / 5) * 5
    return when {
        snapped < 0 -> 55
        snapped >= 60 -> 0
        else -> snapped
    }
}
private fun snapHour(raw: Int): Int {
    val snapped = raw % 24
    return if (snapped < 0) snapped + 24 else snapped
}
