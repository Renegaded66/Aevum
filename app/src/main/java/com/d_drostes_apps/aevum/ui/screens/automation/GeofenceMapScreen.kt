package com.d_drostes_apps.aevum.ui.screens.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.ui.screens.placetimeline.parseHexAndroidColor
import com.d_drostes_apps.aevum.ui.screens.placetimeline.rasterStyleJson
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
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * M18.92 — Geofence-Übersichtskarte ("ultra fancy, elegant"):
 * Vollbild-MapLibre-Karte mit ALLEN Geofences als farbige Emoji-Pins +
 * halbtransparenten Radius-Kreisen (echte GeoJSON-Geometrie), glowendem
 * 🧍-Standort-Emoji am eigenen Ort (2-Beat-Puls), Tap-Interaktion mit
 * Aevum-Callout (Name + Radius + Status), Callout-Tap → Geofence-Editor
 * und Kamera-Fit auf alle Zonen.
 *
 * ── Design-Entscheidungen (+ Selbst-Hinterfragung) ─────────────────────
 *  1. Klassische Annotations-API (Marker/IconFactory) statt Symbol-Layer:
 *     <20 Geofences real — Icons als Bitmaps sind einfacher, identisches
 *     Muster wie PlaceTimelineMap (M18.85-86), keine Data-Join-Komplexität.
 *  2. Radius-Kreise als EIN gemergtes GeoJSON (Polygon-FeatureCollection
 *     mit Farb-Property) + data-driven fill-color via Expression.toColor:
 *     EIN Source statt N; aktiv/inaktiv über zwei GEFILTERTE Layer mit
 *     unterschiedlicher Opacity (statisches Filter-Pairing ist robuster
 *     über MapLibre-Versionen als switchCase-Opacity-Ausdrücke).
 *  3. Standort als MARKER mit 2-Beat-Puls (zwei vorgerenderte Bitmaps,
 *     Wechsel via updateMarker alle 900ms): GL-Layer müssten für jeden
 *     Beat neu gesetzt werden; Marker-Icon-Swap ist billig. Der Puls läuft
 *     in einem eigenen Effect und berührt KEINE Compose-Recomposition.
 *  4. Lifecycle-Forwarding Pflicht (M18.85-Lektion: ohne onStart/onStop
 *     friert die Kachel-GL-Anzeige nach Hintergrund-Phasen ein).
 *  5. Rebuild-Key nur über Geometrie+Aussehen (id:lat:lon:r:icon:color:
 *     enabled) — der 60s-Standort-Puls baut die Pins NICHT neu; die Kamera
 *     kämpft auch nicht gegen den User (Fit nur beim ersten Build und auf
 *     expliziten Button-Tap).
 *  6. Dark-Mode über rasterStyleJson(isDark) (M18.85-Muster), Theme-Wechsel
 *     = kompletter View-Neuaufbau via key(isDark).
 *  7. Callout = eigenes View (PlaceCalloutAdapter-Muster): Name + Radius +
 *     Aktiv/Inaktiv; Callout-Tap öffnet den Geofence-Editor (echte
 *     Interaktion statt toter InfoWindow).
 *  BEWUSST NICHT gebaut: Klick-auf-leere-Karte-deselektiert (Standard-
 *     InfoWindow schließt nativ), 3D-Tilt (Gimmick).
 */

// ── Layer-/Source-IDs ──────────────────────────────────────────────────
private const val ZONE_SOURCE_ID = "aevum-geofence-zones-src"
private const val ZONE_FILL_ON_LAYER_ID = "aevum-geofence-zones-fill-on"
private const val ZONE_FILL_OFF_LAYER_ID = "aevum-geofence-zones-fill-off"
private const val ZONE_OUTLINE_LAYER_ID = "aevum-geofence-zones-outline"
/** Auflösung der Kreis-Geometrie (Punkte pro Kreis). */
private const val CIRCLE_POINTS = 48
/** Puls-Beat-Dauer des Standort-Markers (ms). */
private const val LOCATION_PULSE_MS = 900L

@Composable
fun GeofenceMapScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onEditGeofence: (String) -> Unit,
    viewModel: GeofenceMapViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isDark = isSystemInDarkTheme()

    // Strings vorab auflösen — im LaunchedEffect (nicht-komposabel) ist
    // stringResource() nicht aufrufbar.
    val activeLabel = stringResource(R.string.geofence_map_active)
    val inactiveLabel = stringResource(R.string.geofence_map_inactive)
    val youAreHereLabel = stringResource(R.string.geofence_map_you_are_here)
    val backLabel = stringResource(R.string.common_back)

    // Standort-Puls nur während die Karte sichtbar ist (Akkuschonung).
    DisposableEffect(Unit) {
        viewModel.startLocationPulse()
        onDispose { viewModel.stopLocationPulse() }
    }

    // Rebuild-Key: NUR Geometrie+Aussehen — der 60s-Puls flackert nicht.
    val geoKey = remember(state.geofences) {
        state.geofences.joinToString("|") {
            "${it.id}:${it.latitude}:${it.longitude}:${it.radiusMeters}:${it.icon}:${it.color}:${it.enabled}"
        }
    }
    val userLoc = state.location
    val userLocKey = userLoc?.let { "${it.latitude},${it.longitude}" }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var viewRef by remember { mutableStateOf<MapView?>(null) }
    // M18.92-FIX2: Style-Load-Fehler sichtbar machen (statt stiller weißer Karte).
    var mapError by remember { mutableStateOf<String?>(null) }
    val markersByGfId = remember { ConcurrentHashMap<String, Marker>() }
    var userMarkerRef by remember { mutableStateOf<Marker?>(null) }
    // builtKey/didInitialFit sind an mapRef gekoppelt: Dark-Mode-Wechsel
    // baut die Map komplett neu (key(isDark)) → Guard muss zurücksetzen,
    // sonst bleiben der neue View leer und die Kamera auf Weltansicht.
    var builtKey by remember(mapRef) { mutableStateOf<String?>(null) }
    var didInitialFit by remember(mapRef) { mutableStateOf(false) }

    // Atem-Rhythmus für die Legende (der Marker pulsiert nativ, siehe unten).
    val pulse = rememberInfiniteTransition(label = "locationPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse)
    )

    key(isDark) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        // M18.92-FIX3: textureMode(true) — MapLibre rendert dann
                        // in eine TextureView statt SurfaceView. SurfaceViews
                        // rendern LEER, wenn der Container durch Navigation-
                        // Transitions graphicsLayer-transformiert wird (genau
                        // das "Buttons sichtbar, Karte weiß"-Symptom). Texture-
                        // View ist von Transformations inbegriffen.
                        val options = org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(ctx)
                            .textureMode(true)
                        MapView(ctx, options).also { viewRef = it }.apply {
                            // MapView-Vertrag: onCreate VOR getMapAsync (die
                            // XML-Maps der App bekommen das vom Layout-Inflater;
                            // in Compose müssen wir es selbst rufen).
                            onCreate(null)
                            getMapAsync { map ->
                                map.uiSettings.isAttributionEnabled = true
                                map.uiSettings.isLogoEnabled = false
                                map.uiSettings.isRotateGesturesEnabled = false
                                map.uiSettings.isTiltGesturesEnabled = false
                                // Style-Load-Fehler an der MAPVIEW registrieren
                                // (dort lebt der Listener — nicht am Map-Objekt).
                                this@apply.addOnDidFailLoadingMapListener { reason ->
                                    mapError = reason ?: "Unbekannter Style-Fehler"
                                }
                                map.setStyle(Style.Builder().fromJson(rasterStyleJson(isDark))) { _ ->
                                    mapError = null
                                    mapRef = map
                                    map.setInfoWindowAdapter(
                                        GeofenceCalloutAdapter(ctx, isDark) { marker ->
                                            markersByGfId.entries.firstOrNull { it.value == marker }?.key
                                                ?.let { id -> state.geofences.firstOrNull { it.id == id } }
                                        }
                                    )
                                    map.setOnMarkerClickListener { marker ->
                                        // User-Marker: kein Callout, kein Zentrieren —
                                        // der eigene Standort ist keine Zone.
                                        if (marker === userMarkerRef) return@setOnMarkerClickListener true
                                        val gfId = markersByGfId.entries
                                            .firstOrNull { it.value == marker }?.key
                                        if (gfId != null) {
                                            map.deselectMarkers()
                                            map.selectMarker(marker)
                                            map.animateCamera(CameraUpdateFactory.newLatLng(marker.position))
                                        }
                                        true
                                    }
                                    // Callout-Tap → Geofence-Editor (Interaktion).
                                    map.setOnInfoWindowClickListener { marker ->
                                        val gfId = markersByGfId.entries
                                            .firstOrNull { it.value == marker }?.key
                                        if (gfId != null) onEditGeofence(gfId)
                                        true
                                    }
                                }
                            }
                        }
                    },
                    update = { _ -> /* bewusst leer — Rebuilds über LaunchedEffect */ },
                    onRelease = { view -> view.onDestroy() }
                )

                // M18.92-FIX1: Lifecycle-Forwarding INNERHALB des key(isDark)-
                // Blocks direkt nach dem AndroidView — mit dem View als
                // Parameter (PlaceTimelineMap-Muster). Vorher stand der Effect
                // NACH dem key(isDark)-Block und las viewRef: Beim ersten
                // Composing war viewRef null; als die Factory ihn setzte,
                // restartete der Effect und onDispose rief onDestroy() auf dem
                // FRISCHEN View → Karte direkt nach der Erstellung zerstört →
                // "Buttons sichtbar, Karte weiß". Das Parameter-Capture macht
                // das unmöglich: Jedes Capture gehört zu seinem eigenen View.
                MapLifecycleEffect(viewRef, lifecycleOwner)

                // ── Glass-Topbar (Fullscreen-Gefühl, kein Karten-Header) ──
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    GlassPill(onClick = onBack) {
                        Text("←  $backLabel")
                    }
                    GlassPill(onClick = {}) {
                        Text(
                            "🗺️  ${state.geofences.size}",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // ── Aktions-Buttons rechts unten (nur mit Standort sinnvoll) ──
                if (userLoc != null) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        GlassCircleButton(
                            emoji = "🎯",
                            contentDescription = stringResource(R.string.geofence_map_recenter),
                            onClick = {
                                viewModel.refreshLocationNow()
                                mapRef?.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(
                                        LatLng(userLoc.latitude, userLoc.longitude), 15.0
                                    )
                                )
                            }
                        )
                        GlassCircleButton(
                            emoji = "⤢",
                            contentDescription = stringResource(R.string.geofence_map_fit_all),
                            onClick = {
                                mapRef?.let { map -> fitToGeofences(map, viewRef, state.geofences, userLoc) }
                            }
                        )
                    }
                }

                // ── Legende unten links: dezent, atmet mit dem Marker-Puls ──
                if (userLoc != null) {
                    GlassPill(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(20.dp),
                        onClick = {}
                    ) {
                        Text(
                            "🧍  $youAreHereLabel",
                            fontSize = 12.sp,
                            modifier = Modifier.alpha(pulseAlpha)
                        )
                    }
                }

                // M18.92-FIX2: Style-Load-Fehler SICHTBAR melden (statt stiller
                // weißer Karte — Debugging ohne logcat möglich).
                mapError?.let { err ->
                    GlassPill(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        onClick = {}
                    ) {
                        Text(
                            "⚠️ $err",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    // ── Pins + Zonen-Kreise bauen (nur bei Geometrie-Änderung) ──
    LaunchedEffect(geoKey, mapRef) {
        val map = mapRef ?: return@LaunchedEffect
        if (builtKey == geoKey) return@LaunchedEffect
        builtKey = geoKey
        val style = map.style ?: return@LaunchedEffect
        val iconFactory = IconFactory.getInstance(context)

        // Alte Geofence-Marker entfernen (User-Marker separat — er pulsiert).
        markersByGfId.values.forEach { map.removeMarker(it) }
        markersByGfId.clear()

        // 1) Radius-Kreise: EIN gemergtes GeoJSON mit Farb-Property.
        rebuildZoneCircles(style, state.geofences)

        // 2) Fancy-Pins: Emoji + Geofence-Farbe + Status.
        state.geofences.forEach { gf ->
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(gf.latitude, gf.longitude))
                    .title(gf.name.ifBlank { "📍" })
                    .snippet("${gf.radiusMeters.toInt()} m · ${if (gf.enabled) activeLabel else inactiveLabel}")
                    .icon(geofencePinIcon(iconFactory, context, gf))
            )
            markersByGfId[gf.id] = marker
        }

        // 3) Erste Kamera: Fit auf alle Zonen + Standort.
        if (!didInitialFit) {
            didInitialFit = true
            fitToGeofences(map, viewRef, state.geofences, userLoc)
        }
    }

    // ── 4a) Standort-Marker: Position aktualisieren (ohne Pin-Rebuild) ──
    LaunchedEffect(userLocKey, mapRef, isDark) {
        val map = mapRef ?: return@LaunchedEffect
        val loc = userLoc ?: return@LaunchedEffect
        val iconFactory = IconFactory.getInstance(context)
        val pos = LatLng(loc.latitude, loc.longitude)
        val existing = userMarkerRef
        if (existing != null && map.markers.contains(existing)) {
            existing.position = pos
            map.updateMarker(existing)
        } else {
            userMarkerRef = map.addMarker(
                MarkerOptions().position(pos)
                    // Kein Titel/Snippet — der eigene Standort braucht kein Callout.
                    .icon(userLocationPinIcon(iconFactory, context, isDark, beat = 0))
            )
        }
    }

    // ── 4b) 2-Beat-Puls des Standort-Markers (nur Icon-Swap, billig) ──
    LaunchedEffect(mapRef, isDark, userLoc != null) {
        val map = mapRef ?: return@LaunchedEffect
        if (userLoc == null) return@LaunchedEffect
        val iconFactory = IconFactory.getInstance(context)
        val beatA = userLocationPinIcon(iconFactory, context, isDark, beat = 0)
        val beatB = userLocationPinIcon(iconFactory, context, isDark, beat = 1)
        while (true) {
            kotlinx.coroutines.delay(LOCATION_PULSE_MS)
            val marker = userMarkerRef ?: continue
            if (!map.markers.contains(marker)) continue
            val currentIsA = marker.icon?.bitmap?.sameAs(beatA.bitmap) ?: false
            marker.icon = if (currentIsA) beatB else beatA
            map.updateMarker(marker)
        }
    }
}

/** M18.92-FIX1: Lifecycle-Forwarding an MapView mit PARAMETER-Capture (wie
 *  PlaceTimelineMap.MapLifecycleEffect). Der View wird als Parameter
 *  gecaptured — ein späterer Key-Wechsel disposet nie ein fremdes/frisches
 *  View. onRelease des AndroidView übernimmt onDestroy (einfach, sauber). */
@Composable
private fun MapLifecycleEffect(view: MapView?, owner: androidx.lifecycle.LifecycleOwner) {
    DisposableEffect(view, owner) {
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
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Zonen-Kreise (gemergtes GeoJSON, data-driven Farben)
// ─────────────────────────────────────────────────────────────────────

/**
 * Baut ALLE Radius-Kreise als EIN GeoJSON-FeatureCollection mit Farb-Property
 * ("color") und rendert sie über data-driven `Expression.toColor`:
 *  - Aktiv: kräftige Füllung (0.14) + Outline-Linie (0.55)
 *  - Inaktiv: "Geister"-Füllung (0.04), keine Outline
 * Zwei gefilterte Fill-Layer statt opacity-switchCase — versionenrobust.
 */
private fun rebuildZoneCircles(style: Style, geofences: List<PlaceGeofence>) {
    // Alte Layer/Source entfernen (Rebuild-Pfad).
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

    val enabledFilter = Expression.eq(
        Expression.get("enabled"), Expression.literal(true)
    )
    val disabledFilter = Expression.eq(
        Expression.get("enabled"), Expression.literal(false)
    )
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

/** Meter→Grad-Kreis (Kosinus-Korrektur für Länge, wie buildCirclePoints). */
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
    pts.add(pts[0]) // Polygon schließen
    return pts
}

// ─────────────────────────────────────────────────────────────────────
// Kamera
// ─────────────────────────────────────────────────────────────────────

/** Fit auf alle Zonen (+ Standort). view.post: Bounds-Kamera braucht die
 *  finale View-Größe (PlaceTimelineMap-Muster). */
private fun fitToGeofences(
    map: MapLibreMap,
    view: MapView?,
    geofences: List<PlaceGeofence>,
    userLoc: UserLocationState?
) {
    val latLngs = geofences.map { LatLng(it.latitude, it.longitude) } +
        listOfNotNull(userLoc?.let { LatLng(it.latitude, it.longitude) })
    if (latLngs.isEmpty()) return
    val padPx = (88 * (view?.resources?.displayMetrics?.density ?: 2.75f)).toInt()
    view?.post {
        if (latLngs.size > 1) {
            try {
                val bounds = LatLngBounds.Builder().includes(latLngs).build()
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padPx))
            } catch (_: IllegalArgumentException) {
                // Degenerierte Bounds (alle Punkte identisch) → Fallback.
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLngs.first(), 14.0))
            }
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLngs.first(), 15.0))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Pin-Bitmaps
// ─────────────────────────────────────────────────────────────────────

/**
 * M18.92 FANCY-GEOFENCE-PIN: Tropfen-Pin in Geofence-Farbe mit weißem Ring,
 * Emoji-Icon im Kopf, weichem Bodenschatten; inaktive Zonen halbtransparent
 * + "Pause"-Badge (sofort sichtbar, welche Zonen scharf sind). Aufbau analog
 * placePinIcon (M18.86), ohne Reihenfolge-Badge.
 */
private fun geofencePinIcon(iconFactory: IconFactory, context: Context, gf: PlaceGeofence): Icon {
    val density = context.resources.displayMetrics.density
    val wPx = (52 * density).toInt()
    val hPx = (62 * density).toInt()
    val bitmap = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val base = parseHexAndroidColor(gf.color.ifBlank { "#6366F1" })
    val darker = shadeColorInt(base, 0.68f)
    val active = gf.enabled
    val alphaMul = if (active) 1.0f else 0.55f

    val cx = wPx / 2f
    val bubbleCy = hPx * 0.36f
    val bubbleR = wPx * 0.40f

    // Weicher Bodenschatten ("schwebender" Pin).
    canvas.drawCircle(cx, hPx * 0.90f, bubbleR * 0.42f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x30 shl 24 // schwarz, alpha 0x30
    })

    // Pin-Spitze.
    val tip = android.graphics.Path().apply {
        moveTo(cx, hPx * 0.92f)
        lineTo(cx - bubbleR * 0.60f, bubbleCy + bubbleR * 0.78f)
        lineTo(cx + bubbleR * 0.60f, bubbleCy + bubbleR * 0.78f)
        close()
    }
    canvas.drawPath(tip, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlphaMul(darker, alphaMul) })

    // Weißer Ring + Farbfläche.
    canvas.drawCircle(cx, bubbleCy, bubbleR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlphaMul(Color.WHITE, alphaMul)
    })
    canvas.drawCircle(cx, bubbleCy, bubbleR - 2f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlphaMul(base, alphaMul)
    })

    // Emoji zentriert.
    val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = bubbleR * 0.92f
        textAlign = Paint.Align.CENTER
    }
    val baseline = bubbleCy - (emojiPaint.descent() + emojiPaint.ascent()) / 2f
    canvas.drawText(gf.icon.ifBlank { "📍" }, cx, baseline, emojiPaint)

    // Inaktiv: dezente Pause-Markierung oben rechts.
    if (!active) {
        val badgeR = wPx * 0.14f
        val bcx = cx + bubbleR * 0.76f
        val bcy = bubbleCy - bubbleR * 0.70f
        canvas.drawCircle(bcx, bcy, badgeR + 1.2f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        })
        canvas.drawCircle(bcx, bcy, badgeR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
        })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = badgeR * 0.38f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(bcx - badgeR * 0.28f, bcy - badgeR * 0.42f, bcx - badgeR * 0.28f, bcy + badgeR * 0.42f, p)
        canvas.drawLine(bcx + badgeR * 0.28f, bcy - badgeR * 0.42f, bcx + badgeR * 0.28f, bcy + badgeR * 0.42f, p)
    }

    return iconFactory.fromBitmap(bitmap)
}

/**
 * 🧍-Standort-Pin: das "lustige Männchen" am eigenen Standort — Emoji auf
 * weißer Aevum-Scheibe mit Akzent-Ring + weichem Glow. Beat 0/1 = zwei
 * Puls-Phasen (Glow stark/leise) für den nativen 2-Beat-Puls.
 */
private fun userLocationPinIcon(
    iconFactory: IconFactory,
    context: Context,
    dark: Boolean,
    beat: Int
): Icon {
    val density = context.resources.displayMetrics.density
    val sizePx = (64 * density).toInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = sizePx / 2f
    val accent = if (dark) Color.parseColor("#2DD4BF") else Color.parseColor("#3B82F6")
    // Beat 0: Glow kräftig — Beat 1: Glow leiser (Atem-Rhythmus).
    val glowAlpha = if (beat == 0) 0x40 else 0x18
    val ringAlpha = if (beat == 0) 1.0f else 0.75f

    // 1) Weicher Glow.
    canvas.drawCircle(cx, cx, sizePx * 0.48f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (accent and 0x00FFFFFF) or (glowAlpha shl 24)
    })
    // 2) Akzent-Ring.
    canvas.drawCircle(cx, cx, sizePx * 0.38f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlphaMul(accent, ringAlpha)
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
    })
    // 3) Weiße Scheibe (Kontrast fürs Emoji, auf jeder Kachel lesbar).
    canvas.drawCircle(cx, cx, sizePx * 0.30f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    })
    // 4) Das Männchen — 🧍 zentriert, leicht größer als die Scheibe wirkt
    //    durch den Glow wie ein "Du bist hier"-Avatar.
    val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx * 0.34f
        textAlign = Paint.Align.CENTER
    }
    val baseline = cx - (emoji.descent() + emoji.ascent()) / 2f
    canvas.drawText("🧍", cx, baseline, emoji)

    return iconFactory.fromBitmap(bitmap)
}

/** ARGB-Farbe mit Multiplikator-Alpha (0..1) auf dem bestehenden Alpha. */
private fun withAlphaMul(color: Int, alphaMul: Float): Int {
    val a = ((color ushr 24) * alphaMul).toInt().coerceIn(0, 255)
    return (color and 0x00FFFFFF) or (a shl 24)
}

/** Verdunkelt eine Farbe (Faktor 0..1, 1 = unverändert) — private Kopie des
 *  PlaceTimelineMap-Helpers ( bewusst lokal, keine Querverbundenheit nötig). */
private fun shadeColorInt(color: Int, factor: Float): Int {
    val r = (color shr 16 and 0xFF) * factor
    val g = (color shr 8 and 0xFF) * factor
    val b = (color and 0xFF) * factor
    return Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
}

// ─────────────────────────────────────────────────────────────────────
// Glass-Overlay-Composables
// ─────────────────────────────────────────────────────────────────────

/** Glas-Pill (Back, Zähler, Legende) — halbtransparente Fläche + Hairline. */
@Composable
private fun GlassPill(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val bg = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = bg,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

/** Runder Glass-Action-Button (🎯 Re-Center, ⤢ Fit). */
@Composable
private fun GlassCircleButton(
    emoji: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    val bg = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = Modifier.size(52.dp).semantics { this.contentDescription = contentDescription },
        shape = androidx.compose.foundation.shape.CircleShape,
        color = bg,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        shadowElevation = 6.dp
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(emoji, fontSize = 20.sp, modifier = Modifier.alpha(0.92f))
        }
    }
}
// ─────────────────────────────────────────────────────────────────────
// Callout-Adapter (eigenes View statt grauem Standard-InfoWindow)
// ─────────────────────────────────────────────────────────────────────

/**
 * M18.92 Aevum-Callout: abgerundete Fläche (12 dp), farbige Akzent-Leiste
 * in der ECHTEN Geofence-Farbe, Titel + "Radius · Status"-Zeile. Programmatic
 * View statt XML (die App ist Compose — M18.85-Pattern). Callout-Tap
 * (setOnInfoWindowClickListener) öffnet den Geofence-Editor.
 */
private class GeofenceCalloutAdapter(
    private val context: Context,
    private val dark: Boolean,
    private val geofenceFor: (Marker) -> PlaceGeofence?
) : MapLibreMap.InfoWindowAdapter {

    override fun getInfoWindow(marker: Marker): View {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val bg = if (dark) 0xF2161A22.toInt() else 0xF2FFFFFF.toInt()
        val textColor = if (dark) 0xFFE8EAF0.toInt() else 0xFF1A1C20.toInt()
        val subColor = if (dark) 0xFF9AA3B2.toInt() else 0xFF5B6470.toInt()
        val gf = geofenceFor(marker)
        val accent = gf?.let { parseHexAndroidColor(it.color.ifBlank { "#6366F1" }) }
            ?: 0xFF2DD4BF.toInt()

        val root = android.widget.FrameLayout(context)
        root.setContentDescription(marker.title)

        val card = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(12).toFloat()
            }
        }
        root.addView(
            card,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // Farbige Akzent-Leiste links — die ECHTE Geofence-Farbe.
        val accentView = View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(accent)
                cornerRadii = floatArrayOf(
                    dp(12).toFloat(), dp(12).toFloat(), 0f, 0f,
                    0f, 0f, dp(12).toFloat(), dp(12).toFloat()
                )
            }
        }
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
