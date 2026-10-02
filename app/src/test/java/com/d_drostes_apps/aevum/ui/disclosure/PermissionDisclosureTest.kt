package com.d_drostes_apps.aevum.ui.disclosure

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * M18.141: Wächter für die Berechtigungs-Erklärungen.
 *
 * Zwei Fehlerklassen werden hier abgefangen:
 *
 * 1. TEXT-SEITE: Ein Erklärungsdialog ohne Zweck, ohne konkrete Funktionen
 *    oder ohne Aussage zur Datenverarbeitung erfüllt seinen Zweck nicht.
 *    Die Play-Ablehnung von 1.0.19 entstand genau daraus, dass eine
 *    Berechtigungs-Anfrage ohne substanzielle Erklärung lief.
 *
 * 2. VERDRAHTUNGS-SEITE: Ein Permission-Launcher, der direkt aus dem UI
 *    gerufen wird, umgeht den Dialog. Genau das war der Ablehnungsgrund —
 *    ein Text allein hätte ihn nicht behoben.
 */
class PermissionDisclosureTest {

    private fun resFile(relative: String): File {
        val candidates = listOf(
            File(".").resolve(relative),
            File("..").resolve("app/$relative"),
            File("../app").resolve(relative),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Datei nicht gefunden: $relative (CWD=${File(".").absolutePath})")
    }

    private val enXml by lazy { resFile("src/main/res/values/strings_permission_disclosure.xml").readText() }
    private val deXml by lazy { resFile("src/main/res/values-de/strings_permission_disclosure.xml").readText() }

    private fun stringValue(xml: String, name: String): String {
        val pattern = Regex(
            "<string name=\"$name\"[^>]*>(.*?)</string>",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        return pattern.find(xml)?.groupValues?.get(1)
            ?: error("String '$name' nicht gefunden")
    }

    private fun keys(xml: String): Set<String> =
        Regex("<string name=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toSet()

    /** Jede Berechtigung, die einen Dialog hat. */
    private val kinds = listOf("activity", "notifications", "usage", "calendar")

    // ── Sprachparität ────────────────────────────────────────────────────

    @Test
    fun `EN und DE deklarieren dieselben Schluessel`() {
        val en = keys(enXml)
        val de = keys(deXml)
        assertWithMessage("Schlüssel nur in EN: ${en - de}").that(en - de).isEmpty()
        assertWithMessage("Schlüssel nur in DE: ${de - en}").that(de - en).isEmpty()
    }

    // ── Inhaltliche Pflichtelemente ──────────────────────────────────────

    @Test
    fun `jede Berechtigung hat Titel und Einleitung`() {
        for (kind in kinds) {
            for ((lang, xml) in listOf("EN" to enXml, "DE" to deXml)) {
                val title = stringValue(xml, "perm_${kind}_title")
                val lead = stringValue(xml, "perm_${kind}_lead")
                assertWithMessage("$lang: Titel $kind ist leer").that(title.isNotBlank()).isTrue()
                assertWithMessage("$lang: Titel $kind ist keine Frage").that(title.contains("?")).isTrue()
                assertWithMessage("$lang: Einleitung $kind zu knapp (${lead.length})")
                    .that(lead.length).isAtLeast(120)
            }
        }
    }

    @Test
    fun `jede Berechtigung nennt mindestens drei konkrete Funktionen`() {
        // Ein Dialog, der den Zweck nicht konkret benennt, ist eine leere
        // Hülle. Drei Punkte sind das Minimum, das den Nutzen erklärt.
        for (kind in kinds) {
            for ((lang, xml) in listOf("EN" to enXml, "DE" to deXml)) {
                val points = (1..3).map { stringValue(xml, "perm_${kind}_point_$it") }
                for ((i, p) in points.withIndex()) {
                    assertWithMessage("$lang: Punkt ${i + 1} von $kind ist leer")
                        .that(p.isNotBlank()).isTrue()
                    assertWithMessage("$lang: Punkt ${i + 1} von $kind zu knapp (${p.length})")
                        .that(p.length).isAtLeast(25)
                }
            }
        }
    }

    @Test
    fun `jede Berechtigung sagt etwas zur Datenverarbeitung`() {
        // Pflicht: der Nutzer muss erfahren, dass die Daten lokal bleiben und
        // die Berechtigung widerrufbar ist.
        for (kind in kinds) {
            val en = stringValue(enXml, "perm_${kind}_note")
            val de = stringValue(deXml, "perm_${kind}_note")
            assertWithMessage("EN: Hinweis $kind nennt die lokale Verarbeitung nicht")
                .that(en.contains("device", ignoreCase = true)).isTrue()
            assertWithMessage("EN: Hinweis $kind nennt den Widerruf nicht")
                .that(en.contains("revoke", ignoreCase = true)).isTrue()
            assertWithMessage("DE: Hinweis $kind nennt die lokale Verarbeitung nicht")
                .that(de.contains("lokal", ignoreCase = true)).isTrue()
            assertWithMessage("DE: Hinweis $kind nennt den Widerruf nicht")
                .that(de.contains("entziehen", ignoreCase = true)).isTrue()
        }
    }

    @Test
    fun `Hinweise behaupten keine Uebertragung wenn keine stattfindet`() {
        // Widerspruchsfreiheit zur Datenschutzerklärung: Aevum hat keinen
        // Server. Ein Hinweis, der eine Übertragung offenlässt, wäre falsch.
        for (kind in kinds) {
            val en = stringValue(enXml, "perm_${kind}_note")
            val de = stringValue(deXml, "perm_${kind}_note")
            assertWithMessage("EN: Hinweis $kind relativiert die Lokalität")
                .that(en.contains("may be transmitted") || en.contains("synced")).isFalse()
            assertWithMessage("DE: Hinweis $kind relativiert die Lokalität")
                .that(de.contains("übermittelt werden kann") || de.contains("synchronisiert")).isFalse()
        }
    }

    @Test
    fun `Zustimmung ist eine ausdrueckliche Handlung`() {
        // Kein blosses „OK": der Button muss die Handlung benennen.
        assertWithMessage("EN-Zustimmungsbutton zu unspezifisch")
            .that(stringValue(enXml, "perm_disclosure_allow").trim()).isEqualTo("Allow")
        assertWithMessage("DE-Zustimmungsbutton zu unspezifisch")
            .that(stringValue(deXml, "perm_disclosure_allow").trim()).isEqualTo("Erlauben")
        assertWithMessage("EN: Ablehnung fehlt").that(stringValue(enXml, "perm_disclosure_decline").isNotBlank()).isTrue()
        assertWithMessage("DE: Ablehnung fehlt").that(stringValue(deXml, "perm_disclosure_decline").isNotBlank()).isTrue()
    }

    @Test
    fun `Ablehnung ist nicht als Zustimmung formuliert`() {
        // „Weiter"/„Continue" würde als Zustimmung gelesen.
        val en = stringValue(enXml, "perm_disclosure_decline").lowercase()
        val de = stringValue(deXml, "perm_disclosure_decline").lowercase()
        for (bad in listOf("continue", "ok", "weiter", "fortfahren")) {
            assertWithMessage("EN-Ablehnungsbutton klingt nach Zustimmung: $bad")
                .that(en.contains(bad)).isFalse()
            assertWithMessage("DE-Ablehnungsbutton klingt nach Zustimmung: $bad")
                .that(de.contains(bad)).isFalse()
        }
    }

    @Test
    fun `Nutzungszugriff erklaert die Sonderstellung`() {
        // Diese Berechtigung läuft NICHT über den normalen Systemdialog —
        // der Nutzer muss das erfahren, sonst wundert er sich über die
        // Einstellungsseite.
        val en = stringValue(enXml, "perm_usage_lead")
        val de = stringValue(deXml, "perm_usage_lead")
        assertWithMessage("EN: Sonderstellung des Nutzungszugriffs nicht erklärt")
            .that(en.contains("special", ignoreCase = true) || en.contains("settings page", ignoreCase = true)).isTrue()
        assertWithMessage("DE: Sonderstellung des Nutzungszugriffs nicht erklärt")
            .that(de.contains("speziell", ignoreCase = true) || de.contains("Einstellungsseite", ignoreCase = true)).isTrue()
    }

    @Test
    fun `Kalender-Hinweis nennt den Nur-Lesen-Zugriff`() {
        // READ_CALENDAR ohne WRITE: das ist eine Zusage und muss im Dialog stehen.
        val en = stringValue(enXml, "perm_calendar_note")
        val de = stringValue(deXml, "perm_calendar_note")
        assertWithMessage("EN: Nur-Lesen nicht zugesagt")
            .that(en.contains("read only", ignoreCase = true) || en.contains("never creates", ignoreCase = true)).isTrue()
        assertWithMessage("DE: Nur-Lesen nicht zugesagt")
            .that(de.contains("nur gelesen", ignoreCase = true) || de.contains("niemals Termine an", ignoreCase = true)).isTrue()
    }

    // ── Verdrahtung im Screen ────────────────────────────────────────────

    private val triggerScreen by lazy {
        resFile("src/main/java/com/d_drostes_apps/aevum/ui/screens/settings/TriggerSettingsScreen.kt").readText()
    }

    private val calendarScreen by lazy {
        resFile("src/main/java/com/d_drostes_apps/aevum/ui/screens/calendar/CalendarRulesScreen.kt").readText()
    }

    /**
     * Liefert die Zeilen, in denen ein Launcher DIREKT gestartet wird —
     * Kommentarzeilen ausgenommen.
     */
    private fun directLaunchLines(source: String, launcher: String): List<String> =
        source.lines().filter { line ->
            line.contains("$launcher.launch(") &&
                !line.trimStart().startsWith("//") &&
                !line.trimStart().startsWith("*")
        }

    @Test
    fun `TriggerSettingsScreen nutzt das Permission-Gate`() {
        assertWithMessage("Der Screen erzeugt kein Permission-Disclosure-Gate")
            .that(triggerScreen.contains("rememberPermissionDisclosureGate()")).isTrue()
        assertWithMessage("Der Screen rendert den Erklärungsdialog nicht")
            .that(triggerScreen.contains("PermissionDisclosureDialog(")).isTrue()
    }

    @Test
    fun `kein Berechtigungs-Request umgeht das Gate`() {
        // Der Kern-Guard: Jeder Runtime-Request muss über den Gate-Pfad
        // laufen. Ein direkter launcher.launch(...)-Aufruf im UI-Code wäre
        // genau der Fehler, den Play beanstandet hat.
        //
        // Gegenprobe: Dieser Test schlägt fehl, sobald jemand irgendwo einen
        // weiteren Direktaufruf einbaut (verifiziert).
        val activityLaunches = directLaunchLines(triggerScreen, "activityLauncher")
        val notificationLaunches = directLaunchLines(triggerScreen, "notificationLauncher")
        assertWithMessage(
            "activityLauncher.launch( kommt ${activityLaunches.size} mal vor — erwartet " +
                "genau 1 (im Gate-Pfad runPermissionAction). Weitere Aufrufe umgehen " +
                "den Erklärungsdialog:\n${activityLaunches.joinToString("\n")}"
        ).that(activityLaunches.size).isEqualTo(1)
        assertWithMessage(
            "notificationLauncher.launch( kommt ${notificationLaunches.size} mal vor — " +
                "erwartet genau 1 (im Gate-Pfad runPermissionAction)."
        ).that(notificationLaunches.size).isEqualTo(1)
    }

    @Test
    fun `Kalender-Screen nutzt das Gate`() {
        assertWithMessage("CalendarRulesScreen erzeugt kein Permission-Gate")
            .that(calendarScreen.contains("rememberPermissionDisclosureGate()")).isTrue()
        assertWithMessage("CalendarRulesScreen rendert den Erklärungsdialog nicht")
            .that(calendarScreen.contains("PermissionDisclosureDialog(")).isTrue()
        assertWithMessage("Der Kalender-Banner ruft requestCalendarAccess() nicht")
            .that(calendarScreen.contains("onRequest = { requestCalendarAccess() }")).isTrue()
        val launches = directLaunchLines(calendarScreen, "permissionLauncher")
        assertWithMessage(
            "permissionLauncher.launch( kommt ${launches.size} mal vor — erwartet " +
                "genau 2 (einmal in requestCalendarAccess, einmal im Dialog-Callback)."
        ).that(launches.size).isEqualTo(2)
    }

    @Test
    fun `jeder Gate-Pfad fuehrt bei requiresSettings in die Systemeinstellungen`() {
        // Sonst drückt der Nutzer „Einstellungen öffnen" und es passiert
        // nichts — der stille Fehlschlag aus dem Skill.
        assertWithMessage("TriggerSettings: requiresSettings wird nicht ausgewertet")
            .that(triggerScreen.contains("if (pending.requiresSettings)")).isTrue()
        assertWithMessage("CalendarRules: requiresSettings wird nicht ausgewertet")
            .that(calendarScreen.contains("ready.requiresSettings")).isTrue()
    }

    @Test
    fun `jeder Berechtigungs-Klick im Screen laeuft ueber requestPermission`() {
        // Die Status-Zeilen und die Trigger-Toggles müssen requestPermission
        // rufen. Fehlt einer, erscheint dort kein Dialog.
        for (kind in listOf("ACTIVITY_RECOGNITION", "NOTIFICATIONS", "USAGE_ACCESS")) {
            assertWithMessage("PermissionDisclosureKind.$kind wird im Screen nie angefragt")
                .that(triggerScreen.contains("PermissionDisclosureKind.$kind")).isTrue()
        }
        assertWithMessage("requestPermission() wird nicht aufgerufen")
            .that(triggerScreen.contains("requestPermission(")).isTrue()
        assertWithMessage("Der Gate-Pfad runPermissionAction fehlt")
            .that(triggerScreen.contains("runPermissionAction(")).isTrue()
    }

    @Test
    fun `der Screen persistiert KEINE Zustimmung fuer diese Berechtigungen`() {
        // Bewusste Design-Entscheidung: Der Erklärungsdialog hängt am
        // AKTUELLEN Berechtigungsstatus, nicht an einer gemerkten Antwort.
        // Wurde eine Berechtigung erteilt und wieder entzogen, muss der
        // Dialog erneut erscheinen. Eine „schon erklärt"-Marke würde das
        // verhindern — dieser Test schützt die Entscheidung.
        val gateBlock = triggerScreen.substringAfter("val permissionGate").substringBefore("DisposableEffect")
        for (forbidden in listOf("markAccepted", "isAccepted", "permDisclosureAccepted")) {
            assertWithMessage(
                "Der Permission-Gate-Block verwendet '$forbidden' — das würde den " +
                    "Dialog nach einem Widerruf unterdrücken."
            ).that(gateBlock.contains(forbidden)).isFalse()
        }
    }
}
