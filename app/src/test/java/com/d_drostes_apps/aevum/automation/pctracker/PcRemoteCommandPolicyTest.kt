package com.d_drostes_apps.aevum.automation.pctracker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M22: Entscheidungslogik für PC-Steuerbefehle — pure Logik.
 *
 * Nutzerfall: Am Desktop „Studium" gestartet → das Handy muss die Session
 * aufzeichnen (nicht der PC). Der Befehl darf nichts doppelt starten und
 * bei gelöschter Aktivität nicht ins Leere laufen.
 */
class PcRemoteCommandPolicyTest {

    private fun cmd(command: String = "start", key: String? = "learning") =
        PcRemoteCommandPolicy.Command(command, key)

    @Test
    fun `start mit bekannter Aktivitaet startet sie`() {
        assertThat(
            PcRemoteCommandPolicy.decide(cmd(), typeExists = true, liveActivityTypeId = null, liveIsRunning = false)
        ).isEqualTo(PcRemoteCommandPolicy.Decision.Start("learning"))
    }

    @Test
    fun `unbekannte Aktivitaet wird nicht gestartet`() {
        assertThat(
            PcRemoteCommandPolicy.decide(cmd(key = "geloescht"), typeExists = false, liveActivityTypeId = null, liveIsRunning = false)
        ).isEqualTo(PcRemoteCommandPolicy.Decision.UnknownActivity("geloescht"))
    }

    @Test
    fun `laeuft dieselbe Aktivitaet schon, wird nicht neu gestartet`() {
        assertThat(
            PcRemoteCommandPolicy.decide(cmd(), typeExists = true, liveActivityTypeId = "learning", liveIsRunning = true)
        ).isEqualTo(PcRemoteCommandPolicy.Decision.AlreadyRunning)
    }

    @Test
    fun `andere Aktivitaet laeuft - der Befehl loest sie ab`() {
        assertThat(
            PcRemoteCommandPolicy.decide(cmd(), typeExists = true, liveActivityTypeId = "digital", liveIsRunning = true)
        ).isEqualTo(PcRemoteCommandPolicy.Decision.Start("learning"))
    }

    @Test
    fun `pausierte Session gilt nicht als laufend`() {
        assertThat(
            PcRemoteCommandPolicy.decide(cmd(), typeExists = true, liveActivityTypeId = "learning", liveIsRunning = false)
        ).isEqualTo(PcRemoteCommandPolicy.Decision.Start("learning"))
    }

    @Test
    fun `unbekannte Befehle und leerer Schluessel werden ignoriert`() {
        assertThat(
            PcRemoteCommandPolicy.decide(cmd(command = "stop"), true, null, false)
        ).isEqualTo(PcRemoteCommandPolicy.Decision.Ignore)
        assertThat(
            PcRemoteCommandPolicy.decide(cmd(key = "  "), true, null, false)
        ).isEqualTo(PcRemoteCommandPolicy.Decision.Ignore)
        assertThat(
            PcRemoteCommandPolicy.decide(null, true, null, false)
        ).isEqualTo(PcRemoteCommandPolicy.Decision.Ignore)
    }
}
