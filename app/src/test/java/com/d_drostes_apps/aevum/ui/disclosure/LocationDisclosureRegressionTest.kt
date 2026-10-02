package com.d_drostes_apps.aevum.ui.disclosure

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * M18.142: Regression-Guard für den gemeldeten Fehler.
 *
 * ── DER BEFUND (Nutzer, 2. Oktober 2026) ──────────────────────────────
 *
 *   „Wenn ich unter Einstellungen → Trigger und Erkennung oben auf
 *    ‚Location' klicke, dann passiert nichts. Rechts ist aber ‚Pending'
 *    angegeben, also die Berechtigung wurde noch nicht erteilt. Es sollte
 *    sich zuerst der informierende Dialog öffnen und dann die Nachfrage der
 *    Berechtigung. Bei Background location öffnen sich immerhin die
 *    Einstellungen, aber auch dort fehlt vorher das informierende Pop-up."
 *
 * ── DIE URSACHE ───────────────────────────────────────────────────────
 *
 * Das Gate entschied über `LocationDisclosure.isAccepted(context)` — die
 * GEMERKTE Zustimmung — statt über den AKTUELLEN Berechtigungsstatus:
 *
 *     fun request(action, onReady) {
 *         if (LocationDisclosure.isAccepted(context)) {
 *             onReady(action)      // ← direkt zum Systemdialog, ohne Dialog
 *         } else {
 *             pendingAction = action
 *         }
 *     }
 *
 * Nach einer einmal bestätigten Offenlegung (z. B. beim App-Start) war
 * `isAccepted` dauerhaft true. Folge:
 *   • „Location": Request lief sofort, Android zeigte keinen Dialog mehr
 *     (nach Ablehnung) → es passierte scheinbar NICHTS.
 *   • „Background location": sofortiger Sprung in die Einstellungen, ohne
 *     vorherige Erklärung.
 *
 * Das widersprach exakt der Anforderung — und der Play-Auflage, die eine
 * Offenlegung VOR jedem Request verlangt, nicht nur beim ersten Mal.
 *
 * ── DIESER TEST ───────────────────────────────────────────────────────
 *
 * Er prüft die Entscheidungslogik quelltextbasiert UND über die echte
 * Gate-API. Ein Rückfall auf die alte Logik lässt ihn fehlschlagen.
 */
class LocationDisclosureRegressionTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File(".").resolve("src/main/java/com/d_drostes_apps/aevum/$relative"),
            File("..").resolve("app/src/main/java/com/d_drostes_apps/aevum/$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Quelldatei nicht gefunden: $relative")
        return file.readLines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    private val gateSource by lazy { source("ui/disclosure/LocationDisclosure.kt") }

    /** Quelltext des Trigger-Screens (mehrfach gebraucht). */
    private fun triggerScreenSource(): String =
        source("ui/screens/settings/TriggerSettingsScreen.kt")

    /**
     * Die Kernaussage: Eine bestätigte Offenlegung darf die Anzeige NICHT
     * unterdrücken, solange die Berechtigung fehlt.
     */
    @Test
    fun `bestaetigte Offenlegung unterdrueckt den Dialog nicht`() {
        val acceptedButNotGranted = DisclosureGate(disclosureAccepted = true)
        assertWithMessage(
            "Bei bestätigter Offenlegung und FEHLENDER Berechtigung muss die " +
                "Offenlegung erscheinen. Sonst passiert beim Klick auf 'Location' " +
                "nichts sichtbares — genau der gemeldete Fehler."
        ).that(
            acceptedButNotGranted.needsDisclosure(
                DisclosureGate.Action.REQUEST_FOREGROUND_LOCATION,
                alreadySatisfied = false
            )
        ).isTrue()

        assertWithMessage(
            "Gleiches gilt für den Hintergrund-Pfad: sonst springt die App " +
                "direkt in die Einstellungen, ohne vorher zu erklären."
        ).that(
            acceptedButNotGranted.needsDisclosure(
                DisclosureGate.Action.REQUEST_BACKGROUND_VIA_SETTINGS,
                alreadySatisfied = false
            )
        ).isTrue()
    }

    /**
     * Nur die tatsächlich erteilte Berechtigung darf den Dialog überspringen —
     * dann gibt es nichts zu erklären.
     */
    @Test
    fun `nur der erteilte Zustand ueberspringt den Dialog`() {
        for (accepted in listOf(true, false)) {
            val gate = DisclosureGate(disclosureAccepted = accepted)
            assertWithMessage("accepted=$accepted, erteilt → kein Dialog nötig")
                .that(
                    gate.needsDisclosure(
                        DisclosureGate.Action.REQUEST_FOREGROUND_LOCATION,
                        alreadySatisfied = true
                    )
                ).isFalse()
        }
    }

    /**
     * Quelltext-Guard: Das Gate darf `isAccepted` nicht mehr als alleinige
     * Bedingung verwenden. Ein Rückfall wäre genau der alte Fehler.
     */
    @Test
    fun `Gate entscheidet nicht ueber isAccepted`() {
        val requestFn = gateSource.substringAfter("fun request(").substringBefore("fun consent(")
        assertWithMessage(
            "Die request()-Funktion prüft `LocationDisclosure.isAccepted(context)` " +
                "als Bedingung. Das war die Ursache des Fehlers: nach einer einmal " +
                "bestätigten Offenlegung lief jeder weitere Klick direkt durch. " +
                "Maßgeblich muss der AKTUELLE Berechtigungsstatus sein."
        ).that(requestFn.contains("if (LocationDisclosure.isAccepted(context))")).isFalse()

        assertWithMessage(
            "Die request()-Funktion wertet den Berechtigungsstatus nicht aus"
        ).that(requestFn.contains("alreadySatisfied")).isTrue()
    }

    /**
     * Beide Pfade müssen bedienbar bleiben: Wenn Android keinen Dialog mehr
     * zeigt, muss ein Ersatzweg existieren — sonst passiert „nichts".
     */
    @Test
    fun `Location-Pfad hat einen Fallback wenn Android keinen Dialog zeigt`() {
        val screen = source("ui/screens/settings/TriggerSettingsScreen.kt")
        assertWithMessage(
            "runLocationAction prüft nicht, ob Android den Systemdialog noch " +
                "zeigt. Nach zweimaliger Ablehnung würde der Klick wirkungslos " +
                "verpuffen ('Pending' steht da, aber nichts passiert)."
        ).that(screen.contains("willShowSystemDialog(")).isTrue()
        assertWithMessage(
            "Ohne openAppDetails() als Fallback hat der Nutzer keinen Weg mehr"
        ).that(screen.contains("openAppDetails()")).isTrue()
    }

    /**
     * Der Standort braucht einen Verlaufseintrag für die Unterscheidung
     * „noch nie gefragt" vs. „dauerhaft abgelehnt".
     */
    @Test
    fun `Standort hat einen Dialog-Verlaufsschluessel`() {
        val screen = source("ui/screens/settings/TriggerSettingsScreen.kt")
        assertWithMessage("LOCATION_MEMORY_KEY fehlt")
            .that(screen.contains("LOCATION_MEMORY_KEY")).isTrue()
        assertWithMessage("Ablehnung wird nicht vermerkt")
            .that(screen.contains("PermissionDisclosureMemory.markDenied(context, LOCATION_MEMORY_KEY)")).isTrue()
        assertWithMessage("Grant setzt den Verlauf nicht zurück")
            .that(screen.contains("PermissionDisclosureMemory.clearDenied(context, LOCATION_MEMORY_KEY)")).isTrue()
    }

    /**
     * Der Dialog muss bei beiden Status-Zeilen vorgeschaltet sein — nicht nur
     * bei einer. Der Nutzer hat beide genannt.
     */
    @Test
    fun `beide Location-Zeilen laufen ueber das Gate`() {
        val screen = source("ui/screens/settings/TriggerSettingsScreen.kt")
        assertWithMessage("Vordergrund-Zeile läuft nicht über requestLocationAccess")
            .that(
                screen.contains(
                    "onRequestForeground = {\n" +
                        "                        requestLocationAccess(DisclosureGate.Action.REQUEST_FOREGROUND_LOCATION)"
                )
            ).isTrue()
        assertWithMessage("Hintergrund-Zeile läuft nicht über requestLocationAccess")
            .that(
                screen.contains(
                    "onRequestBackground = {\n" +
                        "                        requestLocationAccess(DisclosureGate.Action.REQUEST_BACKGROUND_VIA_SETTINGS)"
                )
            ).isTrue()
    }

    /**
     * Beide Standort-Screens brauchen den Fallback — sonst verpufft der Klick
     * im einen Screen, während der andere funktioniert.
     */
    @Test
    fun `auch der Geofence-Editor hat den Fallback`() {
        val editor = source("ui/screens/automation/AutomationScreens.kt")
        assertWithMessage(
            "Der Geofence-Editor prüft nicht, ob Android den Dialog noch zeigt"
        ).that(editor.contains("willShowSystemDialog(")).isTrue()
        assertWithMessage(
            "Der Geofence-Editor hat keinen Fallback in die App-Einstellungen"
        ).that(editor.contains("openAppSettings(context)")).isTrue()
        assertWithMessage(
            "Der Geofence-Editor startet den Launcher an mehr als einer Stelle — " +
                "genau EINE ist zulässig (im einheitlichen requestForegroundLocation)"
        ).that(editor.lines().count { it.contains("foregroundPermission.launch(") }).isEqualTo(1)
    }

    /**
     * Beide Screens müssen denselben Verlaufseintrag nutzen. Sonst kennt ein
     * Screen die Ablehnung des anderen nicht.
     */
    @Test
    fun `beide Standort-Screens teilen den Verlaufsschluessel`() {
        assertWithMessage("TriggerSettings nutzt einen eigenen Schlüssel statt LOCATION_MEMORY_KEY")
            .that(triggerScreenSource().contains("LOCATION_MEMORY_KEY")).isTrue()
        assertWithMessage("AutomationScreens nutzt einen eigenen Schlüssel statt LOCATION_MEMORY_KEY")
            .that(source("ui/screens/automation/AutomationScreens.kt").contains("LOCATION_MEMORY_KEY")).isTrue()
        assertWithMessage(
            "LOCATION_MEMORY_KEY wird lokal definiert statt zentral im Disclosure-Modul — " +
                "dann können die Screens auseinanderlaufen"
        ).that(triggerScreenSource().contains("const val LOCATION_MEMORY_KEY")).isFalse()
    }

    /**
     * Die Start-Offenlegung darf nicht als „erledigt" gelten und das Gate
     * abschalten — beide Wege sind unabhängig.
     */
    @Test
    fun `Start-Offenlegung und Gate sind unabhaengig`() {
        val screen = source("ui/screens/settings/TriggerSettingsScreen.kt")
        val gateRequest = screen.substringAfter("fun requestLocationAccess(")
            .substringBefore("fun requestPermission(")
        assertWithMessage(
            "requestLocationAccess darf nicht auf isAccepted zurückfallen — die " +
                "Start-Offenlegung (MainActivity) ist ein anderer Weg."
        ).that(gateRequest.contains("isAccepted")).isFalse()
    }
}
