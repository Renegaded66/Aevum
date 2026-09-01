package com.d_drostes_apps.aevum.ui.screens.automation

import android.content.Context
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.ui.screens.placetimeline.parseHexAndroidColor
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.cos
import kotlin.math.sin

/**
 * M18.93 — Geteilter Map-Core für die Geofence-Karte: Wird von der INLINE-
 * Karte (Geofence-Liste, kompakt) UND der Fullscreen-Karte genutzt.
 *
 * Zuständig für:
 *  - Zonen-Kreise: EIN gemergtes GeoJSON + gefilterte aktiv/inaktiv-Layer
 *  - Pins: Emoji-Tropfen in Geofence-Farbe (inaktiv halbtransparent + Pause)
 *  - Standort-Pin: 🧍 auf weißer Scheibe mit 2-Beat-Puls (updateMarker)
 *
 * Alle Funktionen arbeiten auf einem bereits geladenen Style und sind
 * idempotent (Rebuild entfernt alte Layer/Marker zuerst).
 */
object GeofenceMapCore {

    private const val ZONE_SOURCE_ID = "aevum-geofence-zones-src"
    private const val ZONE_FILL_ON_LAYER_ID = "aevum-geofence-zones-fill-on"
    private const val ZONE_FILL_OFF_LAYER_ID = "aevum-geofence-zones-fill-off"
    private const val ZONE_OUTLINE_LAYER_ID = "aevum-geofence-zones-outline"
    private const val CIRCLE_POINTS = 48

    /**
     * Rebuild-Handler: baut Kreise + Geofence-Pins neu und gibt die
     * Marker-Map zurück (geofenceId → Marker). Der User-Marker wird NICHT
     * angerührt (er pulsiert in einem eigenen Effect des Screens).
     */
    fun buildGeofenceMarkers(
        map: org.maplibre.android.maps.MapLibreMap,
        context: Context,
        geofences: List<PlaceGeofence>,
        markersSink: MutableMap<String, Marker>
    ) {
        val style = map.style ?: return
        markersSink.values.forEach { map.removeMarker(it) }
        markersSink.clear()
        rebuildZoneCircles(style, geofences)
        val iconFactory = IconFactory.getInstance(context)
        geofences.forEach { gf ->
            val marker = map.addMarker(
                MarkerOptions()
                    .position(org.maplibre.android.geometry.LatLng(gf.latitude, gf.longitude))
                    .title(gf.name.ifBlank { "\uD83D\uDCCD" })
                    .snippet("${gf.radiusMeters.toInt()} m")
                    .icon(geofencePinIcon(iconFactory, context, gf))
            )
            markersSink[gf.id] = marker
        }
    }

    /** Radius-Kreise als EIN gemergtes GeoJSON mit Farb-Property. */
    fun rebuildZoneCircles(style: org.maplibre.android.maps.Style, geofences: List<PlaceGeofence>) {
        style.removeLayer(ZONE_FILL_ON_LAYER_ID)
        style.removeLayer(ZONE_FILL_OFF_LAYER_ID)
        style.removeLayer(ZONE_OUTLINE_LAYER_ID)
        style.removeSource(ZONE_SOURCE_ID)
        if (geofences.isEmpty()) return

        val features = geofences.joinToString(",") { gf ->
            val coords = buildCircleCoords(gf.latitude, gf.longitude, gf.radiusMeters.toDouble())
            val coordStr = coords.joinToString(",") { "[${"%.7f".format(it.first)},${"%.7f".format(it.second)}]" }
            val color = gf.color.ifBlank { "#6366F1" }
            """{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$coordStr]]},
                "properties":{"color":"$color","enabled":${gf.enabled}}}"""
        }
        style.addSource(GeoJsonSource(ZONE_SOURCE_ID, """{"type":"FeatureCollection","features":[$features]}"""))

        val enabledFilter = Expression.eq(Expression.get("enabled"), Expression.literal(true))
        val disabledFilter = Expression.eq(Expression.get("enabled"), Expression.literal(false))
        val colorExpr = Expression.toColor(Expression.get("color"))

        style.addLayer(
            FillLayer(ZONE_FILL_ON_LAYER_ID, ZONE_SOURCE_ID).apply {
                withFilter(enabledFilter)
                withProperties(
                    PropertyFactory.fillColor(colorExpr),
                    PropertyFactory.fillOpacity(0.14f),
                    PropertyFactory.fillOutlineColor(colorExpr)
                )
            }
        )
        style.addLayer(
            FillLayer(ZONE_FILL_OFF_LAYER_ID, ZONE_SOURCE_ID).apply {
                withFilter(disabledFilter)
                withProperties(
                    PropertyFactory.fillColor(colorExpr),
                    PropertyFactory.fillOpacity(0.04f),
                    PropertyFactory.fillOutlineColor(colorExpr)
                )
            }
        )
        style.addLayer(
            LineLayer(ZONE_OUTLINE_LAYER_ID, ZONE_SOURCE_ID).apply {
                withFilter(enabledFilter)
                withProperties(
                    PropertyFactory.lineColor(colorExpr),
                    PropertyFactory.lineWidth(1.6f),
                    PropertyFactory.lineOpacity(0.55f)
                )
            }
        )
    }

    /** Meter→Grad-Kreis (Kosinus-Korrektur), Polygon geschlossen. */
    private fun buildCircleCoords(lat: Double, lon: Double, radiusM: Double): List<Pair<Double, Double>> {
        val earthR = 6378137.0
        val latRad = Math.toRadians(lat)
        val pts = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until CIRCLE_POINTS) {
            val a = 2.0 * Math.PI * i / CIRCLE_POINTS
            val dLat = (radiusM * sin(a) / earthR) * (180.0 / Math.PI)
            val dLon = (radiusM * cos(a) / (earthR * cos(latRad))) * (180.0 / Math.PI)
            pts.add(lon + dLon to lat + dLat)
        }
        pts.add(pts[0])
        return pts
    }

    /** Emoji-Tropfen-Pin in Geofence-Farbe (inaktiv: alpha + Pause-Badge). */
    fun geofencePinIcon(iconFactory: IconFactory, context: Context, gf: PlaceGeofence): Icon {
        val density = context.resources.displayMetrics.density
        val wPx = (52 * density).toInt()
        val hPx = (62 * density).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(wPx, hPx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val base = parseHexAndroidColor(gf.color.ifBlank { "#6366F1" })
        val darker = shadeColorInt(base, 0.68f)
        val active = gf.enabled
        val alphaMul = if (active) 1.0f else 0.55f

        val cx = wPx / 2f
        val bubbleCy = hPx * 0.36f
        val bubbleR = wPx * 0.40f

        // Weicher Bodenschatten ("schwebender" Pin).
        canvas.drawCircle(cx, hPx * 0.90f, bubbleR * 0.42f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x30 shl 24
        })

        // Pin-Spitze.
        val tip = android.graphics.Path().apply {
            moveTo(cx, hPx * 0.92f)
            lineTo(cx - bubbleR * 0.60f, bubbleCy + bubbleR * 0.78f)
            lineTo(cx + bubbleR * 0.60f, bubbleCy + bubbleR * 0.78f)
            close()
        }
        canvas.drawPath(tip, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = withAlphaMul(darker, alphaMul) })

        // Weißer Ring + Farbfläche.
        canvas.drawCircle(cx, bubbleCy, bubbleR, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlphaMul(android.graphics.Color.WHITE, alphaMul)
        })
        canvas.drawCircle(cx, bubbleCy, bubbleR - 2f * density, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlphaMul(base, alphaMul)
        })

        // Emoji zentriert.
        val emojiPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = bubbleR * 0.92f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val baseline = bubbleCy - (emojiPaint.descent() + emojiPaint.ascent()) / 2f
        canvas.drawText(gf.icon.ifBlank { "\uD83D\uDCCD" }, cx, baseline, emojiPaint)

        // Inaktiv: dezente Pause-Markierung oben rechts.
        if (!active) {
            val badgeR = wPx * 0.14f
            val bcx = cx + bubbleR * 0.76f
            val bcy = bubbleCy - bubbleR * 0.70f
            canvas.drawCircle(bcx, bcy, badgeR + 1.2f * density, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
            })
            canvas.drawCircle(bcx, bcy, badgeR, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#94A3B8")
            })
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                strokeWidth = badgeR * 0.38f
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            canvas.drawLine(bcx - badgeR * 0.28f, bcy - badgeR * 0.42f, bcx - badgeR * 0.28f, bcy + badgeR * 0.42f, p)
            canvas.drawLine(bcx + badgeR * 0.28f, bcy - badgeR * 0.42f, bcx + badgeR * 0.28f, bcy + badgeR * 0.42f, p)
        }

        return iconFactory.fromBitmap(bitmap)
    }

    /**
     * 🧍-Standort-Pin (Emoji auf weißer Scheibe, Akzent-Ring, Glow).
     * beat 0/1 = zwei Puls-Phasen für den nativen 2-Beat-Rhythmus.
     */
    fun userLocationPinIcon(iconFactory: IconFactory, context: Context, dark: Boolean, beat: Int): Icon {
        val density = context.resources.displayMetrics.density
        val sizePx = (64 * density).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val cx = sizePx / 2f
        val accent = if (dark) android.graphics.Color.parseColor("#2DD4BF") else android.graphics.Color.parseColor("#3B82F6")
        val glowAlpha = if (beat == 0) 0x40 else 0x18
        val ringAlpha = if (beat == 0) 1.0f else 0.75f

        canvas.drawCircle(cx, cx, sizePx * 0.48f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = (accent and 0x00FFFFFF) or (glowAlpha shl 24)
        })
        canvas.drawCircle(cx, cx, sizePx * 0.38f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlphaMul(accent, ringAlpha)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2.4f * density
        })
        canvas.drawCircle(cx, cx, sizePx * 0.30f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
        })
        val emoji = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx * 0.34f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val baseline = cx - (emoji.descent() + emoji.ascent()) / 2f
        canvas.drawText("\uD83E\uDCD5", cx, baseline, emoji)

        return iconFactory.fromBitmap(bitmap)
    }

    /** ARGB-Farbe mit Multiplikator-Alpha auf dem bestehenden Alpha. */
    fun withAlphaMul(color: Int, alphaMul: Float): Int {
        val a = ((color ushr 24) * alphaMul).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /** Verdunkelt eine Farbe (Faktor 0..1, 1 = unverändert). */
    fun shadeColorInt(color: Int, factor: Float): Int {
        val r = (color shr 16 and 0xFF) * factor
        val g = (color shr 8 and 0xFF) * factor
        val b = (color and 0xFF) * factor
        return android.graphics.Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
    }
}