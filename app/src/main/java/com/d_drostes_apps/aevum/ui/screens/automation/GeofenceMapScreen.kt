package com.d_drostes_apps.aevum.ui.screens.automation

import android.content.Context
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import com.d_drostes_apps.aevum.ui.screens.placetimeline.rasterStyleJson
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.concurrent.ConcurrentHashMap

/**
 * M18.92/M18.93 — Geofence-Übersichtskarte (VOLLBILD).
 *
 * Nutzt [GeofenceMapCore] (geteilter Core mit der Inline-Karte der Liste)
 * für Kreise, Pins und Standort-Puls. Diese Datei enthält nur noch den
 * Vollbild-Wrapper: Glass-Topbar, Re-Center/Fit-Buttons, Legende, Callout.
 *
 * M18.92-FIX-Lektionen (siehe skill-Ref m18-92): textureMode(true) für
 * TextureView (transformations-sicher), onCreate(null) vor getMapAsync,
 * Lifecycle-Forwarding mit Parameter-Capture INNERHALB des key(isDark)-
 * Blocks, Fail-Listener an der MapView + sichtbare Error-Pill.
 */

@Composable
fun GeofenceMapScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onEditGeofence: (String) -> Unit,
    viewModel: GeofenceMapViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val isDark = isSystemInDarkTheme()

    val backLabel = stringResource(R.string.common_back)
    val youAreHereLabel = stringResource(R.string.geofence_map_you_are_here)

    DisposableEffect(Unit) {
        viewModel.startLocationPulse()
        onDispose { viewModel.stopLocationPulse() }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        GeofenceMapView(
            geofences = state.geofences,
            userLocation = state.location,
            isDark = isDark,
            modifier = Modifier.fillMaxSize(),
            fitOnBuild = true,
            onMarkerTap = { map, marker -> map.deselectMarkers(); map.selectMarker(marker) },
            onCalloutTap = onEditGeofence,
            overlayContent = { pulseAlpha, userLoc, map, view ->
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
                            "\uD83D\uDDFA\uFE0F  ${state.geofences.size}",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (userLoc != null) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        GlassCircleButton(
                            emoji = "\uD83C\uDFAF",
                            contentDescription = stringResource(R.string.geofence_map_recenter),
                            onClick = {
                                viewModel.refreshLocationNow()
                                map?.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(
                                        LatLng(userLoc.latitude, userLoc.longitude), 15.0
                                    )
                                )
                            }
                        )
                        GlassCircleButton(
                            emoji = "\u2922",
                            contentDescription = stringResource(R.string.geofence_map_fit_all),
                            onClick = {
                                if (map != null) fitToGeofences(map, view, state.geofences, userLoc)
                            }
                        )
                    }

                    GlassPill(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(20.dp),
                        onClick = {}
                    ) {
                        Text(
                            "\uD83E\uDCD5  $youAreHereLabel",
                            fontSize = 12.sp,
                            modifier = Modifier.alpha(pulseAlpha)
                        )
                    }
                }
            }
        )
    }
}

/**
 * Geteilter Vollbild-Map-Host (auch von der Inline-Karte nutzbar).
 * Overlays kommen via [overlayContent] (erhält Puls-Alpha + Standort).
 */
@Composable
internal fun GeofenceMapView(
    geofences: List<com.d_drostes_apps.aevum.data.model.PlaceGeofence>,
    userLocation: UserLocationState?,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    fitOnBuild: Boolean,
    onMarkerTap: (MapLibreMap, Marker) -> Unit = { _, _ -> },
    onCalloutTap: (String) -> Unit = {},
    showUserLocation: Boolean = true,
    // Overlay erhält Puls-Alpha + Standort + Map/View (für Re-Center/Fit);
    // BoxScope-Receiver, damit Overlays sich mit align positionieren können.
    overlayContent: @Composable BoxScope.(pulseAlpha: Float, userLoc: UserLocationState?, map: MapLibreMap?, view: MapView?) -> Unit = { _, _, _, _ -> }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val activeLabel = stringResource(R.string.geofence_map_active)
    val inactiveLabel = stringResource(R.string.geofence_map_inactive)

    val geoKey = remember(geofences) {
        geofences.joinToString("|") {
            "${it.id}:${it.latitude}:${it.longitude}:${it.radiusMeters}:${it.icon}:${it.color}:${it.enabled}"
        }
    }
    val userLoc = userLocation
    val userLocKey = userLoc?.let { "${it.latitude},${it.longitude}" }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var viewRef by remember { mutableStateOf<MapView?>(null) }
    var mapError by remember { mutableStateOf<String?>(null) }
    val markersByGfId = remember { java.util.concurrent.ConcurrentHashMap<String, Marker>() }
    var userMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var builtKey by remember(mapRef) { mutableStateOf<String?>(null) }
    var didInitialFit by remember(mapRef) { mutableStateOf(false) }

    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "locationPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1400),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    key(isDark) {
        Box(modifier = modifier) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // M18.102-CRASHFIX: Factory-Guard (v7.1-Ausnahme für
                    // 3rd-party-Native-Init) — libmaplibre.so-Ladefehler
                    // (16-KB-Page-Size) crashen sonst den Screen.
                    try {
                        // textureMode(true): TextureView statt SurfaceView —
                        // transformations-sicher in Navigation-Transitions.
                        val options = MapLibreMapOptions.createFromAttributes(ctx)
                            .textureMode(true)
                        MapView(ctx, options).also { viewRef = it }.apply {
                            onCreate(null)
                            getMapAsync { map ->
                                map.uiSettings.isAttributionEnabled = true
                                map.uiSettings.isLogoEnabled = false
                                map.uiSettings.isRotateGesturesEnabled = false
                                map.uiSettings.isTiltGesturesEnabled = false
                                this@apply.addOnDidFailLoadingMapListener { reason ->
                                    mapError = reason ?: "Unbekannter Style-Fehler"
                                }
                                map.setStyle(Style.Builder().fromJson(rasterStyleJson(isDark))) { _ ->
                                    mapError = null
                                    mapRef = map
                                    map.setInfoWindowAdapter(
                                        GeofenceCalloutAdapter(ctx, isDark) { marker ->
                                            markersByGfId.entries.firstOrNull { it.value == marker }?.key
                                                ?.let { id -> geofences.firstOrNull { it.id == id } }
                                        }
                                    )
                                    map.setOnMarkerClickListener { marker ->
                                        if (marker === userMarkerRef) return@setOnMarkerClickListener true
                                        if (markersByGfId.containsValue(marker)) onMarkerTap(map, marker)
                                        true
                                    }
                                    map.setOnInfoWindowClickListener { marker ->
                                        val gfId = markersByGfId.entries
                                            .firstOrNull { it.value == marker }?.key
                                        if (gfId != null) onCalloutTap(gfId)
                                        true
                                    }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        android.util.Log.e("GeofenceMapScreen", "MapView-Init fehlgeschlagen — Platzhalter", t)
                        android.widget.TextView(ctx).apply {
                            text = "⚠️ Karte nicht verfügbar"
                            setTextColor(android.graphics.Color.GRAY)
                            textSize = 13f
                            gravity = android.view.Gravity.CENTER
                        }
                    }
                },
                update = { _ -> },
                onRelease = { view -> (view as? MapView)?.onDestroy() }
            )

            // Lifecycle-Forwarding mit Parameter-Capture (M18.92-FIX1).
            MapLifecycleEffect(viewRef, lifecycleOwner)

            overlayContent(pulseAlpha, userLoc, mapRef, viewRef)

            mapError?.let { err ->
                GlassPill(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    onClick = {}
                ) {
                    Text(
                        "\u26A0\uFE0F $err",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    // Pins + Kreise bauen (nur bei Geometrie-Änderung).
    LaunchedEffect(geoKey, mapRef) {
        val map = mapRef ?: return@LaunchedEffect
        if (builtKey == geoKey) return@LaunchedEffect
        builtKey = geoKey
        GeofenceMapCore.buildGeofenceMarkers(map, context, geofences, markersByGfId)
        if (fitOnBuild && !didInitialFit) {
            didInitialFit = true
            fitToGeofences(map, viewRef, geofences, userLoc)
        }
    }

    // Standort-Marker: Position updaten (ohne Pin-Rebuild).
    LaunchedEffect(userLocKey, mapRef, isDark) {
        val map = mapRef ?: return@LaunchedEffect
        val loc = userLoc ?: return@LaunchedEffect
        if (!showUserLocation) return@LaunchedEffect
        val iconFactory = org.maplibre.android.annotations.IconFactory.getInstance(context)
        val pos = LatLng(loc.latitude, loc.longitude)
        val existing = userMarkerRef
        if (existing != null && map.markers.contains(existing)) {
            existing.position = pos
            map.updateMarker(existing)
        } else {
            userMarkerRef = map.addMarker(
                MarkerOptions().position(pos)
                    .icon(GeofenceMapCore.userLocationPinIcon(iconFactory, context, isDark, beat = 0))
            )
        }
    }

    // 2-Beat-Puls (Icon-Swap, billig).
    LaunchedEffect(mapRef, isDark, userLoc != null, showUserLocation) {
        val map = mapRef ?: return@LaunchedEffect
        if (userLoc == null || !showUserLocation) return@LaunchedEffect
        val iconFactory = org.maplibre.android.annotations.IconFactory.getInstance(context)
        val beatA = GeofenceMapCore.userLocationPinIcon(iconFactory, context, isDark, beat = 0)
        val beatB = GeofenceMapCore.userLocationPinIcon(iconFactory, context, isDark, beat = 1)
        while (true) {
            kotlinx.coroutines.delay(900L)
            val marker = userMarkerRef ?: continue
            if (!map.markers.contains(marker)) continue
            val currentIsA = marker.icon?.bitmap?.sameAs(beatA.bitmap) ?: false
            marker.icon = if (currentIsA) beatB else beatA
            map.updateMarker(marker)
        }
    }
}

/** Lifecycle-Forwarding mit Parameter-Capture (M18.92-FIX1-Lektion). */
@Composable
internal fun MapLifecycleEffect(view: MapView?, owner: androidx.lifecycle.LifecycleOwner) {
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

/** Fit auf alle Zonen (+ Standort). view.post: Bounds-Kamera braucht die
 *  finale View-Größe (PlaceTimelineMap-Muster). */
internal fun fitToGeofences(
    map: MapLibreMap,
    view: MapView?,
    geofences: List<com.d_drostes_apps.aevum.data.model.PlaceGeofence>,
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
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLngs.first(), 14.0))
            }
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLngs.first(), 15.0))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Glass-Overlay-Composables
// ─────────────────────────────────────────────────────────────────────

/** Glas-Pill (Back, Zähler, Legende) — halbtransparente Fläche + Hairline. */
@Composable
internal fun GlassPill(
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

/** Runder Glass-Action-Button. */
@Composable
internal fun GlassCircleButton(
    emoji: String,
    contentDescription: String,
    onClick: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val bg = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val sizeDp = if (compact) 38.dp else 52.dp
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = modifier
            .size(sizeDp)
            .semantics { this.contentDescription = contentDescription },
        shape = androidx.compose.foundation.shape.CircleShape,
        color = bg,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        shadowElevation = 6.dp
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(emoji, fontSize = if (compact) 15.sp else 20.sp, modifier = Modifier.alpha(0.92f))
        }
    }
}
// ─────────────────────────────────────────────────────────────────────
// Callout-Adapter (eigenes View statt grauem Standard-InfoWindow)
// ─────────────────────────────────────────────────────────────────────

/**
 * M18.92 Aevum-Callout: abgerundete Fläche (12 dp), farbige Akzent-Leiste
 * in der ECHTEN Geofence-Farbe, Titel + "Radius · Status"-Zeile.
 * Programmatic View statt XML (die App ist Compose — M18.85-Pattern).
 * Callout-Tap (setOnInfoWindowClickListener) öffnet den Geofence-Editor.
 */
internal class GeofenceCalloutAdapter(
    private val context: Context,
    private val dark: Boolean,
    private val geofenceFor: (Marker) -> com.d_drostes_apps.aevum.data.model.PlaceGeofence?
) : MapLibreMap.InfoWindowAdapter {

    override fun getInfoWindow(marker: Marker): android.view.View {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val bg = if (dark) 0xF2161A22.toInt() else 0xF2FFFFFF.toInt()
        val textColor = if (dark) 0xFFE8EAF0.toInt() else 0xFF1A1C20.toInt()
        val subColor = if (dark) 0xFF9AA3B2.toInt() else 0xFF5B6470.toInt()
        val gf = geofenceFor(marker)
        val accent = gf?.let { com.d_drostes_apps.aevum.ui.screens.placetimeline.parseHexAndroidColor(it.color.ifBlank { "#6366F1" }) }
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

        val accentView = android.view.View(context).apply {
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
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return root
    }
}
