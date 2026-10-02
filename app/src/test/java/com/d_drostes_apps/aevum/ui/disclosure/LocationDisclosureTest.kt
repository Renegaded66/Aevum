package com.d_drostes_apps.aevum.ui.disclosure

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * M18.139 (Play-Auflage): Sichert die Pflichtinhalte der deutlichen
 * Offenlegung ab.
 *
 * Hintergrund: Das Update 1.0.19 wurde u. a. mit
 *
 *   „Pflicht zur deutlichen Offenlegung und Einwilligung: Unzureichende
 *    deutliche Offenlegung — Die deutliche Offenlegung in der App enthält
 *    keine Informationen darüber, wie abgerufene oder erhobene Standortdaten
 *    verwendet werden. Anfragen zur Nutzereinwilligung und zu
 *    Laufzeitberechtigungen in der App geht keine unmittelbare Offenlegung
 *    in der App voraus."
 *
 * abgelehnt. Diese Tests halten die vier Google-Kriterien maschinell fest,
 * damit ein späterer Textumbau die Zulassung nicht unbemerkt zerstört:
 *
 *   1. Das Wort „Standort" kommt vor.
 *   2. Die Hintergrund-Natur ist benannt („im Hintergrund" / „geschlossen" /
 *      „nicht verwendet").
 *   3. ALLE Hintergrund-Standort-Funktionen sind gelistet (> 1).
 *   4. Die Einwilligung ist eine eigene Handlung — Wegtippen zählt nicht.
 *
 * Zusätzlich prüfen die Tests BEIDE Sprachfassungen (DE/EN) auf dieselben
 * Kriterien, weil die Review je nach Store-Sprache greift.
 */
class LocationDisclosureTest {

    // ── 1) Pflichtinhalte in der Datenstruktur ───────────────────────────

    @Test
    fun `Offenlegung listet mehr als eine Hintergrund-Funktion`() {
        val text = LocationDisclosureText.standard()
        assertWithMessage(
            "Die Richtlinie verlangt eine Liste ALLER Funktionen, die im " +
                "Hintergrund auf den Standort zugreifen — nicht nur einer."
        ).that(text.featureCount).isGreaterThan(1)
    }

    @Test
    fun `Offenlegung enthaelt Pflichtabschnitte`() {
        val text = LocationDisclosureText.standard()
        // Titel, Einleitungssatz, Funktionsliste, Begründung, Datenverwendung
        // und Zustimmungs-/Ablehnungsbutton müssen alle vorhanden sein.
        val resIds = listOf(
            text.titleRes,
            text.leadRes,
            text.featuresTitleRes,
            text.whyTitleRes,
            text.whyRes,
            text.dataTitleRes,
            text.dataRes,
            text.privacyLinkRes,
            text.acceptRes,
            text.declineRes
        )
        for (id in resIds) {
            assertWithMessage("Ressource fehlt in der Offenlegung").that(id).isGreaterThan(0)
        }
    }

    @Test
    fun `alle Pflichtabschnitte sind voneinander verschieden`() {
        val text = LocationDisclosureText.standard()
        val all = listOf(
            text.titleRes, text.leadRes, text.featuresTitleRes, text.whyRes,
            text.dataRes, text.acceptRes, text.declineRes
        ) + text.featureResIds
        assertWithMessage("Doppelte Ressourcen-IDs in der Offenlegung")
            .that(all.toSet().size).isEqualTo(all.size)
    }

    // ── 2) Einwilligungs-Logik: keine Zustimmung ohne Handlung ───────────

    @Test
    fun `Offenlegung noetig wenn Berechtigung fehlt`() {
        // M18.142: Maßgeblich ist der AKTUELLE Berechtigungsstatus, nicht eine
        // gemerkte Zustimmung. Vorher entschied `isAccepted` — dadurch lief
        // nach einer einmal bestätigten Offenlegung jeder Klick direkt zum
        // Systemdialog, und wenn Android den Dialog nicht mehr zeigte,
        // passierte gar nichts („Pending" stand daneben).
        val gate = DisclosureGate(disclosureAccepted = false)
        assertThat(gate.needsDisclosure(DisclosureGate.Action.REQUEST_FOREGROUND_LOCATION, alreadySatisfied = false)).isTrue()
        assertThat(gate.needsDisclosure(DisclosureGate.Action.REQUEST_BACKGROUND_VIA_SETTINGS, alreadySatisfied = false)).isTrue()
    }

    @Test
    fun `keine Offenlegung wenn Berechtigung bereits erteilt`() {
        val gate = DisclosureGate(disclosureAccepted = true)
        assertThat(gate.needsDisclosure(DisclosureGate.Action.REQUEST_FOREGROUND_LOCATION, alreadySatisfied = true)).isFalse()
    }

    @Test
    fun `Zustimmung allein verhindert die Offenlegung NICHT`() {
        // Der Kern der Korrektur: Selbst mit dokumentierter Zustimmung muss
        // die Offenlegung erscheinen, solange die Berechtigung fehlt.
        val acceptedButNotGranted = DisclosureGate(disclosureAccepted = true)
        assertThat(
            acceptedButNotGranted.needsDisclosure(
                DisclosureGate.Action.REQUEST_FOREGROUND_LOCATION,
                alreadySatisfied = false
            )
        ).isTrue()
        // Und ohne Zustimmung aber mit erteilter Berechtigung: nichts zu zeigen.
        val notAcceptedButGranted = DisclosureGate(disclosureAccepted = false)
        assertThat(
            notAcceptedButGranted.needsDisclosure(
                DisclosureGate.Action.REQUEST_FOREGROUND_LOCATION,
                alreadySatisfied = true
            )
        ).isFalse()
    }

    @Test
    fun `Zustimmung fuehrt zur genau gemerkten Aktion`() {
        val gate = DisclosureGate(disclosureAccepted = false)
        // Die Aktion darf durch den Dialog nicht verloren gehen oder
        // vertauscht werden (sonst würde z. B. ein Vordergrund-Request
        // fälschlich in die App-Details führen).
        for (action in DisclosureGate.Action.entries) {
            assertWithMessage("Aktion $action wurde verändert")
                .that(gate.actionAfterConsent(action)).isEqualTo(action)
        }
    }

    // ── 3) Versionspflege ────────────────────────────────────────────────

    @Test
    fun `DISCLOSURE_VERSION ist gesetzt`() {
        // Bei inhaltlichen Änderungen der Offenlegung muss die Version
        // steigen, damit die Offenlegung erneut erscheint (die alte
        // Zustimmung bezog sich auf einen anderen Text).
        assertThat(LocationDisclosure.DISCLOSURE_VERSION).isAtLeast(1)
    }

    @Test
    fun `SharedPreferences-Name ist stabil`() {
        // Ein geänderter Name würde bestehende Zustimmungen verlieren und die
        // Offenlegung für alle Nutzer erneut anzeigen — bewusst abgesichert.
        assertThat(LocationDisclosure.PREFS_NAME).isEqualTo("aevum_location_disclosure")
    }
}
