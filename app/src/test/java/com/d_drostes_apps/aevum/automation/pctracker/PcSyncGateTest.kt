package com.d_drostes_apps.aevum.automation.pctracker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.147: Drossel des stillen PC-Syncs (User: „zuverlässiger, aber ohne dass
 * man es merkt — und nicht zu viel Akku").
 *
 * Der App-Öffnen-Kick darf die App nie in einen Sync-Sturm verwandeln: pro
 * Mindestabstand genau ein VERSUCH (Erfolg wie Fehlschlag zählen gleich),
 * kein Versuch bei frischem Sync, ohne Kopplung oder bei ausgeschaltetem
 * Schalter.
 */
class PcSyncGateTest {

    private val t0 = 1_800_000_000_000L

    @Test
    fun ohneKopplungKeinVersuch() {
        assertThat(
            PcSyncGate.shouldAttemptNow(
                nowMs = t0, lastAttemptMs = 0L, lastSyncMs = 0L,
                coupled = false, autoSyncEnabled = true
            )
        ).isFalse()
    }

    @Test
    fun schalterAusKeinVersuch() {
        assertThat(
            PcSyncGate.shouldAttemptNow(
                nowMs = t0, lastAttemptMs = 0L, lastSyncMs = 0L,
                coupled = true, autoSyncEnabled = false
            )
        ).isFalse()
    }

    @Test
    fun nochNieSynchronisiertErlaubtDenKick() {
        assertThat(
            PcSyncGate.shouldAttemptNow(
                nowMs = t0, lastAttemptMs = 0L, lastSyncMs = 0L,
                coupled = true, autoSyncEnabled = true
            )
        ).isTrue()
    }

    @Test
    fun frischerSyncBlockiertDenKick() {
        // Vor 30 Sekunden erfolgreich synchronisiert → nichts nachholen.
        assertThat(
            PcSyncGate.shouldAttemptNow(
                nowMs = t0, lastAttemptMs = t0 - 20 * 60_000L, lastSyncMs = t0 - 30_000L,
                coupled = true, autoSyncEnabled = true
            )
        ).isFalse()
    }

    /**
     * PC 1.4.0 (Devon: „spätestens wenn ich aevum auf dem handy öffne sollte er
     * im hintergrund synchronisieren"): Ein Sync von vor 3 Minuten darf das
     * App-Öffnen NICHT mehr blockieren — genau dieser Fall (Desktop „nicht
     * gekoppelt" trotz geöffneter App) war der gemeldete Fehler.
     */
    @Test
    fun syncVorDreiMinutenErlaubtDenKickBeimAppOeffnen() {
        assertThat(
            PcSyncGate.shouldAttemptNow(
                nowMs = t0, lastAttemptMs = t0 - 20 * 60_000L, lastSyncMs = t0 - 3 * 60_000L,
                coupled = true, autoSyncEnabled = true
            )
        ).isTrue()
    }

    /** Und ein frischer Sync (unter 60 s) blockiert weiterhin — kein Doppel-Sync. */
    @Test
    fun syncVorDreissigSekundenBlockiertNoch() {
        assertThat(
            PcSyncGate.shouldAttemptNow(
                nowMs = t0, lastAttemptMs = t0 - 20 * 60_000L, lastSyncMs = t0 - 30_000L,
                coupled = true, autoSyncEnabled = true
            )
        ).isFalse()
    }

    @Test
    fun kuerzlicherFehlversuchBlockiertDenKick() {
        // PC war vor 90 Sekunden nicht erreichbar — nicht sofort erneut funken
        // (Mindestabstand 2 Minuten, Sturm-Schutz bleibt).
        assertThat(
            PcSyncGate.shouldAttemptNow(
                nowMs = t0, lastAttemptMs = t0 - 90_000L, lastSyncMs = t0 - 7 * 60 * 60_000L,
                coupled = true, autoSyncEnabled = true
            )
        ).isFalse()
    }

    /** Ein Fehlversuch von vor 3 Minuten darf das nächste App-Öffnen nicht blockieren. */
    @Test
    fun alterFehlversuchErlaubtNachholen() {
        assertThat(
            PcSyncGate.shouldAttemptNow(
                nowMs = t0, lastAttemptMs = t0 - 3 * 60_000L, lastSyncMs = t0 - 7 * 60 * 60_000L,
                coupled = true, autoSyncEnabled = true
            )
        ).isTrue()
    }

    @Test
    fun verpassterSyncNachWartezeitErlaubtDenKick() {
        // Letzter Sync vor 20 Minuten (z. B. Doze hat den 15-Minuten-Takt
        // verschluckt), letzter Versuch ebenfalls lange her → jetzt, beim
        // App-Öffnen, nachholen.
        assertThat(
            PcSyncGate.shouldAttemptNow(
                nowMs = t0, lastAttemptMs = t0 - 20 * 60_000L, lastSyncMs = t0 - 20 * 60_000L,
                coupled = true, autoSyncEnabled = true
            )
        ).isTrue()
    }

    @Test
    fun grenzeIstExaktDerMindestabstand() {
        val gap = PcSyncGate.MIN_GAP_MS
        // Eine Millisekunde zu früh → kein Versuch.
        assertThat(
            PcSyncGate.shouldAttemptNow(
                nowMs = t0, lastAttemptMs = t0 - gap + 1, lastSyncMs = t0 - gap,
                coupled = true, autoSyncEnabled = true
            )
        ).isFalse()
        // Genau am Mindestabstand → Versuch erlaubt (letzter Sync liegt mit
        // gap=2 Minuten auch über dem Frisch-Fenster von 60 s).
        assertThat(
            PcSyncGate.shouldAttemptNow(
                nowMs = t0, lastAttemptMs = t0 - gap, lastSyncMs = t0 - gap,
                coupled = true, autoSyncEnabled = true
            )
        ).isTrue()
    }
}
