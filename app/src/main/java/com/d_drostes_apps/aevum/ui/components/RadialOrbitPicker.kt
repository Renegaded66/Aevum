package com.d_drostes_apps.aevum.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.domain.liveactivity.RecentActivityType
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * M18.82 — "Orbit Launcher": vollflächiger Aktivitäts-Picker am Dashboard-FAB.
 *
 * Design (User: "keine Standard-Auswahl, etwas Einzigartiges, alles auf einen
 * Blick"): Sternenhimmel-Overlay mit Aktivitäten als Planeten auf konzentrischen
 * Orbits um einen zentralen Kern.
 *  - Orbit 1 (innen): Favoriten — kürzester Tap-Weg, alles ohne Scroll sichtbar.
 *  - Orbit 2/3: Rest nach Namen sortiert; "Kürzlich"-Planeten tragen einen
 *    Trail-Punkt.
 *  - Tipp auf Planet -> Auswahl: Planet pulsiert + Detail-Panel unten
 *    ("Sofort" / "Vorlaufzeit" / "Abbrechen").
 *  - Vorlaufzeit: eigener radialer Minutening (0..120, Snap-Ticks), plus
 *    "Exakte Uhrzeit"-Dialog mit dem bestehenden AevumTimePicker.
 *
 * Bewusste Usability-Gates:
 *  - Kreisgenauer Hit-Test mit 1.6x Radius-Toleranz statt Bounding-Box.
 *  - Rotation/Drift pausieren, sobald etwas ausgewählt oder gesucht ist
 *    (kein "bewegtes Ziel").
 *  - Suche blendet zusätzlich eine klassische Liste ein (gewohnte Interaktion).
 *  - Alle Farben aus MaterialTheme; kein Hardcode.
 *  - Keine externe Library — reines Canvas + Compose-Animationen.
 */

private const val MAX_INNER_ORBIT = 6

private val PLANET_RADIUS_DP = 32.dp
private val MIN_PLANET_DP = 24.dp

// ---------------------------------------------------------------------------
// Layout-Plan: (orbit, indexInOrbit) für jeden Typ — deterministisch.
// ---------------------------------------------------------------------------

internal data class OrbitLayout(
    val items: List<OrbitItem>,
) {
    data class OrbitItem(
        val type: ActivityType,
        val orbit: Int,
        val countInOrbit: Int,
        val indexInOrbit: Int,
        val isRecent: Boolean,
    )
}

/**
 * M18.82.1 (User: "wenn zu viele sind, musst du smart eine Lösung finden"):
 * Dynamische Kapazität je Orbit. Bis 24 Aktivitäten passen alle komfortabel
 * auf 3 Orbits mit 32dp-Planeten. Darüber: Planeten schrumpfen stufenlos
 * (Faktor 1.0 -> 0.72) statt zu clustern — Kugeln bleiben tappbar (>=40dp
 * Ziel), Labels bleiben lesbar (10sp bleibt, ggf. mehr Overlap-Ellipsis).
 * 6 Favoriten bleiben IMMER innen (kürzester Weg).
 */
internal fun planetScaleFactor(totalCount: Int): Float = when {
    totalCount <= 24 -> 1.00f
    totalCount <= 30 -> 0.88f
    totalCount <= 38 -> 0.78f
    else -> 0.70f
}

internal fun buildOrbitLayout(
    activityTypes: List<ActivityType>,
    recents: List<RecentActivityType>,
): OrbitLayout {
    if (activityTypes.isEmpty()) return OrbitLayout(emptyList())
    val recentOrder = recents.map { it.id }
    val favs = activityTypes.filter { it.isFavorite }
        .sortedBy { recentOrder.indexOf(it.id).let { i -> if (i >= 0) i else Int.MAX_VALUE } }
        .ifEmpty { activityTypes.take(3) }
    val rest = activityTypes.filter { it.id !in favs.map { f -> f.id } }
        .sortedBy { it.name.lowercase() }

    val inner = favs.take(MAX_INNER_ORBIT)
    val middle = rest.take((rest.size + 1) / 2)
    val outer = rest.drop((rest.size + 1) / 2)

    val items = mutableListOf<OrbitLayout.OrbitItem>()
    inner.forEachIndexed { i, t ->
        items += OrbitLayout.OrbitItem(t, 0, inner.size, i, recentOrder.contains(t.id))
    }
    if (inner.isEmpty()) {
        // Keine Favoriten: Rest beginnt im Innern-Orbit (nicht leerer Kern).
        val firstHalf = middle.take(3)
        val remain = middle.drop(3)
        firstHalf.forEachIndexed { i, t ->
            items += OrbitLayout.OrbitItem(t, 0, firstHalf.size, i, recentOrder.contains(t.id))
        }
        remain.forEachIndexed { i, t ->
            items += OrbitLayout.OrbitItem(t, 1, remain.size, i, recentOrder.contains(t.id))
        }
        outer.forEachIndexed { i, t ->
            items += OrbitLayout.OrbitItem(t, 2, outer.size, i, recentOrder.contains(t.id))
        }
    } else {
        middle.forEachIndexed { i, t ->
            items += OrbitLayout.OrbitItem(t, 1, middle.size, i, recentOrder.contains(t.id))
        }
        outer.forEachIndexed { i, t ->
            items += OrbitLayout.OrbitItem(t, 2, outer.size, i, recentOrder.contains(t.id))
        }
        // Überzählige Favoriten außen anhängen.
        favs.drop(inner.size).forEach { t ->
            val idx = items.count { it.orbit == 2 }
            items += OrbitLayout.OrbitItem(t, 2, (idx + 1), idx, recentOrder.contains(t.id))
        }
    }
    return OrbitLayout(items)
}

// ---------------------------------------------------------------------------
// OrbitLauncherSheet — öffnet sich vom Dashboard-FAB (M18.82).
// ---------------------------------------------------------------------------

@Composable
fun OrbitLauncherSheet(
    activityTypes: List<ActivityType>,
    recents: List<RecentActivityType>,
    onStart: (String) -> Unit,
    onStartWithTime: (String, String?, Long) -> Unit,
    onCreateActivity: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val favorites = remember(activityTypes) { activityTypes.filter { it.isFavorite }.map { it.id }.toSet() }
    val layout = remember(activityTypes, favorites, recents) {
        buildOrbitLayout(activityTypes, recents)
    }

    var selectedId by remember { mutableStateOf<String?>(null) }
    var showTimeMode by remember { mutableStateOf(false) }
    var retroMinutes by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newActivityName by remember { mutableStateOf("") }
    var exactHour by remember { mutableStateOf(-1) }
    var exactMinute by remember { mutableStateOf(-1) }
    val query = searchQuery.trim()

    val filteredLayout = remember(layout, query) {
        if (query.isEmpty()) layout
        else OrbitLayout(layout.items.filter { it.type.name.contains(query, ignoreCase = true) })
    }
    val inSearchMode = query.isNotEmpty()
    val selected: OrbitLayout.OrbitItem? by derivedStateOf {
        selectedId?.let { id -> layout.items.firstOrNull { it.type.id == id } }
    }

    // --- Animationen --------------------------------------------------------
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }

    val interactiveIdle = selectedId == null && !inSearchMode
    val orbitRot = remember { Animatable(0f) }
    LaunchedEffect(interactiveIdle) {
        if (interactiveIdle) {
            while (true) {
                orbitRot.animateTo(orbitRot.value + 360f, tween(240_000, easing = LinearEasing))
            }
        }
    }
    val drift = remember { Animatable(0f) }
    LaunchedEffect(interactiveIdle) {
        if (interactiveIdle) {
            val start = drift.value
            while (true) {
                drift.animateTo(start + (2f * PI).toFloat(), tween(6000, easing = LinearEasing))
            }
        }
    }
    val pulse = rememberInfiniteTransition(label = "orbitPulse")
    val corePulse by pulse.animateFloat(
        initialValue = 0.96f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "corePulseV",
    )
    val selPulse by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "selPulseV",
    )

    val textMeasurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val onColor = scheme.onSurface
    val surfaceCol = scheme.surface

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.scrim.copy(alpha = 0.78f)),
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val planetR = with(density) { PLANET_RADIUS_DP.toPx() }
        val minPlanetR = with(density) { MIN_PLANET_DP.toPx() }

        val cx = w / 2f
        // M18.82.1 (User: "etwas zu hoch"): Zentrum von 0.33 -> 0.40.
        // M18.82.2 (User: "immer noch Überlappung mit Suchleiste"): Kein
        // Schätzwert mehr — die Header-Höhe wird GEMESSEN (onSizeChanged an
        // der Header-Column) und die Orbit-Geometrie darunter positioniert.
        // Überlappung ist damit strukturell unmöglich (oben + unten geklemmt).
        var headerHeightPx by remember { mutableStateOf(0) }
        val headerH = headerHeightPx.toFloat().coerceAtLeast(h * 0.16f)
        // Verbrauchter vertikaler Raum ober- und unterhalb der Sky:
        // Header oben, Detail-/Ringpanel unten (Puffer 8dp + Labels 22dp).
        val skyTopPx = headerH + with(density) { 12.dp.toPx() }
        val skyBottomPad = with(density) {
            (AevumSpacing.lg + 64.dp + AevumSpacing.lg).toPx() // Detail-Panel-Mindesthöhe
        }
        val skyAvailH = h - skyTopPx - skyBottomPad
        val maxR = min(min(cx, skyAvailH / 2f) * 0.92f, h * 0.30f)
        val cy = skyTopPx + skyAvailH / 2f + with(density) { 4.dp.toPx() }
        val orbitRs = listOf(maxR * 0.40f, maxR * 0.68f, maxR * 0.98f)
        // M18.82.1: Auto-Shrink bei vielen Aktivitäten (smarte Dichte-Lösung):
        // effektiver Planeten-Radius skaliert mit planetScaleFactor().
        val densityScale = planetScaleFactor(filteredLayout.items.size)
        val planetRa = planetR * densityScale

        val positions: Map<String, Offset> = remember(
            filteredLayout, orbitRot.value.roundToInt(), drift.value, w, h,
        ) {
            val map = mutableMapOf<String, Offset>()
            for (item in filteredLayout.items) {
                val r = orbitRs.getOrElse(item.orbit) { orbitRs.last() }
                val spread = if (item.countInOrbit <= 1) 0f else 360f / item.countInOrbit
                val base = -90f + item.indexInOrbit * spread
                val phase = item.orbit * 2.1f + item.indexInOrbit * 0.7f
                val driftX = sin(drift.value + phase) * planetRa * 0.10f
                val driftY = cos(drift.value * 0.8f + phase) * planetRa * 0.08f
                val a = Math.toRadians((base + orbitRot.value).toDouble())
                map[item.type.id] = Offset(
                    cx + r * cos(a).toFloat() + driftX,
                    cy + r * sin(a).toFloat() + driftY,
                )
            }
            map
        }

        // --- Sky-Canvas -----------------------------------------------------
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(scheme.primary.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = maxR * 1.15f,
                ),
                center = Offset(cx, cy),
                radius = maxR * 1.15f,
            )
            for (r in orbitRs) {
                drawCircle(
                    color = onColor.copy(alpha = 0.06f),
                    radius = r, center = Offset(cx, cy),
                    style = Stroke(1.dp.toPx()),
                )
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(scheme.primary.copy(alpha = 0.5f), scheme.primary.copy(alpha = 0.06f)),
                    center = Offset(cx, cy),
                    radius = planetR * 1.8f * corePulse,
                ),
                center = Offset(cx, cy),
                radius = planetR * 1.8f * corePulse,
            )
            drawCircle(
                color = scheme.primary,
                radius = planetR * 0.5f * corePulse,
                center = Offset(cx, cy),
            )

            filteredLayout.items.forEachIndexed { idx, item ->
                val pos = positions[item.type.id] ?: return@forEachIndexed
                val accent = accentFor(item.type)
                val isSel = item.type.id == selectedId
                val scale = if (isSel) selPulse else 1f
                // gestaffelte Spawn-Animation: jede Kugel wirkt ihr Appear-Delay
                val itemAppear = ((appear.value - idx * 0.05f).coerceIn(0f, 1f))
                val springScale = 1f - (1f - itemAppear) * (1f - itemAppear) // easeOutQuad
                val r = (planetRa * scale * (0.4f + 0.6f * springScale)).coerceAtLeast(minPlanetR * 0.5f)

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accentFor(item.type).copy(alpha = 0.35f), Color.Transparent),
                        center = pos, radius = r * 2.0f,
                    ),
                    center = pos, radius = r * 2.0f,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.55f)),
                        center = pos.copy(x = pos.x - r * 0.3f, y = pos.y - r * 0.3f),
                        radius = r * 1.6f,
                    ),
                    center = pos, radius = r,
                )
                if (isSel) {
                    drawCircle(
                        color = onColor.copy(alpha = 0.9f),
                        radius = r * 1.5f, center = pos,
                        style = Stroke(2.5.dp.toPx()),
                    )
                }
                if (item.isRecent && !isSel) {
                    drawCircle(
                        color = onColor.copy(alpha = 0.5f),
                        radius = 3.dp.toPx(),
                        center = Offset(pos.x + r * 1.05f, pos.y - r * 0.9f),
                    )
                }
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        textAlign = android.graphics.Paint.Align.CENTER
                        textSize = r * 1.1f
                        typeface = android.graphics.Typeface.DEFAULT
                        color = android.graphics.Color.WHITE
                    }
                    val icon = if (item.type.icon.isBlank()) "•" else item.type.icon
                    val bounds = android.graphics.Rect()
                    paint.getTextBounds(icon, 0, icon.length, bounds)
                    canvas.nativeCanvas.drawText(icon, pos.x, pos.y - bounds.exactCenterY(), paint)
                }
                // Namens-Label JEDERZEIT unter dem Planeten (User: "jede braucht
                // den Titel darunter").
                // M18.82.2: Labels VOLL WEISS (User-Wunsch), kein Alpha mehr.
                if (!inSearchMode) {
                    val m = textMeasurer.measure(
                        AnnotatedString(item.type.name),
                        style = TextStyle(
                            fontSize = 10.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    drawText(m, topLeft = Offset(pos.x - m.size.width / 2f, pos.y + r * 0.9f))
                }
            }
        }

        // --- Tap-Erkennung (kreisgenau) --------------------------------------
        // M18.82.1 (Z-Ordnung): Diese Box MUSS vor dem Header kommen — lag sie
        // danach, lag sie ÜBER X-Button/Suchfeld und fraß deren Taps (genau der
        // "X funktioniert nicht"-Report). Header/search erhalten zIndex(2f).
        // M18.82.2: PERMANENT aktiv (auch während der Suche):
        //  a) Hintergrund nie klickbar ("nicht durch das Overlay ins Dashboard
        //     steuern") — die Box konsumiert alle freien Flächen.
        //  b) Planeten bleiben in der Suche tappbar (User-Report). Die
        //     Suchliste liegt zIndex(2) über der Box und konsumiert ihre
        //     eigene Zeilen-Taps zuerst — beides koexistiert.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f)
                .pointerInput(filteredLayout, positions) {
                    detectTapGestures { tap ->
                        var bestId: String? = null
                        var bestDist = Float.MAX_VALUE
                        for (item in filteredLayout.items) {
                            val p = positions[item.type.id] ?: continue
                            val d = hypot(tap.x - p.x, tap.y - p.y)
                            if (d < bestDist) { bestDist = d; bestId = item.type.id }
                        }
                        if (bestId != null && bestDist <= planetRa * 1.6f) {
                            selectedId = if (selectedId == bestId) null else bestId
                        } else {
                            selectedId = null
                        }
                    }
                },
        )

        // --- Header ----------------------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(2f)
                .statusBarsPadding()
                .onSizeChanged { headerHeightPx = it.height }
                .padding(horizontal = AevumSpacing.lg, vertical = AevumSpacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringRes(R.string.orbit_title), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = onColor)
                    Text(
                        if (selectedId == null) stringRes(R.string.orbit_hint_idle) else stringRes(R.string.orbit_hint_selected),
                        fontSize = 12.sp, color = onColor.copy(alpha = 0.7f),
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringRes(R.string.common_cancel), tint = onColor)
                }
            }
            // M18.82.1: Live-Suche — filtert bei JEDER Zeicheneingabe direkt
            // (searchQuery-Änderung -> filteredLayout-remember-Key neu -> sofort).
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringRes(R.string.dashboard_search), fontSize = 13.sp, color = onColor.copy(alpha = 0.5f)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = onColor.copy(alpha = 0.6f)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
        }

        // --- Suchlisten-Modus (klassische Liste beim Suchen) ------------------
        if (inSearchMode) {
            // M18.82.1: scrollbar (viele Live-Treffer), Headertonen-Abstand fix.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(2f)
                    .fillMaxWidth()
                    .padding(top = 190.dp, start = AevumSpacing.lg, end = AevumSpacing.lg, bottom = AevumSpacing.lg)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                filteredLayout.items.forEach { item ->
                    val accent = accentFor(item.type)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = AevumSpacing.xs)
                            .clip(RoundedCornerShape(12.dp))
                            .background(surfaceCol.copy(alpha = 0.9f))
                            .clickable {
                                // M18.82.2 (User-Wunsch): Suchtreffer-Click öffnet
                                // dasselbe Detail-Popup wie Planenten-Tap — zentraler
                                // Start-Pfad (Sofort/Vorlaufzeit) bleibt konsistent.
                                selectedId = item.type.id
                                showTimeMode = false
                            }
                            .padding(horizontal = AevumSpacing.md, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (item.type.icon.isBlank()) "•" else item.type.icon, fontSize = 17.sp)
                        Spacer(Modifier.width(AevumSpacing.sm))
                        Text(item.type.name, fontSize = 15.sp, color = onColor, modifier = Modifier.weight(1f))
                        Text("Auswählen", fontSize = 10.sp, color = accent, fontWeight = FontWeight.Medium)
                    }
                }
                // Neue Aktivität
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(surfaceCol.copy(alpha = 0.6f))
                        .clickable { showCreateDialog = true }
                        .padding(horizontal = AevumSpacing.md, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Add, null, tint = onColor.copy(alpha = 0.7f))
                    Spacer(Modifier.width(AevumSpacing.sm))
                    Text(stringRes(R.string.dashboard_create_activity), fontSize = 15.sp, color = onColor.copy(alpha = 0.85f))
                }
            }
        }

        // --- Detail-Panel (unten, bei Auswahl) --------------------------------
        // M18.82.2: Auch in der Suche sichtbar (User: Suchtreffer -> gleiches
        // Popup wie Planenten-Tap). zIndex(3) liegt über der Suchliste (2).
        androidx.compose.animation.AnimatedVisibility(
            visible = selected != null && !showTimeMode,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.92f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(3f)
                .padding(horizontal = AevumSpacing.lg)
                .navigationBarsPadding()
                .padding(bottom = AevumSpacing.lg),
        ) {
            selected?.let { sel ->
                val accent = accentFor(sel.type)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(surfaceCol.copy(alpha = 0.97f))
                        .padding(AevumSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(AevumSpacing.md),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center,
                        ) { Text(if (sel.type.icon.isBlank()) "•" else sel.type.icon, fontSize = 22.sp) }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(sel.type.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = onColor)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(scheme.primary)
                                .clickable {
                                    onStart(sel.type.id)
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringRes(R.string.orbit_start_now),
                                color = scheme.onPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(scheme.primary.copy(alpha = 0.15f))
                                .clickable {
                                    // M18.82.2: Bei Vorlaufzeit die Suche verlassen —
                                    // das Ring-Panel ist bewusst NUR im Sky-Modus
                                    // (Suchliste + Ring würden um Platz kämpfen).
                                    showTimeMode = true
                                    searchQuery = ""
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Schedule, null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                                Text(stringRes(R.string.orbit_start_retro), color = scheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    TextButton(
                        onClick = { selectedId = null },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text(stringRes(R.string.common_cancel), color = onColor.copy(alpha = 0.7f)) }
                }
            }
        }

        // --- Vorlaufzeit-Panel ------------------------------------------------
        if (selected != null && showTimeMode && !inSearchMode) {
            RetroactiveRingPanel(
                accent = accentFor(selected!!.type),
                minutes = retroMinutes,
                onMinutesChange = { retroMinutes = it },
                onExactTime = {
                    val cal = java.util.Calendar.getInstance()
                    exactHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                    exactMinute = cal.get(java.util.Calendar.MINUTE)
                },
                onStart = { m ->
                    val startMs = System.currentTimeMillis() - m * 60_000L
                    onStartWithTime(selected!!.type.id, null, startMs)
                    onDismiss()
                },
                onBack = { showTimeMode = false },
            )
        }

        // --- Exakte Uhrzeit-Dialog (bestehender AevumTimePicker) ---------------
        if (exactHour >= 0) {
            AlertDialog(
                onDismissRequest = { exactHour = -1 },
                title = { Text(stringRes(R.string.orbit_exact_time_title), fontWeight = FontWeight.SemiBold) },
                text = {
                    AevumTimePicker(
                        initialHour = exactHour,
                        initialMinute = exactMinute,
                        accent = scheme.primary,
                        onTimeChange = { h, m -> exactHour = h; exactMinute = m },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val c = java.util.Calendar.getInstance()
                        c.set(java.util.Calendar.HOUR_OF_DAY, exactHour)
                        c.set(java.util.Calendar.MINUTE, exactMinute)
                        c.set(java.util.Calendar.SECOND, 0)
                        c.set(java.util.Calendar.MILLISECOND, 0)
                        if (c.timeInMillis > System.currentTimeMillis()) {
                            c.add(java.util.Calendar.DAY_OF_MONTH, -1)
                        }
                        onStartWithTime(selected!!.type.id, null, c.timeInMillis)
                        onDismiss()
                    }) { Text(stringRes(R.string.orbit_start_at_time)) }
                },
                dismissButton = {
                    TextButton(onClick = { exactHour = -1 }) { Text(stringRes(R.string.common_cancel)) }
                },
            )
        }

        // --- Neue Aktivität-Dialog --------------------------------------------
        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false; newActivityName = "" },
                title = { Text(stringRes(R.string.dashboard_new_activity), fontWeight = FontWeight.SemiBold) },
                text = {
                    OutlinedTextField(
                        value = newActivityName,
                        onValueChange = { newActivityName = it },
                        label = { Text(stringRes(R.string.dashboard_name_example), fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val name = newActivityName.trim()
                        if (name.isNotEmpty()) {
                            onCreateActivity(name)
                            showCreateDialog = false
                            newActivityName = ""
                        }
                    }) { Text(stringRes(R.string.common_apply)) }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false; newActivityName = "" }) {
                        Text(stringRes(R.string.common_cancel))
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Radialer Vorlaufzeit-Ring
// ---------------------------------------------------------------------------

@Composable
private fun RetroactiveRingPanel(
    accent: Color,
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    onExactTime: () -> Unit,
    onStart: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val onColor = scheme.onSurface
    val textMeasurer = rememberTextMeasurer()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AevumSpacing.lg)
            .navigationBarsPadding()
            .padding(bottom = AevumSpacing.lg)
            .clip(RoundedCornerShape(22.dp))
            .background(scheme.surface.copy(alpha = 0.97f))
            .padding(AevumSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringRes(R.string.orbit_retro_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = onColor)
                Text(stringRes(R.string.orbit_retro_sub), fontSize = 11.sp, color = onColor.copy(alpha = 0.6f))
            }
            TextButton(onClick = onBack) { Text(stringRes(R.string.common_back), color = onColor.copy(alpha = 0.7f)) }
        }

        // Texte VOR dem Canvas auflösen (stringRes ist @Composable, im
        // DrawScope nicht aufrufbar) — Canvas bekommt fertige Strings.
        val nowLabel = stringRes(R.string.orbit_retro_now)
        val agoTemplate = stringRes(R.string.orbit_retro_started_ago)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp),
            contentAlignment = Alignment.Center,
        ) {
            var ringSize by remember { mutableStateOf(IntSize.Zero) }
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { ringSize = it }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { off -> minutesFromAngle(ringSize, off, onMinutesChange) },
                            onDrag = { change, _ ->
                                change.consume()
                                minutesFromAngle(ringSize, change.position, onMinutesChange)
                            },
                        )
                    },
            ) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val ringR = min(size.width, size.height) / 2f - 26.dp.toPx()
                drawCircle(
                    color = onColor.copy(alpha = 0.10f),
                    radius = ringR, center = c, style = Stroke(18.dp.toPx()),
                )
                val sweep = 360f * (minutes / 120f)
                drawArc(
                    brush = Brush.sweepGradient(listOf(accent.copy(alpha = 0.35f), accent)),
                    startAngle = -90f, sweepAngle = sweep, useCenter = false,
                    topLeft = Offset(c.x - ringR, c.y - ringR),
                    size = androidx.compose.ui.geometry.Size(ringR * 2f, ringR * 2f),
                    style = Stroke(18.dp.toPx()),
                )
                for (m in listOf(0, 15, 30, 45, 60, 90, 120)) {
                    val a = Math.toRadians((-90.0 + 360.0 * m / 120.0))
                    val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
                    drawLine(
                        color = if (abs(minutes - m) <= 2) accent else onColor.copy(alpha = 0.25f),
                        start = c + dir * (ringR - 16.dp.toPx()),
                        end = c + dir * (ringR + 16.dp.toPx()),
                        strokeWidth = 2.5.dp.toPx(),
                    )
                }
                // Knob am Ende des Fortschritts
                val knobA = Math.toRadians((-90.0 + 360.0 * minutes / 120.0))
                val knobPos = c + Offset(cos(knobA).toFloat(), sin(knobA).toFloat()) * ringR
                drawCircle(color = scheme.surface, radius = 11.dp.toPx(), center = knobPos)
                drawCircle(color = accent, radius = 8.dp.toPx(), center = knobPos)

                val label = if (minutes == 0) nowLabel else "-${formatMinutesLabel(minutes)}"
                val tm = textMeasurer.measure(
                    AnnotatedString(label),
                    style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = onColor),
                )
                drawText(tm, topLeft = Offset(c.x - tm.size.width / 2f, c.y - tm.size.height / 2f - 14.dp.toPx()))
                val mLabel = formatMinutesLabel(minutes)
                val agoText = if (mLabel.isEmpty()) nowLabel.removePrefix("Start: ").replaceFirstChar { it.uppercase() } else agoTemplate.format(mLabel)
                val sub = textMeasurer.measure(
                    AnnotatedString(agoText),
                    style = TextStyle(fontSize = 11.sp, color = onColor.copy(alpha = 0.55f)),
                )
                drawText(sub, topLeft = Offset(c.x - sub.size.width / 2f, c.y - sub.size.height / 2f + 18.dp.toPx()))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            listOf(0, 5, 10, 15, 30, 45, 60).forEach { m ->
                val active = minutes == m
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) accent else onColor.copy(alpha = 0.08f))
                        .clickable { onMinutesSettle(m, onMinutesChange) }
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (m == 0) stringRes(R.string.orbit_retro_now_chip) else "-${m}m",
                        fontSize = 12.sp,
                        color = if (active) pickOnColor(accent) else onColor.copy(alpha = 0.8f),
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(onColor.copy(alpha = 0.08f))
                    .clickable { onExactTime() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, null, tint = onColor.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                    Text(stringRes(R.string.orbit_exact_time), fontSize = 12.sp, color = onColor.copy(alpha = 0.85f))
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent)
                    .clickable { onStart(minutes) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    if (minutes == 0) stringRes(R.string.orbit_retro_start_now) else stringRes(R.string.orbit_retro_start_with_time),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pickOnColor(accent),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun accentFor(type: ActivityType): Color =
    if (type.color != 0L) Color(type.color)
    else categoryColor(type.defaultCategoryId ?: "other")

private fun minutesFromAngle(size: IntSize, pos: Offset, onChange: (Int) -> Unit) {
    if (size.width <= 0 || size.height <= 0) return
    val c = Offset(size.width / 2f, size.height / 2f)
    val v = pos - c
    if (hypot(v.x, v.y) < 40f) return
    var deg = Math.toDegrees(atan2(v.y, v.x).toDouble()).toFloat() + 90f
    if (deg < 0) deg += 360f
    onChange((deg / 360f * 120f).roundToInt().coerceIn(0, 120))
}

private fun onMinutesSettle(m: Int, onChange: (Int) -> Unit) = onChange(m)

private fun formatMinutesLabel(m: Int): String = when {
    m == 0 -> ""
    m < 60 -> "$m min"
    m % 60 == 0 -> "${m / 60} h"
    else -> "-${m / 60} h ${m % 60} min"
}

private fun pickOnColor(accent: Color): Color {
    val lum = 0.299 * accent.red + 0.587 * accent.green + 0.114 * accent.blue
    return if (lum > 0.6) Color(0xFF171329) else Color.White
}

@Composable
private fun stringRes(id: Int): String = androidx.compose.ui.res.stringResource(id)

@Composable
private fun stringRes(id: Int, arg: Any): String =
    androidx.compose.ui.res.stringResource(id, arg)