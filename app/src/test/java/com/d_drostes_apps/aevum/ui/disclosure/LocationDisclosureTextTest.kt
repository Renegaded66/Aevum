package com.d_drostes_apps.aevum.ui.disclosure

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * M18.139 (Play-Auflage): Inhaltsprüfung der Offenlegungs-Texte.
 *
 * Diese Tests lesen die String-Ressourcen direkt aus dem Quellbaum und
 * prüfen den WORTLAUT gegen die Google-Kriterien. Sie sind bewusst
 * textbasiert (kein Android-Framework nötig), damit sie in der normalen
 * JVM-Suite laufen und sofort failen, wenn jemand die Formulierung
 * „aufräumt" und dabei ein Pflichtelement entfernt.
 *
 * Google-Kriterien für die deutliche Offenlegung (Standort im Hintergrund):
 *   • muss den Begriff „Standort"/"location" enthalten
 *   • muss die Hintergrund-Natur benennen: „Hintergrund" / „wenn die App
 *     geschlossen ist" / „immer aktiv" / „wenn die App nicht verwendet wird"
 *   • muss ALLE Funktionen auflisten, die im Hintergrund Standort nutzen
 *   • die Einwilligung muss eine ausdrückliche Handlung sein
 *
 * Geprüft werden BEIDE Sprachfassungen, weil die Review je nach
 * Store-Sprache greift.
 */
class LocationDisclosureTextTest {

    private fun resourceFile(locale: String): String {
        val relative = if (locale == "de") {
            "src/main/res/values/strings_disclosure.xml"
        } else {
            "src/main/res/values-$locale/strings_disclosure.xml"
        }
        val candidates = listOf(
            File(".").resolve(relative),
            File("..").resolve("app/$relative"),
            File("../app").resolve(relative),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("String-Datei nicht gefunden: $relative (CWD=${File(".").absolutePath})")
        return file.readText()
    }

    private val de get() = resourceFile("de")
    private val en get() = resourceFile("en")

    /** Zieht den Inhalt eines <string>-Eintrags aus der XML-Datei. */
    private fun stringValue(xml: String, name: String): String {
        val pattern = Regex(
            "<string name=\"$name\"[^>]*>(.*?)</string>",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        return pattern.find(xml)?.groupValues?.get(1)
            ?: error("String '$name' nicht gefunden")
    }

    // ── Kriterium 1: Begriff „Standort" ──────────────────────────────────

    @Test
    fun `DE - Einleitungssatz nennt Standort und Hintergrund`() {
        val lead = stringValue(de, "disclosure_location_lead")
        assertWithMessage("Das Wort 'Standort' fehlt im Einleitungssatz")
            .that(lead.contains("Standort")).isTrue()
        assertWithMessage(
            "Die Hintergrund-Natur fehlt. Google verlangt eine dieser " +
                "Formulierungen: 'Hintergrund' / 'wenn die App geschlossen " +
                "ist' / 'immer aktiv' / 'wenn die App nicht verwendet wird'."
        ).that(
            lead.contains("im Hintergrund", ignoreCase = true) ||
                lead.contains("geschlossen", ignoreCase = true) ||
                lead.contains("nicht verwendet", ignoreCase = true)
        ).isTrue()
    }

    @Test
    fun `EN - lead sentence names location and background`() {
        val lead = stringValue(en, "disclosure_location_lead")
        assertWithMessage("The word 'location' is missing in the lead sentence")
            .that(lead.contains("location", ignoreCase = true)).isTrue()
        assertWithMessage("The background nature is missing in the lead sentence")
            .that(
                lead.contains("background", ignoreCase = true) ||
                    lead.contains("closed", ignoreCase = true) ||
                    lead.contains("not in use", ignoreCase = true)
            ).isTrue()
    }

    // ── Kriterium 2: Google-Pflichtsatzformel ────────────────────────────

    @Test
    fun `DE - Satz folgt der Google-Formel erhebt Standortdaten um zu ermoeglichen`() {
        val lead = stringValue(de, "disclosure_location_lead")
        // Google: „Diese App erhebt Standortdaten, um [Funktion], [Funktion]
        // und [Funktion] zu ermöglichen, auch wenn die App geschlossen ist
        // oder nicht verwendet wird."
        assertWithMessage("Baustein 'erhebt Standortdaten' fehlt")
            .that(lead.contains("erhebt Standortdaten")).isTrue()
        assertWithMessage("Baustein 'zu ermöglichen' fehlt")
            .that(lead.contains("zu ermöglichen")).isTrue()
        assertWithMessage("Baustein 'auch wenn die App geschlossen ist' fehlt")
            .that(lead.contains("auch wenn die App geschlossen ist")).isTrue()
    }

    @Test
    fun `EN - lead follows the Google sentence pattern`() {
        val lead = stringValue(en, "disclosure_location_lead")
        assertWithMessage("'collects location data' missing")
            .that(lead.contains("collects location data", ignoreCase = true)).isTrue()
        assertWithMessage("'even when the app is closed' missing")
            .that(lead.contains("even when the app is closed")).isTrue()
    }

    // ── Kriterium 3: alle Hintergrund-Funktionen gelistet ────────────────

    @Test
    fun `DE - alle vier Hintergrund-Funktionen sind einzeln beschrieben`() {
        val xml = de
        val names = listOf(
            "disclosure_location_feature_geofence",
            "disclosure_location_feature_drive",
            "disclosure_location_feature_movement",
            "disclosure_location_feature_places"
        )
        for (name in names) {
            val value = stringValue(xml, name)
            assertWithMessage("$name ist leer").that(value.isNotBlank()).isTrue()
            assertWithMessage("$name ist zu knapp für eine Beschreibung")
                .that(value.length).isAtLeast(40)
        }
    }

    @Test
    fun `EN - all four background features are described`() {
        val xml = en
        val names = listOf(
            "disclosure_location_feature_geofence",
            "disclosure_location_feature_drive",
            "disclosure_location_feature_movement",
            "disclosure_location_feature_places"
        )
        for (name in names) {
            assertWithMessage("$name missing or too short")
                .that(stringValue(xml, name).length).isAtLeast(40)
        }
    }

    @Test
    fun `DE - Einleitungssatz zaehlt dieselben Funktionen auf wie die Liste`() {
        val lead = stringValue(de, "disclosure_location_lead")
        // Der Pflichtsatz muss die Funktionen selbst nennen, nicht nur auf
        // eine Liste darunter verweisen.
        val keywords = listOf("Ortserkennung", "Fahrterkennung", "Spaziergängen", "Orts-Timeline")
        for (kw in keywords) {
            assertWithMessage("Funktion '$kw' fehlt im Pflichtsatz der Offenlegung")
                .that(lead.contains(kw)).isTrue()
        }
    }

    // ── Kriterium 4: Datenverwendung + Lokalität ─────────────────────────

    @Test
    fun `DE - Datennutzung und lokale Speicherung sind benannt`() {
        val data = stringValue(de, "disclosure_location_data")
        assertWithMessage("Die Offenlegung erklärt nicht, was mit den Daten passiert")
            .that(data.contains("lokal", ignoreCase = true)).isTrue()
        assertWithMessage("Der Ausschluss von Werbung/Weitergabe fehlt")
            .that(
                data.contains("Werbung", ignoreCase = true) ||
                    data.contains("Weitergabe", ignoreCase = true)
            ).isTrue()
    }

    @Test
    fun `EN - data usage and local storage are stated`() {
        val data = stringValue(en, "disclosure_location_data")
        assertWithMessage("Local storage is not stated")
            .that(data.contains("device", ignoreCase = true)).isTrue()
        assertWithMessage("No advertising / no sharing is not stated")
            .that(
                data.contains("advertising", ignoreCase = true) ||
                    data.contains("sharing", ignoreCase = true)
            ).isTrue()
    }

    // ── Kriterium 5: ausdrückliche Zustimmung ────────────────────────────

    @Test
    fun `DE - Zustimmungsbutton ist eine ausdrueckliche Handlung`() {
        val accept = stringValue(de, "disclosure_location_accept")
        // Ein bloßes „OK" erfüllt die Anforderung an eine ausdrückliche,
        // informierte Einwilligung nicht.
        assertWithMessage("Zustimmungsbutton ist zu unspezifisch: '$accept'")
            .that(accept.contains("Einverstanden", ignoreCase = true)).isTrue()
        assertWithMessage("Zustimmungsbutton benennt den Standort nicht")
            .that(accept.contains("Standort", ignoreCase = true)).isTrue()
    }

    @Test
    fun `EN - consent button is an explicit action`() {
        val accept = stringValue(en, "disclosure_location_accept")
        assertWithMessage("Consent button is too generic: '$accept'")
            .that(accept.contains("Agree", ignoreCase = true)).isTrue()
    }

    // ── Sprachparität ────────────────────────────────────────────────────

    @Test
    fun `DE und EN deklarieren dieselben Schluessel`() {
        // `translatable="false"`-Einträge (z. B. die Datenschutz-URL) stehen
        // bewusst nur in der Standardfassung und werden hier ausgenommen.
        fun keys(xml: String): Set<String> = Regex("<string name=\"([^\"]+)\"([^>]*)>")
            .findAll(xml)
            .filterNot { it.groupValues[2].contains("translatable=\"false\"") }
            .map { it.groupValues[1] }
            .toSet()

        val deKeys = keys(de)
        val enKeys = keys(en)
        assertWithMessage("Schlüssel nur in DE: ${deKeys - enKeys}").that(deKeys - enKeys).isEmpty()
        assertWithMessage("Schlüssel nur in EN: ${enKeys - deKeys}").that(enKeys - deKeys).isEmpty()
    }

    // ── Datenschutz-URL ──────────────────────────────────────────────────

    @Test
    fun `Datenschutz-URL zeigt auf die veroeffentlichte Seite`() {
        val url = stringValue(de, "disclosure_privacy_url").trim()
        assertWithMessage("Datenschutz-URL ist nicht die veröffentlichte Seite: '$url'")
            .that(url).isEqualTo("https://renegaded66.github.io/Aevum/")
        assertWithMessage("Datenschutz-URL muss HTTPS sein")
            .that(url.startsWith("https://")).isTrue()
    }
}
