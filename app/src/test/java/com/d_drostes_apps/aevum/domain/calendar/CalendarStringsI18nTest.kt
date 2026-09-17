package com.d_drostes_apps.aevum.domain.calendar

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * M18.129-i18n: Wächter-Test für die englischen Kalender-Strings.
 *
 * WARUM ÜBER DIE RESSOURCENDATEI UND NICHT ÜBER `R.string`:
 * In einem JVM-Unit-Test ist `R.string.x` nur eine int-ID — die Auflösung
 * passiert zur Laufzeit gegen das Resource-Table der App, das im Test
 * nicht existiert. Was hier GEPRÜFT WERDEN KANN und soll, ist die
 * Ressourcen-Datei selbst: dass jeder Kalender-Key in `values/` (Deutsch,
 * Fallback) UND `values-en/` (Englisch) existiert und dass die englische
 * Fassung keine deutsche ist.
 *
 * Das ist der Fall, der sonst durch die Maschen fällt: ein neuer Key, der
 * nur in `values/` landet, sieht im Debug-Build deutsch-richtig aus und
 * ist auf Englisch trotzdem deutsch (Fallback-Locale) — ein Fehler, der
 * erst im englischen UI auffällt.
 *
 * Die Datei liegt relativ zum Modul-Wurzelverzeichnis (Gradle-Test-CWD).
 */
class CalendarStringsI18nTest {

    private val deFile = File("src/main/res/values/strings_calendar.xml")
    private val enFile = File("src/main/res/values-en/strings_calendar.xml")

    /** Liest name -> Text aus einer Android-Strings-Datei. */
    private fun readStrings(file: File): Map<String, String> {
        assertThat(file.exists()).isTrue()
        val doc = DocumentBuilderFactory.newInstance()
            .apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            .newDocumentBuilder()
            .parse(file)
        val nodes = doc.getElementsByTagName("string")
        val out = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i)
            val name = el.attributes?.getNamedItem("name")?.nodeValue ?: continue
            out[name] = el.textContent ?: ""
        }
        return out
    }

    private val de by lazy { readStrings(deFile) }
    private val en by lazy { readStrings(enFile) }

    @Test
    fun `jede Sprachdatei hat Kalender-Strings`() {
        assertThat(de.keys.count { it.startsWith("calendar_") }).isAtLeast(100)
        assertThat(en.keys.count { it.startsWith("calendar_") }).isAtLeast(100)
    }

    @Test
    fun `jeder deutsche Kalender-Key hat ein englisches Gegenstueck`() {
        val missing = de.keys.filter { it.startsWith("calendar_") && it !in en }
        assertThat(missing).isEmpty()
    }

    @Test
    fun `jeder englische Kalender-Key hat ein deutsches Gegenstueck`() {
        val orphaned = en.keys.filter { it.startsWith("calendar_") && it !in de }
        assertThat(orphaned).isEmpty()
    }

    /**
     * Die eigentliche Übersetzungs-Prüfung: eine englische Fassung, die
     * zeichengleich mit der deutschen ist, wurde nicht übersetzt. Die
     * Ausnahmen sind bewusst sprachneutral (Zahl + Einheit, Syntax-Beispiel,
     * in beiden Sprachen wortgleiches Label).
     */
    @Test
    fun `englische Kalender-Strings sind nicht mit den deutschen identisch`() {
        val neutral = setOf(
            "calendar_rules_sync_interval_hours",      // "%1\$d h"
            "calendar_rules_summary_extra_min_duration", // "≥ %1\$d min"
            "calendar_editor_regex_placeholder",       // Syntax-Beispiel
            "calendar_editor_section_filter"           // "Filter (optional)" = wortgleich
        )
        val untranslated = de.keys
            .filter { it.startsWith("calendar_") && it in en && it !in neutral }
            .filter { de[it]!!.trim() == en[it]!!.trim() }
        assertThat(untranslated).isEmpty()
    }

    @Test
    fun `englische Kalender-Strings sind keine deutschen Texte`() {
        // Deutsche Sonderzeichen/Umlaute dürfen in der EN-Fassung nicht
        // vorkommen (mit Ausnahme der bewusst sprachneutralen Keys).
        val germanOnly = Regex("[äöüßÄÖÜ]")
        val suspicious = en.filterKeys { it.startsWith("calendar_") }
            .filterKeys { it != "calendar_editor_regex_placeholder" }
            .filterValues { germanOnly.containsMatchIn(it) }
            .keys
        assertThat(suspicious).isEmpty()
    }

    /** Platzhalter müssen zwischen DE und EN übereinstimmen (sonst Crash). */
    @Test
    fun `Platzhalter stimmen zwischen Deutsch und Englisch ueberein`() {
        val placeholder = Regex("%\\d+\\\$[sd]")
        val mismatched = de.keys
            .filter { it.startsWith("calendar_") && it in en }
            .mapNotNull { key ->
                val dePh = placeholder.findAll(de[key]!!).map { it.value }.toSortedSet()
                val enPh = placeholder.findAll(en[key]!!).map { it.value }.toSortedSet()
                if (dePh != enPh) "$key: DE=$dePh EN=$enPh" else null
            }
        assertThat(mismatched).isEmpty()
    }

    /**
     * Deutsch bleibt die Quelle: `values/` ist der Fallback. Der Test
     * dokumentiert die Richtung — es gibt keine `values-de/`.
     */
    @Test
    fun `Deutsch liegt im Fallback-Ordner values`() {
        assertThat(deFile.path.replace('\\', '/')).endsWith("/values/strings_calendar.xml")
        assertThat(Locale.GERMAN.language).isEqualTo("de")
    }
}
