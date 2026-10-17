package com.d_drostes_apps.aevum.automation.apptracking

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.151/M20: Faltung der UsageStats-Foreground-Events — pure Logik,
 * JVM-getestet. Nutzer-sichtbare Semantik:
 * „Vordergrund" ist die zuletzt RESUMED/FOREGROUND-gemeldete Activity
 * (Paket + Klasse), bis DIESELBE Activity pausiert/stoppt — Events anderer
 * Activities desselben Pakets oder fremder Apps ändern nichts.
 *
 * M20-Erweiterung (Nutzer-Report: nach Handy-Neustart startete „Digital"
 * statt der App-Activity, weil die Faltung den Vordergrund verlor): Auf
 * Paket-Ebene leerte JEDES PAUSED/STOPPED derselben App den Zustand — auch
 * der Splash→Main-Wechsel INNERHALB der App. Die neuen Tests nageln die
 * Activity-Ebene und die Rückfall-Regeln fest.
 */
class ForegroundTrackerTest {

    private val app = "com.instagram.android"
    private val other = "com.android.chrome"
    private val appMain = "com.instagram.android.MainActivity"
    private val appSplash = "com.instagram.android.SplashActivity"

    @Test
    fun `resumed setzt den Vordergrund`() {
        assertThat(
            ForegroundTracker.next(
                current = null,
                packageName = app,
                className = appMain,
                isForegroundEvent = true,
                isBackgroundEvent = false
            )
        ).isEqualTo(Foreground(app, "MainActivity"))
    }

    @Test
    fun `resumed wechselt von einer anderen App`() {
        assertThat(
            ForegroundTracker.next(
                current = Foreground(other, "MainActivity"),
                packageName = app,
                className = appMain,
                isForegroundEvent = true,
                isBackgroundEvent = false
            )
        ).isEqualTo(Foreground(app, "MainActivity"))
    }

    @Test
    fun `paused der aktuellen Activity leert den Vordergrund`() {
        assertThat(
            ForegroundTracker.next(
                current = Foreground(app, "MainActivity"),
                packageName = app,
                className = appMain,
                isForegroundEvent = false,
                isBackgroundEvent = true
            )
        ).isNull()
    }

    @Test
    fun `stopped der aktuellen Activity leert den Vordergrund`() {
        assertThat(
            ForegroundTracker.next(
                current = Foreground(app, "MainActivity"),
                packageName = app,
                className = appMain,
                isForegroundEvent = false,
                isBackgroundEvent = true
            )
        ).isNull()
    }

    @Test
    fun `background-Event einer ANDEREN App laesst den Vordergrund unveraendert`() {
        assertThat(
            ForegroundTracker.next(
                current = Foreground(app, "MainActivity"),
                packageName = other,
                className = "com.android.chrome.Main",
                isForegroundEvent = false,
                isBackgroundEvent = true
            )
        ).isEqualTo(Foreground(app, "MainActivity"))
    }

    @Test
    fun `unbeteiligte Events lassen den Vordergrund unveraendert`() {
        assertThat(
            ForegroundTracker.next(
                current = Foreground(app, "MainActivity"),
                packageName = app,
                className = appMain,
                isForegroundEvent = false,
                isBackgroundEvent = false
            )
        ).isEqualTo(Foreground(app, "MainActivity"))
    }

    @Test
    fun `pause bei leerem Vordergrund bleibt leer`() {
        assertThat(
            ForegroundTracker.next(
                current = null,
                packageName = other,
                className = null,
                isForegroundEvent = false,
                isBackgroundEvent = true
            )
        ).isNull()
    }

    @Test
    fun `sequenz resume pause resume ergibt die letzte App`() {
        var current: Foreground? = null
        current = ForegroundTracker.next(current, app, appMain, isForegroundEvent = true, isBackgroundEvent = false)
        current = ForegroundTracker.next(current, app, appMain, isForegroundEvent = false, isBackgroundEvent = true)
        current = ForegroundTracker.next(current, other, "com.android.chrome.Main", isForegroundEvent = true, isBackgroundEvent = false)
        assertThat(current).isEqualTo(Foreground(other, "com.android.chrome.Main".removePrefix("com.android.chrome.")))
    }

    // ── M20: Activity-Ebene — der gemeldete Nutzerfall ──────────────────

    @Test
    fun `M20 splash zu main - trailing STOPPED des Splash leert den Vordergrund NICHT`() {
        var current: Foreground? = null
        current = ForegroundTracker.next(current, app, appSplash, true, false)
        current = ForegroundTracker.next(current, app, appMain, true, false)
        // Splash räumt sich auf — auf Paket-Ebene hätte das den Vordergrund
        // fälschlich geleert (der Nutzer-Report).
        current = ForegroundTracker.next(current, app, appSplash, false, true)
        assertThat(current).isEqualTo(Foreground(app, "MainActivity"))
    }

    @Test
    fun `M20 Splash-Events in beliebiger Reihenfolge enden auf der Haupt-Activity`() {
        // Reihenfolge-Variante: pause(splash) vor resume(main), dann stop(splash).
        var current: Foreground? = null
        current = ForegroundTracker.next(current, app, appSplash, true, false)
        current = ForegroundTracker.next(current, app, appSplash, false, true)
        current = ForegroundTracker.next(current, app, appMain, true, false)
        current = ForegroundTracker.next(current, app, appSplash, false, true)
        assertThat(current).isEqualTo(Foreground(app, "MainActivity"))
        // Und die aktuell gemerkte Activity selbst räumt weiterhin.
        current = ForegroundTracker.next(current, app, appMain, false, true)
        assertThat(current).isNull()
    }

    @Test
    fun `M20 Kurz- und Langform derselben Activity matchen`() {
        // MOVE_TO_FOREGROUND liefert z. B. "com.instagram.android.MainActivity",
        // ACTIVITY_RESUMED ".MainActivity" — beide Formen müssen sich erkennen.
        val state = ForegroundTracker.next(null, app, ".MainActivity", true, false)
        assertThat(state).isEqualTo(Foreground(app, "MainActivity"))
        val cleared = ForegroundTracker.next(state, app, appMain, false, true)
        assertThat(cleared).isNull()
    }

    @Test
    fun `M20 fehlende Klassen fallen auf Paket-Ebene zurueck (altes Verhalten)`() {
        val state = ForegroundTracker.next(null, app, null, true, false)
        assertThat(state).isEqualTo(Foreground(app, null))
        // Background-Event der App ohne Klasse räumt weiterhin (M18.67-Semantik).
        val cleared = ForegroundTracker.next(state, app, appMain, false, true)
        assertThat(cleared).isNull()
    }

    @Test
    fun `M20 Events ohne Paketnamen aendern nichts`() {
        val state = Foreground(app, "MainActivity")
        assertThat(ForegroundTracker.next(state, "", null, true, false)).isEqualTo(state)
        assertThat(ForegroundTracker.next(state, "", null, false, true)).isEqualTo(state)
    }
}
