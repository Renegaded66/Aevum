package com.d_drostes_apps.aevum.automation.apptracking

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.151/M20/M21: Faltung der UsageStats-Foreground-Events — pure Logik,
 * JVM-getestet. Nutzer-sichtbare Semantik:
 * „Vordergrund" ist die zuletzt RESUMED/FOREGROUND-gemeldete Activity
 * (Paket + Klasse), bis KEINE Activity ihres Pakets mehr offen ist.
 *
 * M20: Activity-Ebene — Splash→Main-Wechsel darf den Vordergrund nicht
 * leeren (Nutzer-Report: nach Neustart startete „Digital" statt der
 * zugewiesenen Activity).
 *
 * M21: offene Activities als MENGE. Ein `PAUSED`/`STOPPED` ohne brauchbaren
 * Klassennamen (OEM liefert `className == null`) darf eine noch offene
 * Haupt-Activity nicht mit abräumen.
 */
class ForegroundTrackerTest {

    private val app = "com.instagram.android"
    private val other = "com.android.chrome"
    private val appMain = "com.instagram.android.MainActivity"
    private val appSplash = "com.instagram.android.SplashActivity"

    private fun fg(state: ForegroundState, pkg: String, cls: String?) = ForegroundTracker.next(
        state = state, packageName = pkg, className = cls,
        isForegroundEvent = true, isBackgroundEvent = false
    )

    private fun bg(state: ForegroundState, pkg: String, cls: String?) = ForegroundTracker.next(
        state = state, packageName = pkg, className = cls,
        isForegroundEvent = false, isBackgroundEvent = true
    )

    private fun none(state: ForegroundState, pkg: String, cls: String?) = ForegroundTracker.next(
        state = state, packageName = pkg, className = cls,
        isForegroundEvent = false, isBackgroundEvent = false
    )

    @Test
    fun `resumed setzt den Vordergrund`() {
        assertThat(fg(ForegroundState.EMPTY, app, appMain).current)
            .isEqualTo(Foreground(app, "MainActivity"))
    }

    @Test
    fun `resumed wechselt von einer anderen App`() {
        val state = fg(ForegroundState( Foreground(other, "MainActivity")), app, appMain)
        assertThat(state.current).isEqualTo(Foreground(app, "MainActivity"))
    }

    @Test
    fun `paused der aktuellen Activity leert den Vordergrund`() {
        val state = fg(ForegroundState.EMPTY, app, appMain)
        assertThat(bg(state, app, appMain).current).isNull()
    }

    @Test
    fun `stopped der aktuellen Activity leert den Vordergrund`() {
        val state = fg(ForegroundState.EMPTY, app, appMain)
        assertThat(bg(state, app, appMain).current).isNull()
    }

    @Test
    fun `background-Event einer ANDEREN App laesst den Vordergrund unveraendert`() {
        val state = fg(ForegroundState.EMPTY, app, appMain)
        assertThat(bg(state, other, "com.android.chrome.Main").current)
            .isEqualTo(Foreground(app, "MainActivity"))
    }

    @Test
    fun `unbeteiligte Events lassen den Vordergrund unveraendert`() {
        val state = fg(ForegroundState.EMPTY, app, appMain)
        assertThat(none(state, app, appMain)).isEqualTo(state)
    }

    @Test
    fun `pause bei leerem Vordergrund bleibt leer`() {
        assertThat(bg(ForegroundState.EMPTY, other, null).current).isNull()
    }

    @Test
    fun `sequenz resume pause resume ergibt die letzte App`() {
        var state = fg(ForegroundState.EMPTY, app, appMain)
        state = bg(state, app, appMain)
        state = fg(state, other, "com.android.chrome.Main")
        assertThat(state.current).isEqualTo(Foreground(other, "Main"))
    }

    // ── M20: Activity-Ebene — der gemeldete Nutzerfall ──────────────────

    @Test
    fun `M20 splash zu main - trailing STOPPED des Splash leert den Vordergrund NICHT`() {
        var state = fg(ForegroundState.EMPTY, app, appSplash)
        state = fg(state, app, appMain)
        // Splash räumt sich auf — auf Paket-Ebene hätte das den Vordergrund
        // fälschlich geleert (der Nutzer-Report).
        state = bg(state, app, appSplash)
        assertThat(state.current).isEqualTo(Foreground(app, "MainActivity"))
    }

    @Test
    fun `M20 Splash-Events in beliebiger Reihenfolge enden auf der Haupt-Activity`() {
        // Reihenfolge-Variante: pause(splash) vor resume(main), dann stop(splash).
        var state = fg(ForegroundState.EMPTY, app, appSplash)
        state = bg(state, app, appSplash)
        state = fg(state, app, appMain)
        state = bg(state, app, appSplash)
        assertThat(state.current).isEqualTo(Foreground(app, "MainActivity"))
        // Und die aktuell gemerkte Activity selbst räumt weiterhin.
        state = bg(state, app, appMain)
        assertThat(state.current).isNull()
    }

    @Test
    fun `M20 Kurz- und Langform derselben Activity matchen`() {
        // MOVE_TO_FOREGROUND liefert z. B. "com.instagram.android.MainActivity",
        // ACTIVITY_RESUMED ".MainActivity" — beide Formen müssen sich erkennen.
        val state = fg(ForegroundState.EMPTY, app, ".MainActivity")
        assertThat(state.current).isEqualTo(Foreground(app, "MainActivity"))
        assertThat(bg(state, app, appMain).current).isNull()
    }

    @Test
    fun `M20 fehlende Klassen fallen auf Paket-Ebene zurueck (altes Verhalten)`() {
        val state = fg(ForegroundState.EMPTY, app, null)
        assertThat(state.current).isEqualTo(Foreground(app, null))
        // Background-Event der App ohne Klasse räumt weiterhin (M18.67-Semantik).
        assertThat(bg(state, app, appMain).current).isNull()
    }

    @Test
    fun `M20 Events ohne Paketnamen aendern nichts`() {
        val state = fg(ForegroundState.EMPTY, app, appMain)
        assertThat(fg(state, "", null)).isEqualTo(state)
        assertThat(bg(state, "", null)).isEqualTo(state)
    }

    // ── M21: offene Activities als Menge ────────────────────────────────

    @Test
    fun `M21 PAUSED ohne Klassennamen raeumt die offene Haupt-Activity NICHT ab`() {
        // Nutzerfall (17.10.2026): Duolingo startet, Splash pausiert ohne
        // className — bis M20 fiel die Faltung dann auf Paket-Ebene zurück
        // und leerte den Vordergrund mitten in der App. Die App-Aufzeichnung
        // startete „Digital" statt „Lernen".
        var state = fg(ForegroundState.EMPTY, app, appSplash)
        state = fg(state, app, appMain)
        state = bg(state, app, null) // PAUSED ohne Klasse
        assertThat(state.current).isEqualTo(Foreground(app, "MainActivity"))
        // Erst wenn die Haupt-Activity selbst endet, ist der Vordergrund leer.
        state = bg(state, app, appMain)
        assertThat(state.current).isNull()
    }

    @Test
    fun `M21 App-Wechsel verwirft die offenen Activities des alten Pakets`() {
        var state = fg(ForegroundState.EMPTY, app, appSplash)
        state = fg(state, app, appMain)
        state = fg(state, other, "com.android.chrome.Main")
        assertThat(state.openActivities.keys).containsExactly(other)
        // Ein verspaetetes STOPPED der alten App aendert nichts mehr.
        assertThat(bg(state, app, appSplash).current).isEqualTo(Foreground(other, "Main"))
    }

    @Test
    fun `M21 Stop eines nie gestarteten Splash raeumt die Haupt-Activity nicht`() {
        val state = fg(ForegroundState.EMPTY, app, appMain)
        assertThat(bg(state, app, appSplash).current).isEqualTo(Foreground(app, "MainActivity"))
    }
}
