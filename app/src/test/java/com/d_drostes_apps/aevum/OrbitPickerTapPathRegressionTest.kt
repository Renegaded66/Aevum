package com.d_drostes_apps.aevum

import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.domain.liveactivity.RecentActivityType
import com.d_drostes_apps.aevum.ui.components.buildOrbitLayout
import com.d_drostes_apps.aevum.ui.components.planetScaleFactor
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * M18.116-Regression: "+ reagiert nicht, erst nach Suche" (Orbit-Launcher).
 *
 * Root Cause (DashboardScreen -> OrbitLauncherSheet): Der Vollflächen-Tap-
 * Detektor war per `pointerInput(filteredLayout, positions)` verankert,
 * wobei `positions` eine remember-Map AUF DIE LAUFENDEN Animations-Werte
 * (Orbit-Rotation orbitRot, Drift drift) war. Im Idle rotieren+driften die
 * Orbits endlos => positions bekam jede Frame eine neue Map-Instanz =>
 * pointerInput startete den Gesten-Detektor ~60x/s neu => jede Tap-Geste
 * wurde abgebrochen, bevor detectTapGestures sie erkennen konnte. Erst
 * eine Suche frierte die Animationen ein (interactiveIdle=false) — deshalb
 * "reagiert erst nach Suche". Fix: pointerInput-Key nur noch
 * `filteredLayout`; der Detektor liest die Positions live via
 * rememberUpdatedState (kein Gesten-Restart pro Frame mehr).
 *
 * Die Gesture-Restart-Semantik selbst ist Compose-Runtime-Verhalten und
 * nur instrumentiert testbar (ui-test-junit4). Diese JVM-Tests sichern den
 * Daten-Vertrag des Tap-Pfads ab, den der Detektor konsumiert:
 *  1) buildOrbitLayout liefert für ALLE Aktivitäten konsistente
 *     Orbit-Platzierungen (der Tap-Loop iteriert genau diese Items).
 *  2) Der kreisgenaue Hit-Test (Best-Kandidat + 1.6x Radius-Toleranz)
 *     erkennt einen Tap auf einem Planeten-Zentrum zuverlässig —
 *     unabhängig davon, wie die Positionen erzeugt wurden (Animations-
 *     Frames oder statisch). Genau dieser Vertrag war der zweite Halbe
 *     der Fehlfunktion: ohne stabile Positions-Referenz stirbt die Geste,
 *     bevor der Hit-Test läuft.
 */
class OrbitPickerTapPathRegressionTest {

    private fun type(id: String, name: String = id, favorite: Boolean = false) =
        ActivityType(id = id, name = name, isFavorite = favorite)

    // ------------------------------------------------------------------
    // Vertrag 1: Layout-Platzierung — der Tap-Loop iteriert genau diese Items
    // ------------------------------------------------------------------

    @Test
    fun layout_containsEveryActivity_exactlyOnce() {
        val types = (1..30).map { type("a$it", "Aktivität $it") }
        val layout = buildOrbitLayout(types, emptyList())

        assertThat(layout.items.map { it.type.id }).containsNoDuplicates()
        assertThat(layout.items.map { it.type.id }).containsExactlyElementsIn(types.map { it.id })
    }

    @Test
    fun layout_orbitInvariants_countMatchesIndexBounds() {
        val types = (1..25).map { type("a$it", "Aktivität $it") } +
            listOf(type("fav1", "Favorit 1", favorite = true), type("fav2", "Favorit 2", favorite = true))
        val layout = buildOrbitLayout(types, emptyList())

        // Jede Item-Position muss im gültigen Bereich liegen: der Tap-Loop
        // berechnet aus (orbit, countInOrbit, indexInOrbit) den Winkel —
        // Index >= Count oder Index < 0 würde Positionen außerhalb der
        // Orbit-Geometrie erzeugen (Hit-Test verfehlt sichtbare Planeten).
        for (item in layout.items) {
            assertThat(item.orbit).isAtLeast(0)
            assertThat(item.orbit).isAtMost(2)
            assertThat(item.indexInOrbit).isAtLeast(0)
            assertThat(item.indexInOrbit).isLessThan(item.countInOrbit)
        }
        // Pro Orbit eindeutige Indizes (sonst überlagern sich Planeten und
        // der Best-Distance-Hit-Test wählt willkürlich).
        val byOrbit = layout.items.groupBy { it.orbit }
        for ((_, items) in byOrbit) {
            assertThat(items.map { it.indexInOrbit }).containsNoDuplicates()
        }
    }

    @Test
    fun layout_favoritesAreOnInnerOrbit_shortestTapPath() {
        val types = (1..20).map { type("a$it", "Aktivität $it") } +
            listOf(
                type("fav1", "Favorit 1", favorite = true),
                type("fav2", "Favorit 2", favorite = true),
                type("fav3", "Favorit 3", favorite = true),
            )
        val layout = buildOrbitLayout(types, emptyList())

        val favItems = layout.items.filter { it.type.isFavorite }
        assertThat(favItems).isNotEmpty()
        assertThat(favItems.all { it.orbit == 0 }).isTrue()
    }

    // ------------------------------------------------------------------
    // Vertrag 2: Kreisgenauer Hit-Test — der Vertrag, den die Tap-Logik
    // gegen die (jetzt live aktualisierten) Positionen erfüllen muss
    // ------------------------------------------------------------------

    @Test
    fun hitTest_tapAtPlanetCenter_selectsThatPlanet() {
        // Reproduziert die Orbit-Geometrie des Detektors (identische Formel)
        // für einen typischen 1080x2400-Frame und prüft: Tap auf dem Zentrum
        // eines Planeten trifft GENAU diesen Planeten (Best-Distance-Wahl).
        val types = (1..18).map { type("a$it", "Aktivität $it") }
        val layout = buildOrbitLayout(types, emptyList())

        val w = 1080f; val h = 2400f
        val cx = w / 2f
        val headerH = (h * 0.16f) + 30f
        val skyAvailH = h - headerH - 250f
        val maxR = minOf(minOf(cx, skyAvailH / 2f) * 0.92f, h * 0.30f)
        val cy = headerH + skyAvailH / 2f + 12f
        val orbitRs = listOf(maxR * 0.40f, maxR * 0.68f, maxR * 0.98f)
        val planetRa = 96f * planetScaleFactor(layout.items.size) // 32dp @ 3x density

        for (item in layout.items) {
            val r = orbitRs[item.orbit]
            val spread = if (item.countInOrbit <= 1) 0f else 360f / item.countInOrbit
            val base = -90f + item.indexInOrbit * spread
            val a = Math.toRadians(base.toDouble())
            val pos = Pair(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())

            // Exakt die Hit-Test-Logik aus OrbitLauncherSheet.detectTapGestures:
            var bestId: String? = null
            var bestDist = Float.MAX_VALUE
            for (candidate in layout.items) {
                val cr = orbitRs[candidate.orbit]
                val cSpread = if (candidate.countInOrbit <= 1) 0f else 360f / candidate.countInOrbit
                val cBase = -90f + candidate.indexInOrbit * cSpread
                val ca = Math.toRadians(cBase.toDouble())
                val cPos = Pair(cx + cr * cos(ca).toFloat(), cy + cr * sin(ca).toFloat())
                val d = hypot(pos.first - cPos.first, pos.second - cPos.second)
                if (d < bestDist) { bestDist = d; bestId = candidate.type.id }
            }
            assertThat(bestId).isEqualTo(item.type.id)
            assertThat(bestDist).isAtMost(planetRa * 1.6f) // 1.6x Toleranz-Gate
        }
    }

    @Test
    fun hitTest_tapFarFromAllPlanets_clearsSelection() {
        // Ein Tap weit außerhalb jeder 1.6x-Toleranz darf keinen Planeten
        // liefern (Abwahl-Branch: selectedId = null).
        val types = (1..10).map { type("a$it", "Aktivität $it") }
        val layout = buildOrbitLayout(types, emptyList())

        val w = 1080f; val h = 2400f
        val cx = w / 2f
        val headerH = (h * 0.16f) + 30f
        val skyAvailH = h - headerH - 250f
        val maxR = minOf(minOf(cx, skyAvailH / 2f) * 0.92f, h * 0.30f)
        val cy = headerH + skyAvailH / 2f + 12f
        val orbitRs = listOf(maxR * 0.40f, maxR * 0.68f, maxR * 0.98f)
        val planetRa = 96f * planetScaleFactor(layout.items.size)

        // Tap in der unteren Bildschirm-Ecke — außerhalb aller Orbits+Toleranz.
        val tap = Pair(40f, h - 40f)
        var bestId: String? = null
        var bestDist = Float.MAX_VALUE
        for (candidate in layout.items) {
            val r = orbitRs[candidate.orbit]
            val spread = if (candidate.countInOrbit <= 1) 0f else 360f / candidate.countInOrbit
            val base = -90f + candidate.indexInOrbit * spread
            val a = Math.toRadians(base.toDouble())
            val p = Pair(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
            val d = hypot(tap.first - p.first, tap.second - p.second)
            if (d < bestDist) { bestDist = d; bestId = candidate.type.id }
        }
        val passed = bestId != null && bestDist <= planetRa * 1.6f
        assertThat(passed).isFalse()
    }

    // ------------------------------------------------------------------
    // Vertrag 3: Dichte-Skalierung bleibt stabil (Planeten bleiben tappbar)
    // ------------------------------------------------------------------

    @Test
    fun planetScaleFactor_neverShrinksBelowTapTargetContract() {
        // Bei 32dp-Basis darf der Faktor nicht unter 0.7 fallen — sonst
        // rutschen die 1.6x-Toleranz-Ziele unter die 40dp-Tap-Ziel-Grenze.
        assertThat(planetScaleFactor(0)).isEqualTo(1.0f)
        assertThat(planetScaleFactor(24)).isEqualTo(1.0f)
        assertThat(planetScaleFactor(25)).isEqualTo(0.88f)
        assertThat(planetScaleFactor(31)).isEqualTo(0.78f)
        assertThat(planetScaleFactor(39)).isEqualTo(0.70f)
        assertThat(planetScaleFactor(500)).isEqualTo(0.70f)
    }

    // ------------------------------------------------------------------
    // Suche ändert nur die Filterung, nicht die Geometrie-Verträge
    // ------------------------------------------------------------------

    @Test
    fun layout_recentsMarked_forTapTrailContract() {
        val types = listOf(
            type("a1", "Alpha"), type("a2", "Beta"), type("a3", "Gamma"),
        )
        val recents = listOf(RecentActivityType("a2", "Beta", lastUsedAt = 42L))
        val layout = buildOrbitLayout(types, recents)

        val a2 = layout.items.first { it.type.id == "a2" }
        val a1 = layout.items.first { it.type.id == "a1" }
        assertThat(a2.isRecent).isTrue()
        assertThat(a1.isRecent).isFalse()
    }
}