package com.d_drostes_apps.aevum.ui.screens.placetimeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.domain.placetimeline.PlaceVisit
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * M18.83.2: Karten-Ansicht der Orts-Timeline — stilisierte Karte OHNE
 * Google-Maps-SDK (Offline-Prinzip, kein API-Key): Equirectangular-Projektion
 * im lokalen Maßstab, gestrichelte Streckenlinien in chronologischer
 * Reihenfolge, nummerierte Punkte pro Visit (Besuchsreihenfolge), Kompass-N +
 * Maßstabs-Hinweis. Die "Zeitachse mit Strecken"-Metapher von Google Maps.
 *
 * Viewport auto-fittet auf die Visit-Bbox (+15% Padding); bei einem einzigen
 * Punkt (alle Visits am selben Ort) zeigt er die Marke zentriert mit
 * Mindest-Spannung, statt sinnlos zu zoomen.
 */
@Composable
fun PlaceMapCard(
    visits: List<PlaceVisit>,
    modifier: Modifier = Modifier
) {
    val mappable = visits.filter { it.latitude != null && it.longitude != null }
    if (mappable.isEmpty()) return

    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val lineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

    val minLat = mappable.minOf { it.latitude!! }
    val maxLat = mappable.maxOf { it.latitude!! }
    val minLon = mappable.minOf { it.longitude!! }
    val maxLon = mappable.maxOf { it.longitude!! }
    val latSpan = max(maxLat - minLat, 0.002)  // ~220 m Mindest-Spanne
    val lonSpan = max(maxLon - minLon, 0.002)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(AevumRadius.md))
            .background(surface)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val padPx = 52.dp.toPx()  // Platz für Punkt-Labels + Nummern
            val plotW = w - 2 * padPx
            val plotH = h - 2 * padPx
            if (plotW <= 0f || plotH <= 0f) return@Canvas

            val latPad = latSpan * 0.15
            val lonPad = lonSpan * 0.15
            fun project(lat: Double, lon: Double): Offset {
                val fx = ((lon - (minLon - lonPad)) / (lonSpan + 2 * lonPad)).toFloat()
                val fy = ((lat - (minLat - latPad)) / (latSpan + 2 * latPad)).toFloat()
                return Offset(padPx + fx * plotW, padPx + (1f - fy) * plotH)
            }

            // Dezentes Gitter (Kartengefühl).
            val gridStep = plotW / 4
            for (i in 1..3) {
                drawLine(
                    color = gridColor,
                    start = Offset(padPx + i * gridStep, padPx),
                    end = Offset(padPx + i * gridStep, padPx + plotH),
                    strokeWidth = 1f
                )
                drawLine(
                    color = gridColor,
                    start = Offset(padPx, padPx + i * (plotH / 4)),
                    end = Offset(padPx + plotW, padPx + i * (plotH / 4)),
                    strokeWidth = 1f
                )
            }

            // Kompass-N oben rechts.
            drawCompassN(topRight = Offset(w - 26.dp.toPx(), 26.dp.toPx()), color = labelColor, textMeasurer = textMeasurer)

            // Strecken in chronologischer Reihenfolge (gestrichelt, Ort-Farbe
            // des STARTS der Strecke; Übergänge sind "unterwegs", keine Ort-
            // Evidenz → bewusst nicht als eigene Marke).
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            for (i in 0 until mappable.size - 1) {
                val a = mappable[i]
                val b = mappable[i + 1]
                if (a.latitude == b.latitude && a.longitude == b.longitude) continue
                val color = parseHexColorOrNull(a.color)?.copy(alpha = 0.85f) ?: lineColor
                drawLine(
                    color = color,
                    start = project(a.latitude!!, a.longitude!!),
                    end = project(b.latitude!!, b.longitude!!),
                    strokeWidth = 2.5f,
                    pathEffect = dash,
                    cap = StrokeCap.Round
                )
            }

            // Punkte: chronologisch nummeriert; mehrfach-Besuche am selben Ort
            // teilen sich einen Punkt (die letzte Nummer zählt optisch).
            mappable.forEachIndexed { index, visit ->
                val center = project(visit.latitude!!, visit.longitude!!)
                val color = parseHexColorOrNull(visit.color) ?: MaterialThemeUnknownFallback
                val radius = 9.dp.toPx()
                drawCircle(color = color.copy(alpha = 0.22f), radius = radius + 5f, center = center)
                drawCircle(color = color, radius = radius, center = center)
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = radius - 2f,
                    center = center,
                    style = Stroke(width = 1.5f)
                )
                val label = (index + 1).toString()
                val layout = textMeasurer.measure(
                    label,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
                drawText(
                    layout,
                    topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f)
                )
                // Name klein unter dem Punkt (nur einmal pro Ort).
                val isFirstOfPlace = mappable.indexOfFirst { it.name == visit.name } == index
                if (isFirstOfPlace) {
                    val nameLayout = textMeasurer.measure(
                        visit.name,
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 9.sp,
                            color = labelColor
                        ),
                        maxLines = 1
                    )
                    drawText(
                        nameLayout,
                        topLeft = Offset(
                            center.x - nameLayout.size.width / 2f,
                            center.y + radius + 4f
                        )
                    )
                }
            }
        }
    }
}

private val MaterialThemeUnknownFallback = Color(0xFF6366F1)

private fun DrawScope.drawCompassN(
    topRight: Offset,
    color: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    val layout = textMeasurer.measure(
        "N",
        style = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
    )
    // Anime-Pfeil: kleine Linie nach oben + "N".
    drawLine(
        color = color,
        start = Offset(topRight.x + layout.size.width / 2f, topRight.y + 16.dp.toPx()),
        end = Offset(topRight.x + layout.size.width / 2f, topRight.y + 2.dp.toPx()),
        strokeWidth = 2f,
        cap = StrokeCap.Round
    )
    drawText(layout, topLeft = Offset(topRight.x - 2.dp.toPx(), topRight.y + 18.dp.toPx()))
}