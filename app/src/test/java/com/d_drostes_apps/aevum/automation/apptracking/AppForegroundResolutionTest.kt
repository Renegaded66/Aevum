package com.d_drostes_apps.aevum.automation.apptracking

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M20: Kombinations-Regel der Digital-Vorprüfung — welche Quelle gewinnt,
 * wenn der ScreenRecordingWorker entscheiden muss, ob eine getrackte App im
 * Vordergrund ist? (Nutzerfall: frisch geöffnete App nach Handy-Neustart —
 * „Digital" startete, obwohl die getrackte App offen war.)
 *
 * Regeln:
 *  - Frischer Service-Schnappschuss (Service pollt im 2-s-Takt) ist
 *    AUTORITATIV — auch sein „null" (Homescreen). Eine Einmal-Abfrage kann
 *    es nicht besser wissen.
 *  - Sonst: kurze Faltung (2 min) — gerade geöffnete/verlassene App.
 *  - Sonst: weite Baseline-Faltung (12 h) — App „ruhig offen".
 *  - Erst wenn alle drei leer sind: null (kein Vordergrund nachweisbar).
 */
class AppForegroundResolutionTest {

    private val duolingo = "com.duolingo"

    @Test
    fun `frischer Schnappschuss gewinnt immer`() {
        assertThat(
            AppForegroundResolution.pick(
                snapshotFresh = true,
                snapshotPackage = duolingo,
                shortFold = "com.android.chrome",
                wideFold = "com.instagram.android"
            )
        ).isEqualTo(duolingo)
    }

    @Test
    fun `frischer Schnappschuss mit Homescreen-null ist autoritativ`() {
        assertThat(
            AppForegroundResolution.pick(
                snapshotFresh = true,
                snapshotPackage = null,
                shortFold = duolingo,
                wideFold = duolingo
            )
        ).isNull()
    }

    @Test
    fun `veralteter Schnappschuss wird ignoriert - kurze Faltung gewinnt`() {
        assertThat(
            AppForegroundResolution.pick(
                snapshotFresh = false,
                snapshotPackage = "com.android.chrome",
                shortFold = duolingo,
                wideFold = null
            )
        ).isEqualTo(duolingo)
    }

    @Test
    fun `ruhig offene App - erst die weite Baseline findet sie`() {
        // Der Nutzerfall: App seit Minuten offen, keine Übergangs-Events in
        // 2 Minuten, Service (noch) nicht am Pollen.
        assertThat(
            AppForegroundResolution.pick(
                snapshotFresh = false,
                snapshotPackage = null,
                shortFold = null,
                wideFold = duolingo
            )
        ).isEqualTo(duolingo)
    }

    @Test
    fun `alle Quellen leer - Ergebnis null`() {
        assertThat(
            AppForegroundResolution.pick(
                snapshotFresh = false,
                snapshotPackage = null,
                shortFold = null,
                wideFold = null
            )
        ).isNull()
    }
}
