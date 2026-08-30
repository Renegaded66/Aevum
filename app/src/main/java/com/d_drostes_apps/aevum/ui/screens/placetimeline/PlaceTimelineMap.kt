package com.d_drostes_apps.aevum.ui.screens.placetimeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.d_drostes_apps.aevum.domain.placetimeline.PlaceVisit
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.concurrent.ConcurrentHashMap

/**
 * M18.85: INTERAKTIVE Karte der Orts-Timeline — der Google-Maps-Zeitachse
 * nachempfunden (User: "Ich will dass du von den Funktionen genau die
 * Google Maps Zeitachse nachbaust, nur in fancy").
 *
 * Ersetzt die stilisierte Canvas-Karte (M18.83.2 — "nur ein Strich"):
 * echte OSM-Kacheln über MapLibre (bereits via ADR-0024 für den Geofence-
 * Editor integriert — keine neue Dependency, kein API-Key).
 *
 * Funktionen:
 *  - Pan/Zoom/Gesten (echte Karte statt statischem Canvas)
 *  - Nummerierte, farbige Marker pro besuchtem Ort (Besuchsreihenfolge)
 *  - Gestrichelte Routen-Linien zwischen den Orten (Segment-Farbe des
 *    Start-Orts) — GeoJSON-Layer wie Google Timeline
 *  - Auto-Fit auf alle Orte des Tages (Bounds-Kamera), Refit bei Tag-
 *    wechsel; User-Pan bleibt innerhalb eines Tags erhalten
 *  - Marker-Tap → Callout (Name + Zeitfenster + Dauer) + Sync zur Liste
 *    (Aufrufer scrollt zum Eintrag); Listen-Tap → Karte fliegt zum Ort
 *    + Marker wird ausgewählt (Callout)
 *  - Dark-Mode: Kacheln über raster-brightness/saturation getönt
 *  - Mehrfach-Besuche am selben Ort: EIN Marker (erste Nummer), Snippet
 *    listet alle Zeitfenster des Orts
 *
 * Architektur-Entscheidungen (+ Selbst-Hinterfragung):
 *  - Klassische Annotations-API (Marker/IconFactory) statt Annotation-
 *    Manager: weniger bewegliche Teile, <20 Marker/Tag — ausreichen,
 *    identisches Muster wie der Geofence-Editor.
 *  - Marker→Visit-Mapping über eine ConcurrentHashMap (klassische
 *    Marker haben kein Tag-Feld; Snippet-Missbrauch wäre fragil, weil
 *    das Callout den Snippet-Inhalt ANZEIGT — die Id wäre sichtbar).
 *  - Rebuild-Key nur über (Id+Koordinaten+Farbe): Der 60s-Ticker des
 *    ViewModels erzeugt jede Minute neue Visit-Objekte (isOngoing-Ende
 *    wächst) — die Karte darf davon nicht flackern.
 *  - Lifecycle-Forwarding an MapView — ohne das friert die Kachel-
 *    Anzeige nach Hintergrund-Phasen ein.
 *  - BEWUSST nicht gebaut: Klick-auf-Karte-deselektiert (Google tut
 *    das, aber unser Callout ist via selectMarker() Standard-InfoWindow
 *    — Schließen-Button existiert dort nativ).
 */
@Composable
fun PlaceTimelineMap(
    visits: List<PlaceVisit>,
    selectedVisitId: String?,
    onVisitSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val mappable = remember(visits) { visits.filter { it.latitude != null && it.longitude != null } }
    if (mappable.isEmpty()) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isDark = isSystemInDarkTheme()

    // Rebuild-Schlüssel: nur geometrisch relevante Änderungen bauen die
    // Marker neu — der 60s-Ticker (isOngoing-Ende wächst) flackert nicht.
    val mapKey = remember(mappable) {
        mappable.joinToString("|") { "${it.id}:${it.latitude}:${it.longitude}:${it.color}" }
    }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var viewRef by remember { mutableStateOf<MapView?>(null) }
    // Marker-Sync: build-Effekt schreibt, Klick-Listener + Auswahl-Effekt
    // lesen. ConcurrentHashMap, weil der Listener auf dem MapLibre-Thread
    // feuert.
    val markersByVisitId = remember { ConcurrentHashMap<String, Marker>() }
    var builtKey by remember { mutableStateOf<String?>(null) }

    // Dark-Mode-Wechsel = kompletter View-Neuaufbau (Theme-Wechsel ist
    // selten; Kachel-Tinting steckt im Style-JSON).
    key(isDark) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(AevumRadius.md))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).also { viewRef = it }.apply {
                        getMapAsync { map ->
                            map.uiSettings.isAttributionEnabled = true
                            map.uiSettings.isLogoEnabled = false
                            map.uiSettings.isRotateGesturesEnabled = false
                            map.uiSettings.isTiltGesturesEnabled = false
                            map.setStyle(Style.Builder().fromJson(rasterStyleJson(isDark))) { _ ->
                                mapRef = map
                                // M18.85 FANCY-CALLOUT: Statt des grauen Standard-
                                // InfoWindows ein eigenes View-Layout im Aevum-
                                // Design (dunkle Fläche, abgerundet, Ortsfarbe
                                // als Akzent-Leiste, Titel + Zeitfenster).
                                map.setInfoWindowAdapter(
                                    PlaceCalloutAdapter(ctx, isDark) { marker ->
                                        markersByVisitId.entries
                                            .firstOrNull { it.value == marker }
                                            ?.key?.let { id -> mappable.firstOrNull { it.id == id } }
                                            ?.let { parseHexAndroidColor(it.color) }
                                    }
                                )
                                // Marker-Tap: Callout + Sync zur Liste.
                                map.setOnMarkerClickListener { marker ->
                                    val visitId = markersByVisitId.entries
                                        .firstOrNull { it.value == marker }?.key
                                    if (visitId != null) {
                                        map.deselectMarkers()
                                        map.selectMarker(marker)
                                        onVisitSelected(visitId)
                                    }
                                    true
                                }
                            }
                        }
                    }
                },
                update = { _ ->
                    // Bewusst leer: update feuert bei jeder Recomposition
                    // (auch dem 60s-Ticker) — Rebuilds laufen ausschließlich
                    // über den keyierten LaunchedEffect unten.
                },
                onRelease = { view -> view.onDestroy() }
            )
        }
    }

    // Lifecycle-Forwarding (Kachel nur im Vordergrund, sauberer Abbau).
    MapLifecycleEffect(viewRef, lifecycleOwner)

    // Marker + Routen bauen, wenn Map ready ist ODER sich der Tag/Visits
    // geometrisch ändern.
    LaunchedEffect(mapKey, mapRef) {
        val map = mapRef ?: return@LaunchedEffect
        if (builtKey == mapKey) return@LaunchedEffect
        builtKey = mapKey
        markersByVisitId.clear()
        rebuildMarkersAndRoutes(context, map, mappable, markersByVisitId)
        // Auto-Fit: Bounds über alle Orte — Refit bei jedem neuen Key
        // (Tagwechsel springt die Kamera auf den neuen Tag).
        fitToBounds(map, viewRef, mappable)
    }

    // Auswahl-Sync: Liste (oder extern) wählt Visit → Marker auswählen,
    // Callout zeigen, Kamera hinschwingen.
    LaunchedEffect(selectedVisitId, mapRef) {
        val map = mapRef ?: return@LaunchedEffect
        if (selectedVisitId == null) return@LaunchedEffect
        val marker = markersByVisitId[selectedVisitId] ?: return@LaunchedEffect
        map.deselectMarkers()
        map.selectMarker(marker)
        map.animateCamera(CameraUpdateFactory.newLatLng(marker.position))
    }
}

/** Lifecycle-Forwarding an MapView (Kachel-GL braucht onStart/onStop). */
@Composable
private fun MapLifecycleEffect(view: MapView?, owner: androidx.lifecycle.LifecycleOwner) {
    androidx.compose.runtime.DisposableEffect(view, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view?.onStart()
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                Lifecycle.Event.ON_STOP -> view?.onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view?.onDestroy()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Aufbau-Helfer (nicht komposabel)
// ─────────────────────────────────────────────────────────────────────

/** OSM-Raster-Style wie im Geofence-Editor (AevumMapView-Muster), im
 *  Dark-Mode über Standard-Raster-Paint-Properties getönt. */
internal fun rasterStyleJson(dark: Boolean): String {
    val paint = if (dark) {
        """"paint": {
                "raster-brightness-max": 0.55,
                "raster-saturation": -0.55,
                "raster-contrast": -0.15
            }"""
    } else {
        """"paint": { "raster-contrast": 0.05 }"""
    }
    return """
    {
        "version": 8,
        "name": "Aevum Places",
        "sources": {
            "osm-raster": {
                "type": "raster",
                "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
                "tileSize": 256,
                "attribution": "© OpenStreetMap contributors",
                "maxzoom": 19
            }
        },
        "layers": [
            {
                "id": "osm-raster-layer",
                "type": "raster",
                "source": "osm-raster",
                $paint
            }
        ]
    }
    """.trimIndent()
}

/**
 * Baut Marker (EIN Marker pro ORT — Mehrfach-Besuche teilen ihn; die
 * Nummer ist der Index des ERSTEN Besuchs) und gestrichelte Routen-
 * Segmente (Farbe des Start-Orts) neu auf. Schreibt die Marker→Visit-Id
 * in [markersByVisitId] (der ORTS-Anker ist die Id des ersten Besuchs).
 */
private fun rebuildMarkersAndRoutes(
    context: Context,
    map: MapLibreMap,
    visits: List<PlaceVisit>,
    markersByVisitId: ConcurrentHashMap<String, Marker>
) {
    val style = map.style ?: return
    // Alte Marker + Routen entfernen (Rebuild bei Tagwechsel).
    map.markers.forEach { map.removeMarker(it) }
    (0 until ROUTE_SEGMENT_MAX).forEach { i ->
        style.removeLayer(ROUTE_LAYER_PREFIX + i)
        style.removeSource(ROUTE_SOURCE_PREFIX + i)
    }

    val iconFactory = IconFactory.getInstance(context)

    // Ein Marker pro Ort (gleiche Koordinaten = derselbe Ort).
    val byPlace = visits.groupBy { "${it.latitude}:${it.longitude}" }
    var placeNumber = 0
    visits.forEach { visit ->
        val groupKey = "${visit.latitude}:${visit.longitude}"
        val group = byPlace[groupKey] ?: return@forEach
        if (visit !== group.first()) return@forEach // nur der erste Besuch baut den Marker
        placeNumber++
        val anchor = group.first()
        val icon = numberedMarkerIcon(iconFactory, context, placeNumber, anchor.color)
        val snippet = group.joinToString("\n") { v ->
            TimeFormatting.formatTime(v.startAt) + "–" + TimeFormatting.formatTime(v.endAt) +
                " · " + TimeFormatting.formatDuration(v.durationMs)
        }
        val marker = map.addMarker(
            MarkerOptions()
                .position(LatLng(anchor.latitude!!, anchor.longitude!!))
                .title(anchor.name)
                .snippet(snippet)
                .icon(icon)
        )
        markersByVisitId[anchor.id] = marker
    }

    // Routen-Segmente in chronologischer Besuchs-Reihenfolge: von Besuch i
    // nach Besuch i+1 (gleiche aufeinanderfolgende Koordinaten überspringen
    // — kein Null-Segment bei Mehrfach-Besuchen am selben Ort).
    var segment = 0
    for (i in 0 until visits.size - 1) {
        if (segment >= ROUTE_SEGMENT_MAX) break
        val a = visits[i]
        val b = visits[i + 1]
        if (a.latitude == b.latitude && a.longitude == b.longitude) continue
        val sourceId = ROUTE_SOURCE_PREFIX + segment
        val layerId = ROUTE_LAYER_PREFIX + segment
        val geoJson = """
            {"type":"FeatureCollection","features":[{"type":"Feature",
            "geometry":{"type":"LineString","coordinates":[
                [${a.longitude},${a.latitude}],[${b.longitude},${b.latitude}]
            ]}}]}
        """.trimIndent()
        style.addSource(GeoJsonSource(sourceId, geoJson))
        style.addLayer(
            LineLayer(layerId, sourceId).apply {
                withProperties(
                    PropertyFactory.lineColor(parseHexAndroidColor(a.color)),
                    PropertyFactory.lineWidth(3.5f),
                    PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            }
        )
        segment++
    }
}

/** Auto-Fit auf alle Orte (mit Padding); Einzelort → Zoom 15 zentriert.
 *  view.post: Bounds-Kamera braucht die finale View-Größe — direkt nach
 *  dem Style-Load ist das Layout noch nicht fertig. */
private fun fitToBounds(map: MapLibreMap, view: MapView?, visits: List<PlaceVisit>) {
    val latLngs = visits.map { LatLng(it.latitude!!, it.longitude!!) }
    val padPx = (72 * (view?.resources?.displayMetrics?.density ?: 2.75f)).toInt()
    view?.post {
        if (latLngs.size > 1) {
            try {
                val bounds = LatLngBounds.Builder().includes(latLngs).build()
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padPx))
            } catch (_: IllegalArgumentException) {
                // Degenerierte Bounds (identische Punkte) → Fallback.
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLngs.first(), 14.0))
            }
        } else {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLngs.first(), 15.0))
        }
    }
}

/**
 * Nummerierter, farbiger Marker als Bitmap: Glow-Ring + Farbfläche +
 * weiße Umrandung + weiße Nummer (Besuchsreihenfolge) — die Google-
 * Timeline-Nummerierung.
 */
internal fun numberedMarkerIcon(
    iconFactory: IconFactory,
    context: Context,
    number: Int,
    colorHex: String
): Icon {
    val density = context.resources.displayMetrics.density
    val sizePx = (48 * density).toInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = sizePx / 2f
    val baseColor = parseHexAndroidColor(colorHex)

    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (baseColor and 0x00FFFFFF) or 0x50000000 }
    canvas.drawCircle(cx, cx, sizePx * 0.46f, glow)

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = baseColor }
    canvas.drawCircle(cx, cx, sizePx * 0.36f, fill)

    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    canvas.drawCircle(cx, cx, sizePx * 0.36f, ring)

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sizePx * 0.38f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val baseline = cx - (text.descent() + text.ascent()) / 2f
    canvas.drawText(number.toString(), cx, baseline, text)

    return iconFactory.fromBitmap(bitmap)
}

/** Hex-Farbe (Visit/Geofence-Format "#RRGGBB") → Android-Color-Int. */
internal fun parseHexAndroidColor(hex: String): Int = try {
    Color.parseColor(hex)
} catch (_: IllegalArgumentException) {
    Color.parseColor("#6366F1")
}

/**
 * M18.85: Eigenes Callout-View für Marker — ersetzt MapLibres graues
 * Standard-InfoWindow. Aevum-Design: dunkle/helle Fläche je Theme,
 * 12-dp-Rundung, farbige Akzent-Leiste links (echte Ortsfarbe via
 * [accentColorFor]), Titel + Zeitfenster (aus Marker.title/snippet,
 * die beim Rebuild befüllt werden). Programmatic View (kein XML — die
 * App nutzt Compose, ein einzelnes Callout-XML wäre ein Fremdkörper).
 */
private class PlaceCalloutAdapter(
    private val context: Context,
    private val dark: Boolean,
    private val accentColorFor: (Marker) -> Int?
) : MapLibreMap.InfoWindowAdapter {

    override fun getInfoWindow(marker: Marker): View {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val bg = if (dark) 0xF2161A22.toInt() else 0xF2FFFFFF.toInt()
        val textColor = if (dark) 0xFFE8EAF0.toInt() else 0xFF1A1C20.toInt()
        val subColor = if (dark) 0xFF9AA3B2.toInt() else 0xFF5B6470.toInt()

        val root = android.widget.FrameLayout(context)
        root.setContentDescription(marker.title)

        val card = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundResource(android.R.color.transparent)
        }
        val cardParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        root.addView(card, cardParams)

        // Hintergrund-Drawable: abgerundete dunkle Fläche.
        val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(12).toFloat()
        }
        card.background = bgDrawable

        // Farbige Akzent-Leiste links — die ECHTE Ortsfarbe (Lookup über
        // die Marker→Visit-Map des Aufrufers), Fallback Aevum-Türkis.
        val accent = accentColorFor(marker) ?: 0xFF2DD4BF.toInt()
        val accentPaint = android.graphics.drawable.GradientDrawable().apply {
            setColor(accent)
            cornerRadii = floatArrayOf(
                dp(12).toFloat(), dp(12).toFloat(), 0f, 0f,
                0f, 0f, dp(12).toFloat(), dp(12).toFloat()
            )
        }
        val accentView = View(context)
        accentView.background = accentPaint
        card.addView(
            accentView,
            android.widget.LinearLayout.LayoutParams(dp(5), android.widget.LinearLayout.LayoutParams.MATCH_PARENT)
        )

        val textColumn = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(14), dp(10))
        }

        val title = android.widget.TextView(context).apply {
            text = marker.title ?: ""
            setTextColor(textColor)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        textColumn.addView(title)

        if (!marker.snippet.isNullOrBlank()) {
            val snippet = android.widget.TextView(context).apply {
                text = marker.snippet
                setTextColor(subColor)
                textSize = 12f
            }
            textColumn.addView(snippet)
        }

        card.addView(
            textColumn,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return root
    }
}

private const val ROUTE_SOURCE_PREFIX = "aevum-route-src-"
private const val ROUTE_LAYER_PREFIX = "aevum-route-lyr-"
/** Sicherheitsgrenze: ein Tag hat real <30 Ortswechsel; 64 Layer-Ids
 *  sind reserviert (Remove-Loop braucht eine feste Obergrenze). */
private const val ROUTE_SEGMENT_MAX = 64