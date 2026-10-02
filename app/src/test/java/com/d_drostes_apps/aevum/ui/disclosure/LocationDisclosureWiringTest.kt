package com.d_drostes_apps.aevum.ui.disclosure

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * M18.139 (Play-Auflage): Verdrahtungs-Guard für die deutliche Offenlegung.
 *
 * Die Play-Ablehnung lautete (Auszug):
 *
 *   „Anfragen zur Nutzereinwilligung und zu Laufzeitberechtigungen in der App
 *    geht keine unmittelbare Offenlegung in der App voraus."
 *
 * Der eigentliche Fehler war also NICHT der Text, sondern die Verdrahtung:
 * der Runtime-Request lief direkt, ohne vorgeschalteten Dialog. Genau das
 * kann bei jeder künftigen UI-Änderung erneut passieren — ein neuer Button,
 * der `launch(...)` aufruft, umgeht die Offenlegung wieder.
 *
 * Dieser Test liest den Quellcode der Standort-Einbaustellen und erzwingt:
 *   1. Jeder Screen mit einem Standort-Permission-Launcher nutzt das Gate
 *      (`disclosureGate.request(`) — nicht den Launcher direkt.
 *   2. In keinem dieser Screens wird ein Standort-Launcher noch direkt
 *      außerhalb der freigegebenen Ausführungsfunktion gestartet.
 *   3. Der Disclosure-Dialog wird tatsächlich gerendert (sonst wäre das Gate
 *      wirkungslos, weil `pendingAction` nie sichtbar würde).
 *
 * Bewusst quelltextbasiert: die Prüfung läuft in der normalen JVM-Suite und
 * braucht weder Emulator noch Gerät.
 */
class LocationDisclosureWiringTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File(".").resolve("src/main/java/com/d_drostes_apps/aevum/$relative"),
            File("..").resolve("app/src/main/java/com/d_drostes_apps/aevum/$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Quelldatei nicht gefunden: $relative (CWD=${File(".").absolutePath})")
        // Kommentare entfernen, damit Doku-Beispiele nicht als echte
        // Aufrufe zählen.
        return file.readLines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    /** Screens, die Standort-Permissions anfragen und das Gate nutzen müssen. */
    private val locationScreens = listOf(
        "ui/screens/settings/TriggerSettingsScreen.kt",
        "ui/screens/automation/AutomationScreens.kt",
    )

    @Test
    fun `jeder Standort-Screen nutzt das Offenlegungs-Gate`() {
        for (screen in locationScreens) {
            val src = source(screen)
            assertWithMessage(
                "$screen fragt Standort-Permissions an, nutzt aber nicht das " +
                    "Offenlegungs-Gate. Ohne `disclosureGate.request(...)` fehlt " +
                    "die deutliche Offenlegung vor dem Runtime-Request " +
                    "(Play-Richtlinie 'Nutzerdaten')."
            ).that(src.contains("rememberLocationDisclosureGate()")).isTrue()
            assertWithMessage("$screen: Gate wird deklariert, aber nie als request(...) benutzt")
                .that(src.contains("disclosureGate.request(")).isTrue()
        }
    }

    @Test
    fun `jeder Standort-Screen rendert den Offenlegungs-Dialog`() {
        for (screen in locationScreens) {
            val src = source(screen)
            assertWithMessage(
                "$screen muss LocationDisclosureDialog rendern — sonst bleibt " +
                    "pendingAction gesetzt und der Nutzer sieht keinen Dialog, " +
                    "während die Permission-Anfrage nie ausgeführt wird."
            ).that(src.contains("LocationDisclosureDialog(")).isTrue()
        }
    }

    @Test
    fun `Zustimmung laeuft ueber consent - nicht ueber direkten Zustandswechsel`() {
        for (screen in locationScreens) {
            val src = source(screen)
            assertWithMessage("$screen: onAccept muss disclosureGate.consent(...) aufrufen")
                .that(src.contains("disclosureGate.consent")).isTrue()
            assertWithMessage("$screen: Ablehnung muss disclosureGate.dismiss() aufrufen")
                .that(src.contains("disclosureGate.dismiss()")).isTrue()
        }
    }

    @Test
    fun `Standort-Launcher wird nur in der Gate-geschuetzten Funktion gestartet`() {
        // TriggerSettingsScreen bündelt die Ausführung in runLocationAction();
        // jeder direkte launch(...) außerhalb wäre ein Bypass.
        val src = source("ui/screens/settings/TriggerSettingsScreen.kt")
        val launcherUses = Regex("locationLauncher\\.launch\\(").findAll(src).count()
        assertWithMessage(
            "TriggerSettingsScreen startet locationLauncher.launch(...) " +
                "an $launcherUses Stellen. Zulässig ist genau EINE — in " +
                "runLocationAction(), das ausschließlich nach bestätigter " +
                "Offenlegung läuft. Jede weitere Stelle umgeht das Gate."
        ).that(launcherUses).isEqualTo(1)
    }

    @Test
    fun `App-Start zeigt die Offenlegung bei normaler Nutzung`() {
        // Richtlinie: „Must be displayed in the normal usage of the app and
        // not require the user to navigate into a menu or settings."
        val src = source("MainActivity.kt")
        assertWithMessage("MainActivity rendert die Start-Offenlegung nicht")
            .that(src.contains("LocationDisclosureDialog(")).isTrue()
        assertWithMessage("MainActivity prüft die Zustimmung nicht")
            .that(src.contains("LocationDisclosure.isAccepted(")).isTrue()
    }

    @Test
    fun `Datenschutz-Screen bietet Policy-Link und erneute Offenlegung`() {
        val src = source("ui/screens/settings/DataSettingsScreens.kt")
        assertWithMessage("Datenschutz-Screen hat keinen Policy-Link")
            .that(src.contains("openPrivacyPolicy(")).isTrue()
        assertWithMessage("Datenschutz-Screen zeigt die Offenlegung nicht erneut an")
            .that(src.contains("LocationDisclosureDialog(")).isTrue()
    }

    @Test
    fun `GeofenceForegroundService ist entfernt und nicht mehr deklariert`() {
        // Play-Policy (Durchsetzung ab 28.10.2026): Geofencing ist kein
        // genehmigter Anwendungsfall für Dienste im Vordergrund. Apps, die
        // einen Location-FGS nur dafür nutzen, müssen die Berechtigung aus
        // ALLEN Tracks entfernen.
        val serviceFile = listOf(
            File(".").resolve("src/main/java/com/d_drostes_apps/aevum/automation/geofence/GeofenceForegroundService.kt"),
            File("..").resolve("app/src/main/java/com/d_drostes_apps/aevum/automation/geofence/GeofenceForegroundService.kt"),
        )
        assertWithMessage(
            "GeofenceForegroundService existiert wieder. Geofencing ist seit " +
                "der Play-Policy vom 15.04.2026 kein zulässiger Anwendungsfall " +
                "für FOREGROUND_SERVICE_LOCATION mehr."
        ).that(serviceFile.any { it.exists() }).isFalse()

        val manifest = readManifestWithoutComments()
        assertWithMessage("Manifest deklariert GeofenceForegroundService noch")
            .that(manifest.contains("GeofenceForegroundService")).isFalse()
    }

    /**
     * Liest das Manifest und entfernt XML-Kommentare. Nötig, weil die
     * Entfernung des GeofenceForegroundService dokumentiert ist — ein
     * reiner Textvergleich würde den erklärenden Kommentar als Deklaration
     * missverstehen.
     */
    private fun readManifestWithoutComments(): String {
        val raw = listOf(
            File(".").resolve("src/main/AndroidManifest.xml"),
            File("..").resolve("app/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.exists() }?.readText() ?: error("AndroidManifest.xml nicht gefunden")
        return raw.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    @Test
    fun `keine ungenutzten FGS- und Overlay-Berechtigungen im Manifest`() {
        // Mindestumfang-Prüfung: nicht deklarieren, was nicht gebraucht wird.
        // SYSTEM_ALERT_WINDOW verschärft ab Android 15 zusätzlich die
        // FGS-Start-Regeln.
        val manifest = readManifestWithoutComments()
        assertWithMessage("FOREGROUND_SERVICE_HEALTH ist ungenutzt und muss raus")
            .that(manifest.contains("android.permission.FOREGROUND_SERVICE_HEALTH")).isFalse()
        assertWithMessage("SYSTEM_ALERT_WINDOW ist ungenutzt und muss raus")
            .that(manifest.contains("android.permission.SYSTEM_ALERT_WINDOW")).isFalse()
    }
}
