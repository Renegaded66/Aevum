package com.d_drostes_apps.aevum.domain.digital

import com.d_drostes_apps.aevum.data.model.AppLimit
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * M18.131: Tests für die 5-Minuten-Vorwarnung vor einer App-Sperre.
 *
 * Auftrag: „…dass 5 Minuten vor Erreichen des Limits eine
 * Handybenachrichtigung von Aevum gesendet wird, dass die App in 5 Minuten
 * gesperrt wird."
 *
 * Zwei Dinge müssen stimmen, sonst ist die Warnung falsch:
 *  1. Der ZEITPUNKT (Restzeit <= 5 min) — nicht eine Prozent-Schwelle, die
 *     je nach Limit-Länge 2 bis 12 Minuten vorher meldete.
 *  2. Die AUSNAHMEN. Ein ALWAYS_ALLOW-App oder ein Limit außerhalb seines
 *     Zeitfensters sperrt nie — eine Warnung dort wäre schlicht gelogen.
 */
class AppLimitWarningTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    /** 18.09.2026, 15:00 — liegt in keinem Zeitfenster-Randfall. */
    private val afternoon: Long =
        LocalDateTime.of(LocalDate.of(2026, 9, 18), LocalTime.of(15, 0))
            .atZone(zone).toInstant().toEpochMilli()

    private fun limit(
        minutes: Int = 60,
        enabled: Boolean = true,
        exceptionType: String = AppLimit.EXCEPTION_NONE,
        windowStartMin: Int = 22 * 60,
        windowEndMin: Int = 6 * 60
    ) = AppLimit(
        packageName = "com.example.app",
        limitMinutes = minutes,
        enabled = enabled,
        exceptionType = exceptionType,
        windowStartMin = windowStartMin,
        windowEndMin = windowEndMin,
        updatedAt = 0L
    )

    private fun min(n: Int) = n * 60_000L

    // ── Der Kernfall aus dem Auftrag ───────────────────────────────────

    @Test
    fun `5 Minuten Restzeit loest die Warnung aus`() {
        val used = min(55) // Limit 60 → 5 min Restzeit
        assertThat(AppLimitChecker.isWarningDue(limit(), used, afternoon)).isTrue()
    }

    @Test
    fun `mehr als 5 Minuten Restzeit loest noch keine Warnung aus`() {
        val used = min(54) // 6 min Restzeit
        assertThat(AppLimitChecker.isWarningDue(limit(), used, afternoon)).isFalse()
    }

    @Test
    fun `erreichtes Limit loest keine Vorwarnung mehr aus`() {
        val used = min(60)
        assertThat(AppLimitChecker.isWarningDue(limit(), used, afternoon)).isFalse()
    }

    @Test
    fun `ueberschrittenes Limit loest keine Vorwarnung mehr aus`() {
        val used = min(75)
        assertThat(AppLimitChecker.isWarningDue(limit(), used, afternoon)).isFalse()
    }

    /**
     * Der eigentliche Grund für die Ablösung der 80-%-Schwelle: bei einem
     * 60-Minuten-Limit lag sie bei 12 Minuten Restzeit, bei einem
     * 10-Minuten-Limit bei 2 Minuten. Die Warnung muss aber in beiden
     * Fällen bei 5 Minuten Restzeit kommen.
     */
    @Test
    fun `Schwelle ist zeitbasiert und nicht prozentbasiert`() {
        // 60-min-Limit: bei 80 % (48 min verbraucht) wären es noch 12 min
        // Restzeit → die alte Logik warnte hier VIEL zu früh, die neue nicht.
        assertThat(AppLimitChecker.isWarningDue(limit(minutes = 60), min(48), afternoon)).isFalse()

        // 10-min-Limit: bei 80 % (8 min verbraucht) wären es 2 min Restzeit
        // → die alte Logik warnte hier zu SPÄT. Die neue warnt schon bei
        // 5 min Restzeit (5 min verbraucht), also drei Minuten früher.
        assertThat(AppLimitChecker.isWarningDue(limit(minutes = 10), min(5), afternoon)).isTrue()
        // Und sie warnt noch nicht bei 4 min Restzeit (6 min verbraucht).
        assertThat(AppLimitChecker.isWarningDue(limit(minutes = 10), min(6), afternoon)).isTrue()
        assertThat(AppLimitChecker.isWarningDue(limit(minutes = 10), min(3), afternoon)).isFalse()
    }

    // ── Ausnahmen: keine Warnung für eine Sperre, die nie kommt ────────

    @Test
    fun `ALWAYS_ALLOW loest keine Warnung aus`() {
        val used = min(55)
        assertThat(
            AppLimitChecker.isWarningDue(
                limit(exceptionType = AppLimit.EXCEPTION_ALWAYS_ALLOW), used, afternoon
            )
        ).isFalse()
    }

    @Test
    fun `TIME_WINDOW ausserhalb des Fensters loest keine Warnung aus`() {
        // Fenster 22:00–06:00, Bezugszeit 15:00 → außerhalb.
        val used = min(55)
        assertThat(
            AppLimitChecker.isWarningDue(
                limit(exceptionType = AppLimit.EXCEPTION_TIME_WINDOW), used, afternoon
            )
        ).isFalse()
    }

    @Test
    fun `TIME_WINDOW innerhalb des Fensters loest die Warnung aus`() {
        val nighttime = LocalDateTime.of(LocalDate.of(2026, 9, 18), LocalTime.of(23, 0))
            .atZone(zone).toInstant().toEpochMilli()
        val used = min(55)
        assertThat(
            AppLimitChecker.isWarningDue(
                limit(exceptionType = AppLimit.EXCEPTION_TIME_WINDOW), used, nighttime
            )
        ).isTrue()
    }

    /** Mitternachts-übergreifendes Fenster: 05:00 liegt im Fenster 22:00–06:00. */
    @Test
    fun `TIME_WINDOW ueber Mitternacht ist um 5 Uhr aktiv`() {
        val earlyMorning = LocalDateTime.of(LocalDate.of(2026, 9, 19), LocalTime.of(5, 0))
            .atZone(zone).toInstant().toEpochMilli()
        val used = min(55)
        assertThat(
            AppLimitChecker.isWarningDue(
                limit(exceptionType = AppLimit.EXCEPTION_TIME_WINDOW), used, earlyMorning
            )
        ).isTrue()
    }

    // ── Inaktive Limits ────────────────────────────────────────────────

    @Test
    fun `deaktiviertes Limit loest keine Warnung aus`() {
        assertThat(AppLimitChecker.isWarningDue(limit(enabled = false), min(55), afternoon)).isFalse()
    }

    @Test
    fun `kein Limit loest keine Warnung aus`() {
        assertThat(AppLimitChecker.isWarningDue(null, min(55), afternoon)).isFalse()
    }

    @Test
    fun `Limit von 0 Minuten loest keine Warnung aus`() {
        assertThat(AppLimitChecker.isWarningDue(limit(minutes = 0), 0L, afternoon)).isFalse()
    }

    // ── Konfigurierbarer Vorlauf ───────────────────────────────────────

    @Test
    fun `Vorlauf ist konfigurierbar`() {
        val used = min(50) // 10 min Restzeit
        assertThat(AppLimitChecker.isWarningDue(limit(), used, afternoon, leadMinutes = 10)).isTrue()
        assertThat(AppLimitChecker.isWarningDue(limit(), used, afternoon, leadMinutes = 5)).isFalse()
    }

    @Test
    fun `Vorlauf 0 schaltet die Warnung ab`() {
        assertThat(AppLimitChecker.isWarningDue(limit(), min(59), afternoon, leadMinutes = 0)).isFalse()
    }

    // ── Der Standardwert selbst ────────────────────────────────────────

    @Test
    fun `Standard-Vorlauf betraegt 5 Minuten`() {
        assertThat(AppLimitChecker.WARNING_LEAD_MINUTES).isEqualTo(5)
    }

    /**
     * Grenzfall: exakt 5 Minuten Restzeit ist eingeschlossen („in 5 Minuten
     * gesperrt" soll auch dann gelten, wenn die Prüfung genau trifft).
     */
    @Test
    fun `exakt 5 Minuten Restzeit ist eingeschlossen`() {
        assertThat(
            AppLimitChecker.isWarningDue(limit(), limit().limitMinutes * 60_000L - min(5), afternoon)
        ).isTrue()
    }

    /**
     * Abgrenzung zur Sperre: dicht unter dem Limit darf nicht beides
     * gleichzeitig gelten (weder Warnung noch Sperre doppelt).
     */
    @Test
    fun `Warnung und Sperre schliessen sich aus`() {
        val l = limit()
        for (usedMin in 0..80) {
            val used = min(usedMin)
            val blocked = AppLimitChecker.isBlocked(l, used, afternoon)
            val warned = AppLimitChecker.isWarningDue(l, used, afternoon)
            assertThat(blocked && warned).isFalse()
        }
    }

    /**
     * Eine Sekunde vor dem Limit muss die Warnung noch greifen — sonst
     * gäbe es ein Fenster, in dem der Nutzer ungewarnt gesperrt wird.
     */
    @Test
    fun `eine Sekunde vor dem Limit ist die Warnung aktiv`() {
        val used = limit().limitMinutes * 60_000L - 1_000L
        assertThat(AppLimitChecker.isWarningDue(limit(), used, afternoon)).isTrue()
    }
}
