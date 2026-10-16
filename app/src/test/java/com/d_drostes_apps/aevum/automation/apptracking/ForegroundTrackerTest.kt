package com.d_drostes_apps.aevum.automation.apptracking

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.151: Faltung der UsageStats-Foreground-Events — pure Logik,
 * JVM-getestet. Nutzer-sichtbare Semantik (siehe [ForegroundTracker]):
 * „Vordergrund" ist die zuletzt RESUMED/FOREGROUND-gemeldete App, bis
 * SIE SELBST pausiert/stoppt — Events anderer Apps ändern nichts.
 */
class ForegroundTrackerTest {

    private val app = "com.instagram.android"
    private val other = "com.android.chrome"

    @Test
    fun `resumed setzt den Vordergrund`() {
        assertThat(
            ForegroundTracker.next(
                current = null,
                packageName = app,
                isForegroundEvent = true,
                isBackgroundEvent = false
            )
        ).isEqualTo(app)
    }

    @Test
    fun `resumed wechselt von einer anderen App`() {
        assertThat(
            ForegroundTracker.next(
                current = other,
                packageName = app,
                isForegroundEvent = true,
                isBackgroundEvent = false
            )
        ).isEqualTo(app)
    }

    @Test
    fun `paused der aktuellen App leert den Vordergrund`() {
        assertThat(
            ForegroundTracker.next(
                current = app,
                packageName = app,
                isForegroundEvent = false,
                isBackgroundEvent = true
            )
        ).isNull()
    }

    @Test
    fun `stopped der aktuellen App leert den Vordergrund`() {
        assertThat(
            ForegroundTracker.next(
                current = app,
                packageName = app,
                isForegroundEvent = false,
                isBackgroundEvent = true
            )
        ).isNull()
    }

    @Test
    fun `background-Event einer ANDEREN App laesst den Vordergrund unveraendert`() {
        assertThat(
            ForegroundTracker.next(
                current = app,
                packageName = other,
                isForegroundEvent = false,
                isBackgroundEvent = true
            )
        ).isEqualTo(app)
    }

    @Test
    fun `unbeteiligte Events lassen den Vordergrund unveraendert`() {
        assertThat(
            ForegroundTracker.next(
                current = app,
                packageName = app,
                isForegroundEvent = false,
                isBackgroundEvent = false
            )
        ).isEqualTo(app)
    }

    @Test
    fun `pause bei leerem Vordergrund bleibt leer`() {
        assertThat(
            ForegroundTracker.next(
                current = null,
                packageName = other,
                isForegroundEvent = false,
                isBackgroundEvent = true
            )
        ).isNull()
    }

    @Test
    fun `sequenz resume pause resume ergibt die letzte App`() {
        var current: String? = null
        current = ForegroundTracker.next(current, app, isForegroundEvent = true, isBackgroundEvent = false)
        current = ForegroundTracker.next(current, app, isForegroundEvent = false, isBackgroundEvent = true)
        current = ForegroundTracker.next(current, other, isForegroundEvent = true, isBackgroundEvent = false)
        assertThat(current).isEqualTo(other)
    }
}
