package com.d_drostes_apps.aevum.domain.digital

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * M18.131: Tests für den Wächter der Limit-Vorwarnung.
 *
 * WARUM DIESE PERSISTENZ NÖTIG IST: der AppBlockService hielt die
 * „schon gewarnt"-Menge vorher nur im Speicher. Der Service wird täglich
 * neu gestartet (Reboot, Limit-Änderung, Prozess-Tod) — danach bekam der
 * Nutzer dieselbe Warnung erneut, was wie eine neue nahende Sperre wirkt.
 *
 * Der Tages-Key löst das ohne Aufräumjob: beim Datumswechsel greift die
 * Warnung automatisch wieder, alte Schlüssel bleiben einfach liegen.
 */
class LimitWarningGuardTest {

    private class FakeStore : LimitWarningGuard.Store {
        val keys = mutableSetOf<String>()
        override fun contains(key: String) = key in keys
        override fun put(key: String) { keys += key }
        override fun remove(key: String) { keys -= key }
    }

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun at(day: Int, hour: Int = 15): Long =
        LocalDateTime.of(LocalDate.of(2026, 9, day), LocalTime.of(hour, 0))
            .atZone(zone).toInstant().toEpochMilli()

    // ── Kernverhalten: genau einmal pro App und Tag ────────────────────

    @Test
    fun `erste Warnung an einem Tag wird als neu gemeldet`() {
        val guard = LimitWarningGuard(FakeStore())
        assertThat(guard.markWarned("com.example.app", at(18))).isTrue()
    }

    @Test
    fun `zweite Warnung derselben App am selben Tag wird nicht gemeldet`() {
        val guard = LimitWarningGuard(FakeStore())
        assertThat(guard.markWarned("com.example.app", at(18))).isTrue()
        assertThat(guard.markWarned("com.example.app", at(18))).isFalse()
    }

    /**
     * Der eigentliche Fehlerfall: ein neuer Guard (neuer Service-Start)
     * über demselben Store darf die Warnung NICHT erneut senden.
     */
    @Test
    fun `Warnung ueberlebt einen Neustart des Guards`() {
        val store = FakeStore()
        assertThat(LimitWarningGuard(store).markWarned("com.example.app", at(18))).isTrue()
        // Service neu gestartet → neue Instanz, gleicher persistenter Store.
        assertThat(LimitWarningGuard(store).markWarned("com.example.app", at(18))).isFalse()
    }

    @Test
    fun `andere App wird unabhaengig gewarnt`() {
        val guard = LimitWarningGuard(FakeStore())
        assertThat(guard.markWarned("com.example.a", at(18))).isTrue()
        assertThat(guard.markWarned("com.example.b", at(18))).isTrue()
    }

    // ── Tageswechsel ───────────────────────────────────────────────────

    @Test
    fun `am naechsten Tag greift die Warnung wieder`() {
        val guard = LimitWarningGuard(FakeStore())
        assertThat(guard.markWarned("com.example.app", at(18))).isTrue()
        assertThat(guard.markWarned("com.example.app", at(19))).isTrue()
    }

    /**
     * Der Tageswechsel richtet sich nach der LOKALEN Zeitzone: 23:59 und
     * 00:01 sind zwei verschiedene Tage (sonst wäre die Warnung über den
     * Tageswechsel hinweg unterdrückt).
     */
    @Test
    fun `Tageswechsel um lokale Mitternacht`() {
        val guard = LimitWarningGuard(FakeStore())
        val beforeMidnight = at(18, hour = 23)
        val afterMidnight = at(19, hour = 0)
        assertThat(guard.markWarned("com.example.app", beforeMidnight)).isTrue()
        assertThat(guard.markWarned("com.example.app", afterMidnight)).isTrue()
    }

    @Test
    fun `mehrfache Pruefung innerhalb einer Minute warnt nur einmal`() {
        val guard = LimitWarningGuard(FakeStore())
        val base = at(18)
        val results = (0..5).map { guard.markWarned("com.example.app", base + it * 1_000L) }
        assertThat(results.count { it }).isEqualTo(1)
    }

    // ── Abfrage ohne Nebenwirkung ──────────────────────────────────────

    @Test
    fun `wasWarnedToday aendert nichts`() {
        val guard = LimitWarningGuard(FakeStore())
        assertThat(guard.wasWarnedToday("com.example.app", at(18))).isFalse()
        // Die reine Abfrage darf NICHT markieren.
        assertThat(guard.wasWarnedToday("com.example.app", at(18))).isFalse()
        // Erst markWarned setzt den Zustand.
        assertThat(guard.markWarned("com.example.app", at(18))).isTrue()
        assertThat(guard.wasWarnedToday("com.example.app", at(18))).isTrue()
    }

    // ── Rücknahme ──────────────────────────────────────────────────────

    /** Nach „Limit erhöht" soll die Warnung für den neuen Wert wieder möglich sein. */
    @Test
    fun `clear gibt die Warnung wieder frei`() {
        val guard = LimitWarningGuard(FakeStore())
        assertThat(guard.markWarned("com.example.app", at(18))).isTrue()
        guard.clear("com.example.app", at(18))
        assertThat(guard.markWarned("com.example.app", at(18))).isTrue()
    }

    @Test
    fun `clear betrifft nur denselben Tag`() {
        val guard = LimitWarningGuard(FakeStore())
        guard.markWarned("com.example.app", at(18))
        guard.markWarned("com.example.app", at(19))
        guard.clear("com.example.app", at(18))
        // Der 19. ist noch markiert.
        assertThat(guard.wasWarnedToday("com.example.app", at(19))).isTrue()
        assertThat(guard.wasWarnedToday("com.example.app", at(18))).isFalse()
    }

    // ── Schlüsselformat ────────────────────────────────────────────────

    @Test
    fun `Schluessel enthaelt Datum und Paketnamen`() {
        val guard = LimitWarningGuard(FakeStore())
        val key = guard.key("com.example.app", at(18))
        assertThat(key).contains("2026-09-18")
        assertThat(key).contains("com.example.app")
    }

    /** Zwei Apps am selben Tag dürfen nie denselben Schlüssel bekommen. */
    @Test
    fun `Schluessel kollidieren nicht zwischen Apps`() {
        val guard = LimitWarningGuard(FakeStore())
        assertThat(guard.key("com.example.a", at(18)))
            .isNotEqualTo(guard.key("com.example.b", at(18)))
    }

    /** Zwei Tage dürfen für dieselbe App nie denselben Schlüssel ergeben. */
    @Test
    fun `Schluessel kollidieren nicht zwischen Tagen`() {
        val guard = LimitWarningGuard(FakeStore())
        assertThat(guard.key("com.example.app", at(18)))
            .isNotEqualTo(guard.key("com.example.app", at(19)))
    }
}
