package com.d_drostes_apps.aevum.ui.components

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * MapLibre-based map composable for the Geofence Editor.
 *
 * M7.1: Redesigned with crosshair-centered UX (Variant A).
 * - Crosshair stays fixed at screen center.
 * - User pans/zooms the map underneath.
 * - On camera idle (stop), the center coordinate is emitted.
 * - Radius circle renders live at the current center.
 */
@Composable
fun AevumMapView(
    latitude: Double,
    longitude: Double,
    radiusMeters: Float,
    onCenterChanged: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
    // M18.93: Emoji-Pin statt Fadenkreuz — zeigt das aktuell gewählte
    // Geofence-Icon live in der Kartenmitte (Fallback: 📍).
    centerEmoji: String = "",
    // Akzentfarbe des Pins (Geofence-Farbe, Hex "#RRGGBB" oder leer).
    centerColorHex: String = ""
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    val centerColor = remember(centerColorHex) {
        try {
            ComposeColor(android.graphics.Color.parseColor(centerColorHex.ifBlank { "#6366F1" }))
        } catch (_: IllegalArgumentException) {
            ComposeColor(0xFF6366F1)
        }
    }

    // OSM raster style JSON
    val rasterStyleJson = remember {
        """
        {
            "version": 8,
            "name": "OSM Raster",
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
                    "source": "osm-raster"
                }
            ]
        }
        """.trimIndent()
    }

    val center = remember(latitude, longitude) { LatLng(latitude, longitude) }

    Box(modifier = modifier.clip(RoundedCornerShape(12.dp))) {
        // Map
        AndroidView(
            factory = { ctx ->
                // M18.102-CRASHFIX: Factory-Guard (v7.1-Ausnahme für
                // 3rd-party-Native-Init). MapView(ctx) lädt libmaplibre.so —
                // auf 16-KB-Page-Size-Geräten mit alter Lib ein
                // UnsatisfiedLinkError (Error, nicht Exception). Statt den
                // Screen zu crashen: TextView-Platzhalter zeigen; die
                // update-/onRelease-Pfade sind über (as? MapView) abgesichert.
                try {
                    MapView(ctx).also { mapViewRef = it }.apply {
                        getMapAsync { map ->
                            map.uiSettings.isAttributionEnabled = true
                            map.uiSettings.isLogoEnabled = false
                            map.uiSettings.isRotateGesturesEnabled = true
                            map.uiSettings.isScrollGesturesEnabled = true
                            map.uiSettings.isZoomGesturesEnabled = true

                            map.setStyle(Style.Builder().fromJson(rasterStyleJson)) { style ->
                                addCircleLayer(style, center, radiusMeters.toDouble())
                            }

                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(center, 15.0))

                            // M7.1: Use camera IDLE (user stopped moving) — NOT move listener
                            map.addOnCameraIdleListener {
                                val target = map.cameraPosition.target
                                if (target != null) {
                                    onCenterChanged(target.latitude, target.longitude)
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("AevumMapView", "MapView-Init fehlgeschlagen — Platzhalter", t)
                    android.widget.TextView(ctx).apply {
                        text = "⚠️ Karte nicht verfügbar"
                        setTextColor(android.graphics.Color.GRAY)
                        textSize = 13f
                        gravity = android.view.Gravity.CENTER
                    }
                }
            },
            update = { mapView ->
                (mapView as? MapView)?.getMapAsync { map ->
                    // Update circle when radius/lat/lon changed externally
                    val target = LatLng(latitude, longitude)
                    map.style?.let { style ->
                        updateCircle(style, target, radiusMeters.toDouble())
                    }
                    // Only fly to new position if it changed from outside
                    val current = map.cameraPosition.target
                    if (current != null && current.distanceTo(target) > 50) {
                        map.animateCamera(CameraUpdateFactory.newLatLng(target))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(320.dp)
        )

        // Crosshair overlay — fixed at center
        Box(
            modifier = Modifier.fillMaxWidth().height(320.dp),
            contentAlignment = Alignment.Center
        ) {
            // M18.93: Schönes Emoji-Pin statt Fadenkreuz — zeigt LIVE das
            // aktuell gewählte Geofence-Icon (Parameter centerEmoji) auf
            // farbiger Bubble mit weißem Ring, Schatten und Pin-Spitze;
            // darunter weicher Bodenschatten. Position bleibt exakt die
            // Karten-Mitte (Crosshair-Zentrierungs-UX unverändert).
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 26.dp)) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .shadow(6.dp, CircleShape)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(3.dp, centerColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(centerEmoji.ifBlank { "\uD83D\uDCCD" }, fontSize = 26.sp)
                }
                // Pin-Spitze (kleines Dreieck als ▼-Glyph, exakt zentriert).
                Text("▼", fontSize = 14.sp, color = centerColor, modifier = Modifier.offset(y = (-4).dp))
            }
            // Weicher Bodenschatten unter der Pin-Spitze.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = 34.dp)
                    .size(width = 26.dp, height = 7.dp)
                    .alpha(0.25f)
                    .clip(CircleShape)
                    .background(ComposeColor.Black)
            )
        }

        // Coordinate & radius display
        Text(
            text = "${
                "%.6f".format(latitude)
            }, ${
                "%.6f".format(longitude)
            } · ${
                if (radiusMeters >= 1000) "${"%.1f".format(radiusMeters / 1000)}km"
                else "${radiusMeters.toInt()}m"
            }",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(8.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }

    // Lifecycle management
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

// Layer IDs
private const val CIRCLE_SOURCE_ID = "aevum-circle-source"
private const val CIRCLE_FILL_ID = "aevum-circle-fill"
private const val CIRCLE_OUTLINE_ID = "aevum-circle-outline"

private fun addCircleLayer(style: Style, center: LatLng, radiusMeters: Double) {
    val geojson = buildCircleGeoJSON(center, radiusMeters)
    val source = GeoJsonSource(CIRCLE_SOURCE_ID, geojson)
    style.addSource(source)

    // Semi-transparent fill
    style.addLayer(
        FillLayer(CIRCLE_FILL_ID, CIRCLE_SOURCE_ID).apply {
            withProperties(
                PropertyFactory.fillColor("#2DD4BF"),
                PropertyFactory.fillOpacity(0.14f)
            )
        }
    )

    // Outline
    style.addLayer(
        FillLayer(CIRCLE_OUTLINE_ID, CIRCLE_SOURCE_ID).apply {
            withProperties(
                PropertyFactory.fillColor("#14B8A6"),
                PropertyFactory.fillOpacity(0.06f),
                PropertyFactory.fillOutlineColor("#14B8A6")
            )
        }
    )
}

private fun updateCircle(style: Style, center: LatLng, radiusMeters: Double) {
    val geojson = buildCircleGeoJSON(center, radiusMeters)
    val source = style.getSource(CIRCLE_SOURCE_ID) as? GeoJsonSource
    source?.setGeoJson(geojson)
}

/**
 * Build ~36 points approximating a circle at the given center with the given radius.
 */
private fun buildCirclePoints(center: LatLng, radiusMeters: Double): List<List<Double>> {
    val earthRadiusMeters = 6378137.0
    val latRad = Math.toRadians(center.latitude)
    val numPoints = 36
    val points = mutableListOf<List<Double>>()
    for (i in 0 until numPoints) {
        val angle = 2.0 * Math.PI * i / numPoints
        val dx = radiusMeters * Math.cos(angle)
        val dy = radiusMeters * Math.sin(angle)
        val deltaLat = (dy / earthRadiusMeters) * (180.0 / Math.PI)
        val deltaLon = (dx / (earthRadiusMeters * Math.cos(latRad))) * (180.0 / Math.PI)
        points.add(listOf(center.longitude + deltaLon, center.latitude + deltaLat))
    }
    points.add(points[0])
    return points
}

private fun buildCircleGeoJSON(center: LatLng, radiusMeters: Double): String {
    val points = buildCirclePoints(center, radiusMeters)
    val coords = points.joinToString(",") { "[${it[0]},${it[1]}]" }
    return """
    {
        "type": "FeatureCollection",
        "features": [{
            "type": "Feature",
            "geometry": {
                "type": "Polygon",
                "coordinates": [[$coords]]
            },
            "properties": {}
        }]
    }
    """.trimIndent()
}
