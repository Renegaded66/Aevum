package de.devondroste.aevum.ui.components

import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * MapLibre-based map composable for the Geofence Editor.
 * Shows OSM raster tiles at low zoom, with a circle overlay for the geofence radius
 * and a center marker that can be dragged.
 */
@Composable
fun AevumMapView(
    latitude: Double,
    longitude: Double,
    radiusMeters: Float,
    onCenterChanged: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

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
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    getMapAsync { map ->
                        map.uiSettings.isAttributionEnabled = true
                        map.uiSettings.isLogoEnabled = false
                        map.uiSettings.isRotateGesturesEnabled = true
                        map.uiSettings.isScrollGesturesEnabled = true
                        map.uiSettings.isZoomGesturesEnabled = true

                        map.setStyle(Style.Builder().fromJson(rasterStyleJson)) { style ->
                            addGeofenceLayers(style, center, radiusMeters.toDouble(), map)
                        }

                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(center, 15.0))

                        // Marker drag to update center
                        map.addOnCameraMoveListener {
                            val newCenter = map.cameraPosition.target
                            if (newCenter != null) {
                                onCenterChanged(newCenter.latitude, newCenter.longitude)
                            }
                        }
                    }
                }
            },
            update = { mapView ->
                mapView.getMapAsync { map ->
                    val current = map.cameraPosition?.target
                    if (current != null && (kotlin.math.abs(current.latitude - latitude) > 0.0001 || kotlin.math.abs(current.longitude - longitude) > 0.0001)) {
                        // Update geofence source layers
                        map.style?.let { style ->
                            updateGeofenceLayers(style, center, radiusMeters.toDouble(), map)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(320.dp)
        )

        // Coordinate display
        Text(
            text = "${"%.6f".format(latitude)}, ${"%.6f".format(longitude)} · ${
                if (radiusMeters >= 1000) "${"%.1f".format(radiusMeters / 1000)}km"
                else "${radiusMeters.toInt()}m"
            }",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(8.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall
        )
    }

    // Lifecycle management
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // MapView lifecycle is handled by AndroidView internally
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

private var geofenceSourceId = "aevum-geofence-source"
private var geofenceFillLayerId = "aevum-geofence-fill"
private var geofenceOutlineLayerId = "aevum-geofence-outline"
private var markerSourceId = "aevum-marker-source"
private var markerLayerId = "aevum-marker-layer"

private fun addGeofenceLayers(style: Style, center: LatLng, radiusMeters: Double, map: MapLibreMap) {
    // Create the geofence circle as a GeoJSON source
    val circlePoints = buildCirclePoints(center, radiusMeters)
    val geojson = buildCircleGeoJSON(circlePoints)

    val geofenceSource = GeoJsonSource(geofenceSourceId, geojson)
    style.addSource(geofenceSource)

    // Fill layer (semi-transparent)
    val fillLayer = CircleLayer(geofenceFillLayerId, geofenceSourceId).apply {
        withProperties(
            PropertyFactory.circleRadius(8f), // will be resized by the data's scale
            PropertyFactory.circleColor("#2DD4BF"),
            PropertyFactory.circleOpacity(0.18f),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor("#14B8A6"),
            PropertyFactory.circleStrokeOpacity(0.7f)
        )
    }
    style.addLayer(fillLayer)

    // Marker source
    val markerGeojson = """
    {
        "type": "FeatureCollection",
        "features": [{
            "type": "Feature",
            "geometry": {
                "type": "Point",
                "coordinates": [${center.longitude}, ${center.latitude}]
            },
            "properties": {}
        }]
    }
    """.trimIndent()

    val markerSource = GeoJsonSource(markerSourceId, markerGeojson)
    style.addSource(markerSource)

    val markerLayer = CircleLayer(markerLayerId, markerSourceId).apply {
        withProperties(
            PropertyFactory.circleRadius(10f),
            PropertyFactory.circleColor("#2DD4BF"),
            PropertyFactory.circleOpacity(0.9f),
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeOpacity(1.0f)
        )
    }
    style.addLayer(markerLayer)
}

private fun updateGeofenceLayers(style: Style, center: LatLng, radiusMeters: Double, map: MapLibreMap) {
    // Update geofence circle
    val circlePoints = buildCirclePoints(center, radiusMeters)
    val geojson = buildCircleGeoJSON(circlePoints)
    val geofenceSource = style.getSource(geofenceSourceId) as? GeoJsonSource
    geofenceSource?.setGeoJson(geojson)

    // Update marker
    val markerGeojson = """
    {
        "type": "FeatureCollection",
        "features": [{
            "type": "Feature",
            "geometry": {
                "type": "Point",
                "coordinates": [${center.longitude}, ${center.latitude}]
            },
            "properties": {}
        }]
    }
    """.trimIndent()
    val markerSource = style.getSource(markerSourceId) as? GeoJsonSource
    markerSource?.setGeoJson(markerGeojson)
}

/**
 * Build a list of ~36 points approximating a circle at the given center with the given radius.
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
    points.add(points[0]) // close the circle
    return points
}

private fun buildCircleGeoJSON(points: List<List<Double>>): String {
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
