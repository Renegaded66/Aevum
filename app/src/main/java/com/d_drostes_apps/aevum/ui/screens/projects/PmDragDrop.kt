package com.d_drostes_apps.aevum.ui.screens.projects

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * M19.2: Drag & Drop für die To-do-Listen (Nutzerwunsch: „per drag and drop
 * verschieben").
 *
 * AUFTEILUNG — und warum:
 *  - [PmReorder] ist die REINE Index-Mathematik (JVM-testbar): kein Compose,
 *    kein Android. Das Verschieben einer Zeile und die Zielberechnung aus
 *    der Zeigerposition sind damit ohne Gerät festgenagelt.
 *  - [PmDragController] hält den flüchtigen Zieh-Zustand (welche Zeile,
 *    wie weit verschoben, wo schwebt der Finger) und liest die echten
 *    Zeilengrenzen aus dem [LazyListState] — dadurch funktioniert es bei
 *    unterschiedlich hohen Zeilen (Projektkarten vs. Aufgabenzeilen) OHNE
 *    Höhen-Annahme.
 *
 * BEDIENUNG: Gezogen wird am Griff (⋮⋮) rechts an der Zeile. Nur der Griff
 * startet das Ziehen — Tippen (abhaken) und Scrollen bleiben unberührt.
 * Während des Ziehens schwebt die Zeile unter dem Finger, an den Rändern
 * scrollt die Liste von selbst nach.
 *
 * GRUPPEN-GRENZEN: In der Heute-Ansicht stehen Aufgaben mehrerer Projekte
 * untereinander. Ein Zug bleibt deshalb INNERHALB der Projekt-Gruppe
 * ([groupOf]) — die Zielberechnung ignoriert Zeilen anderer Gruppen. Die
 * Rückruf-Indizes sind entsprechend LOKAL zur Gruppe.
 */

/** Grenzen einer sichtbaren Zeile (Viewport-Koordinaten, wie LazyListItemInfo). */
data class PmItemBounds(val key: String, val top: Float, val bottom: Float) {
    val centerY: Float get() = (top + bottom) / 2f
}

/** Reine Index-Mathematik — bewusst ohne Compose/Android, damit sie testbar ist. */
object PmReorder {

    /**
     * Verschiebt das Element an [from] an die Stelle [to] (Zielindex in der
     * Liste NACH dem Entfernen — exakt das, was [PmReorder.targetIndex]
     * liefert).
     */
    fun <T> applyMove(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices) return list
        val target = to.coerceIn(0, list.lastIndex)
        if (from == target) return list
        val out = list.toMutableList()
        val moved = out.removeAt(from)
        out.add(target, moved)
        return out
    }

    /**
     * Zielindex beim Ziehen: [bounds] sind die SICHTBAREN, ziehbaren Zeilen
     * OHNE das gezogene Element, in Anzeige-Reihenfolge. Gezählt wird, wie
     * viele Mittelpunkte OBERHALB der Zeigerposition liegen — die Zeile landet
     * damit genau dort, wo der Finger zeigt.
     */
    fun targetIndex(bounds: List<PmItemBounds>, pointerY: Float): Int {
        var target = 0
        for (boundsEntry in bounds) {
            if (pointerY > boundsEntry.centerY) target++ else break
        }
        return target
    }
}

/**
 * Flüchtiger Zieh-Zustand. Wird über [rememberPmDragController] an eine
 * Liste gebunden (Listenzustand, ziehbare Schlüssel in Anzeige-Reihenfolge,
 * Gruppenzuordnung, Rückruf).
 */
class PmDragController internal constructor() {

    private var listState: LazyListState? = null
    private var scope: CoroutineScope? = null
    private var openKeys: List<String> = emptyList()
    private var groupOf: Map<String, String> = emptyMap()
    private var onMove: ((groupId: String, from: Int, to: Int) -> Unit)? = null

    /** Aktuell gezogene Zeile (Schlüssel) oder null. */
    var draggingKey: String? by mutableStateOf(null)
        private set

    /** Senkrechter Versatz der gezogenen Zeile (px). */
    var dragOffsetY: Float by mutableFloatStateOf(0f)
        private set

    /** Zeigerposition in Viewport-Koordinaten (px) — Grundlage der Zielberechnung. */
    private var pointerY: Float = 0f

    internal fun bind(
        state: LazyListState,
        keys: List<String>,
        groups: Map<String, String>,
        move: (groupId: String, from: Int, to: Int) -> Unit
    ) {
        listState = state
        openKeys = keys
        groupOf = groups
        onMove = move
    }

    internal fun attachScope(coroutineScope: CoroutineScope) {
        scope = coroutineScope
    }

    fun isDragged(key: String): Boolean = draggingKey == key

    internal fun onDragStart(key: String) {
        draggingKey = key
        dragOffsetY = 0f
        pointerY = itemCenterY(key) ?: 0f
    }

    internal fun onDrag(deltaY: Float) {
        if (draggingKey == null) return
        dragOffsetY += deltaY
        pointerY += deltaY
        autoScroll()
    }

    internal fun onDragEnd() {
        val key = draggingKey
        draggingKey = null
        dragOffsetY = 0f
        if (key == null) return
        val group = groupOf[key] ?: return
        val mates = openKeys.filter { groupOf[it] == group }
        val from = mates.indexOf(key)
        if (from < 0) return
        val others = visibleBounds()
            .filter { it.key != key && it.key in mates }
        val to = PmReorder.targetIndex(others, pointerY)
        if (to != from) onMove?.invoke(group, from, to)
    }

    internal fun onDragCancel() {
        draggingKey = null
        dragOffsetY = 0f
    }

    // ── innere Helfer ──────────────────────────────────────────────────────

    private fun itemCenterY(key: String): Float? {
        val info = itemInfo(key) ?: return null
        return info.offset + info.size / 2f
    }

    private fun itemInfo(key: String): LazyListItemInfo? =
        listState?.layoutInfo?.visibleItemsInfo?.firstOrNull { it.key == key }

    private fun visibleBounds(): List<PmItemBounds> =
        listState?.layoutInfo?.visibleItemsInfo.orEmpty()
            .mapNotNull { info ->
                val key = info.key as? String ?: return@mapNotNull null
                PmItemBounds(key, info.offset.toFloat(), (info.offset + info.size).toFloat())
            }

    /** Auto-Scroll an den Rändern: Die Liste folgt dem Finger, auch über den Bildschirm hinaus. */
    private fun autoScroll() {
        val state = listState ?: return
        val coroutineScope = scope ?: return
        val info = state.layoutInfo
        val zone = 140f
        val step = 20f
        when {
            pointerY < info.viewportStartOffset + zone ->
                coroutineScope.launch { state.scrollBy(-step) }
            pointerY > info.viewportEndOffset - zone ->
                coroutineScope.launch { state.scrollBy(step) }
        }
    }
}

/**
 * Bindet einen [PmDragController] an eine LazyColumn. [openKeys] ist die
 * Anzeige-Reihenfolge der ziehbaren (offenen) Zeilen, [groupOf] ordnet jeder
 * Zeile ihre Gruppe zu (Heute: Projekt; Detail: das Projekt selbst).
 * [onMove] bekommt LOKALE Indizes innerhalb der Gruppe.
 */
@Composable
fun rememberPmDragController(
    listState: LazyListState,
    openKeys: List<String>,
    groupOf: Map<String, String>,
    onMove: (groupId: String, from: Int, to: Int) -> Unit
): PmDragController {
    val scope = rememberCoroutineScope()
    val controller = remember { PmDragController() }
    controller.attachScope(scope)
    controller.bind(listState, openKeys, groupOf, onMove)
    return controller
}

/** Zieh-Griff: nur hier startet das Ziehen (Tippen und Scrollen bleiben frei). */
fun Modifier.pmDragHandle(controller: PmDragController, key: String): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    pointerInput(key) {
        detectDragGestures(
            onDragStart = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                controller.onDragStart(key)
            },
            onDrag = { change, amount ->
                change.consume()
                controller.onDrag(amount.y)
            },
            onDragEnd = { controller.onDragEnd() },
            onDragCancel = { controller.onDragCancel() }
        )
    }
}

/** Versatz/Anhebung der gezogenen Zeile + Zeichenreihenfolge. */
fun Modifier.pmDragFloat(controller: PmDragController, key: String): Modifier =
    zIndex(if (controller.isDragged(key)) 1f else 0f)
        .graphicsLayer {
            if (controller.isDragged(key)) {
                translationY = controller.dragOffsetY
                scaleX = 1.02f
                scaleY = 1.02f
            }
        }
