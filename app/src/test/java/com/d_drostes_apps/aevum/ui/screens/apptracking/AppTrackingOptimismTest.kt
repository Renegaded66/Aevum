package com.d_drostes_apps.aevum.ui.screens.apptracking

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.147: Die optimistische App-Aufzeichnung — reine Regeln, JVM-getestet.
 *
 * Kern-Eigenschaft (User-Anforderung „flüssige Interaktionen"): Ein Klick
 * muss SOFORT wirken (App wechselt die Spalte), die DB folgt dahinter. Die
 * UI darf dabei nie zurückspringen — weder vor dem DB-Write (Override gilt)
 * noch danach (Override weg, DB stimmt jetzt) noch bei einem Fehlschlag
 * (Override weg, ehrlicher alter Stand).
 */
class AppTrackingOptimismTest {

    // ── Effektiver Zustand: lokale Absicht schlägt den (noch alten) DB-Stand ──

    @Test
    fun hinzufuegenIstSofortSichtbarBevorDieDbSchreibt() {
        assertThat(
            AppTrackingOptimism.effectiveTracked(
                hasDatabaseEntry = false,
                override = TrackedOverride(tracked = true)
            )
        ).isTrue()
    }

    @Test
    fun entfernenIstSofortSichtbarTrotzNochVorhandenerDbZeile() {
        assertThat(
            AppTrackingOptimism.effectiveTracked(
                hasDatabaseEntry = true,
                override = TrackedOverride(tracked = false)
            )
        ).isFalse()
    }

    @Test
    fun ohneOverrideZaehltAlleinDerDbStand() {
        assertThat(AppTrackingOptimism.effectiveTracked(true, null)).isTrue()
        assertThat(AppTrackingOptimism.effectiveTracked(false, null)).isFalse()
    }

    // ── Siedlungs-Regel: wann darf der Override verschwinden? ──

    @Test
    fun hinzufuegenSiedeltErstWennDieZeileDaIst() {
        val ov = TrackedOverride(tracked = true)
        assertThat(AppTrackingOptimism.settled(hasDatabaseEntry = false, databaseTypeId = null, override = ov))
            .isFalse()
        assertThat(AppTrackingOptimism.settled(hasDatabaseEntry = true, databaseTypeId = "other", override = ov))
            .isTrue()
    }

    @Test
    fun entfernenSiedeltErstWennDieZeileWegIst() {
        val ov = TrackedOverride(tracked = false)
        assertThat(AppTrackingOptimism.settled(hasDatabaseEntry = true, databaseTypeId = "other", override = ov))
            .isFalse()
        assertThat(AppTrackingOptimism.settled(hasDatabaseEntry = false, databaseTypeId = null, override = ov))
            .isTrue()
    }

    @Test
    fun activityZuordnungSiedeltErstBeiGleichemTyp() {
        val ov = TrackedOverride(tracked = true, activityTypeId = "sport")
        assertThat(AppTrackingOptimism.settled(hasDatabaseEntry = true, databaseTypeId = "other", override = ov))
            .isFalse()
        assertThat(AppTrackingOptimism.settled(hasDatabaseEntry = true, databaseTypeId = "sport", override = ov))
            .isTrue()
    }

    // ── Effektive Activity-Zuordnung ──

    @Test
    fun frischeZuordnungGewinntSofort() {
        assertThat(
            AppTrackingOptimism.effectiveActivityTypeId(
                databaseTypeId = "other",
                override = TrackedOverride(tracked = true, activityTypeId = "sport")
            )
        ).isEqualTo("sport")
    }

    @Test
    fun ohneFrischeZuordnungZaehltDerDbTyp() {
        assertThat(AppTrackingOptimism.effectiveActivityTypeId("reading", null)).isEqualTo("reading")
        // Override ohne neuen Typ (z. B. Re-Enable) → DB-Typ anzeigen.
        assertThat(
            AppTrackingOptimism.effectiveActivityTypeId("reading", TrackedOverride(tracked = true))
        ).isEqualTo("reading")
    }

    // ── Chunk-Merge: die Liste wächst nach, nie Duplikate ──

    @Test
    fun mergeChunkHaengtNeueAnUndErsetztBekannte() {
        val merged = AppTrackingOptimism.mergeChunk(listOf("a", "b"), listOf("b", "c")) { it }
        assertThat(merged).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun warmerCacheErzeugtBeiErneutemScanKeineDuplikate() {
        val current = listOf("a", "b", "c")
        val merged = AppTrackingOptimism.mergeChunk(current, listOf("a", "b", "c")) { it }
        assertThat(merged).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun leererChunkLaesstDenBestandUnveraendert() {
        val current = listOf("a", "b")
        assertThat(AppTrackingOptimism.mergeChunk(current, emptyList<String>()) { it })
            .isEqualTo(current)
    }
}
