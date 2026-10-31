package com.d_drostes_apps.aevum.ui.screens.onboarding

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M34 — Gate des Willkommens-Dialogs.
 *
 * Die wichtigste Zusicherung des Auftrags steckt hier: „die Änderung
 * beeinflusst also nicht bestehende Installationen". Ein Fehler an dieser
 * Stelle würde jedem Bestandsnutzer nach dem Update einen Einrichtungs-
 * Dialog vorsetzen, den er nie wollte.
 *
 * Robolectric, weil [OnboardingGate] SharedPreferences und den Paketmanager
 * braucht — beides existiert im JVM-Test nicht.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingGateTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        context.getSharedPreferences(OnboardingGate.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `frische Installation zeigt den Wizard`() {
        // Robolectric setzt beide Zeitstempel gleich → Neuinstallation.
        assertThat(OnboardingGate.shouldShow(context)).isTrue()
        assertThat(OnboardingGate.isCompleted(context)).isFalse()
    }

    @Test
    fun `nach Abschluss erscheint der Wizard nicht mehr`() {
        OnboardingGate.markCompleted(context)
        assertThat(OnboardingGate.shouldShow(context)).isFalse()
        assertThat(OnboardingGate.isCompleted(context)).isTrue()
    }

    @Test
    fun `Update einer bestehenden Installation ueberspringt den Wizard dauerhaft`() {
        // 90 Tage alter Erststart, eben aktualisiert → kein Wizard.
        val first = 1_000_000_000_000L
        val last = first + 90L * 24 * 60 * 60 * 1000
        assertThat(OnboardingGate.isFreshInstall(first, last)).isFalse()

        // Und das Abschluss-Flag wird gesetzt, damit die Prüfung stabil bleibt.
        OnboardingGate.markCompleted(context)
        assertThat(OnboardingGate.shouldShow(context)).isFalse()
    }

    @Test
    fun `gleicher Zeitstempel gilt als Neuinstallation`() {
        val first = 1_700_000_000_000L
        assertThat(OnboardingGate.isFreshInstall(first, first)).isTrue()
        // Paketmanager-Rundung: wenige Sekunden Abweichung sind normal.
        assertThat(OnboardingGate.isFreshInstall(first, first + 30_000L)).isTrue()
    }

    @Test
    fun `mehr als zwei Minuten Abstand ist ein Update`() {
        val first = 1_700_000_000_000L
        assertThat(OnboardingGate.isFreshInstall(first, first + 2 * 60_000L + 1)).isFalse()
    }

    @Test
    fun `unmoegliche Zeitstempel sind kein Neuinstallations-Signal`() {
        // Update-Zeitstempel VOR der Installation (kaputte Daten) → false.
        val first = 1_700_000_000_000L
        assertThat(OnboardingGate.isFreshInstall(first, first - 5_000L)).isFalse()
        assertThat(OnboardingGate.isFreshInstall(0L, 0L)).isTrue()
    }
}
