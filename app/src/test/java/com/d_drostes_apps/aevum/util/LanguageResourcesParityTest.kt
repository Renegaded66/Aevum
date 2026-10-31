package com.d_drostes_apps.aevum.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * M34 — Wächter-Test für ALLE Sprachdateien.
 *
 * Warum auf Dateiebene: Im JVM-Test ist `R.string.x` nur eine Zahl; ob eine
 * Übersetzung existiert, sieht man dort nicht. Genau der Fehler, der sonst
 * durchrutscht, ist ein Key, der nur in `values/` und `values-de/` steht —
 * im Debug-Build (Englisch) fällt er nicht auf, auf Spanisch ist die Stelle
 * dann englisch oder deutsch.
 *
 * Geprüft wird pro Sprache:
 *  1. Es existiert für jede englische Datei eine Datei mit exakt denselben Keys.
 *  2. Alle Platzhalter (`%1$s`, `%2$d`) stimmen mit dem Original überein —
 *     sonst wirft `getString` zur Laufzeit.
 *  3. Es gibt keine leeren Werte (ein leerer Text ist ein unsichtbarer Knopf).
 *  4. Die Übersetzung ist nicht zeichengleich mit dem Englischen — außer bei
 *     bewusst sprachneutralen Strings und Endonymen.
 */
class LanguageResourcesParityTest {

    private val resDir = File("src/main/res")

    /** Sprachen, die eine vollständige Übersetzung haben müssen. */
    private val languages = listOf("de", "es", "fr", "pt", "it", "tr", "pl", "da", "nb", "sv")

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

    private fun sourceFiles(): List<File> =
        resDir.resolve("values").listFiles { file -> file.name.startsWith("strings") && file.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            ?: emptyList()

    // Kein Leerzeichen im Muster: sonst wird „40% Nachtanteil" als Platzhalter
    // „% N" gelesen und der Test meldet Fehler, die keine sind.
    private val placeholder = Regex("%%|%(?:\\d+\\$)?[-#+0.,]*\\d*(?:\\.\\d+)?[a-zA-Z]")

    @Test
    fun `jede Sprache hat fuer jede englische Datei eine Uebersetzung`() {
        val englishFiles = sourceFiles()
        assertThat(englishFiles).isNotEmpty()
        languages.forEach { language ->
            englishFiles.forEach { english ->
                val translated = resDir.resolve("values-$language/${english.name}")
                assertThat(translated.exists())
                    .isTrue()
            }
        }
    }

    @Test
    fun `jede Uebersetzung hat exakt dieselben Keys`() {
        val problems = mutableListOf<String>()
        languages.forEach { language ->
            sourceFiles().forEach { english ->
                val translation = resDir.resolve("values-$language/${english.name}")
                if (!translation.exists()) return@forEach
                val source = readStrings(english)
                val target = readStrings(translation)
                val missing = source.keys - target.keys
                val extra = target.keys - source.keys
                if (missing.isNotEmpty()) problems += "$language/${english.name} fehlt: ${missing.take(5)}"
                if (extra.isNotEmpty()) problems += "$language/${english.name} zu viel: ${extra.take(5)}"
            }
        }
        assertThat(problems).isEmpty()
    }

    @Test
    fun `Platzhalter stimmen in jeder Sprache mit dem Original ueberein`() {
        val problems = mutableListOf<String>()
        languages.forEach { language ->
            sourceFiles().forEach { english ->
                val translation = resDir.resolve("values-$language/${english.name}")
                if (!translation.exists()) return@forEach
                val source = readStrings(english)
                val target = readStrings(translation)
                source.forEach { (key, sourceValue) ->
                    val translatedValue = target[key] ?: return@forEach
                    val expected = placeholder.findAll(sourceValue).map { it.value }.toSortedSet()
                    val actual = placeholder.findAll(translatedValue).map { it.value }.toSortedSet()
                    if (expected != actual) {
                        problems += "$language/$key: erwartet $expected, gefunden $actual"
                    }
                }
            }
        }
        assertThat(problems).isEmpty()
    }

    @Test
    fun `keine Uebersetzung ist leer`() {
        val problems = mutableListOf<String>()
        languages.forEach { language ->
            sourceFiles().forEach { english ->
                val translation = resDir.resolve("values-$language/${english.name}")
                if (!translation.exists()) return@forEach
                readStrings(translation).forEach { (key, value) ->
                    if (value.isBlank()) problems += "$language/${english.name}:$key"
                }
            }
        }
        assertThat(problems).isEmpty()
    }

    @Test
    fun `Uebersetzungen sind nicht einfach der englische Text`() {
        // Bewusst sprachneutral: Endonyme, Eigennamen, reine Zahl-/Einheit-
        // Strings. Alles andere, das zeichengleich bleibt, wurde vergessen.
        val neutral = setOf(
            "app_name",
            "app_name_debug"
        )
        val problems = mutableListOf<String>()
        languages.forEach { language ->
            sourceFiles().forEach { english ->
                val translation = resDir.resolve("values-$language/${english.name}")
                if (!translation.exists()) return@forEach
                val source = readStrings(english)
                val target = readStrings(translation)
                source.forEach { (key, sourceValue) ->
                    if (key in neutral || key.startsWith("language_")) return@forEach
                    val translatedValue = target[key] ?: return@forEach
                    if (sourceValue.trim() != translatedValue.trim()) return@forEach
                    // Gleich bleiben dürfen: URLs, Format-Strings
                    // („%1$d min"), Marken („Aevum Review") und kurze
                    // Lehnwörter („Pause", „Stop"). Ein vergessener ABSATZ
                    // hat dagegen viele Wörter — nur der zählt hier.
                    if (sourceValue.contains("://")) return@forEach
                    val words = placeholder.replace(sourceValue, " ")
                        .split(Regex("[^\\p{L}]+"))
                        .count { it.length >= 2 }
                    if (words >= 5) {
                        problems += "$language/$key: \"$sourceValue\""
                    }
                }
            }
        }
        // Ein vergessener Absatz ist ein Fehler, kein Ausreißer: die Liste
        // muss leer sein. Kurze Lehnwörter und Format-Strings sind oben
        // bereits ausgenommen.
        assertThat(problems).isEmpty()
    }

    @Test
    fun `jede Sprache hat einen Ressourcen-Ordner fuer jeden Katalog-Eintrag`() {
        // Englisch liegt in `values/`, `system` hat bewusst keinen Ordner —
        // beide sind keine eigenen Übersetzungsordner.
        AppLanguage.selectable
            .filterNot { it.code == "en" || it.code == "system" }
            .forEach { language ->
            val dir = resDir.resolve("values-${language.code}")
            assertThat(dir.isDirectory).isTrue()
            assertThat(dir.listFiles()?.count { it.name.startsWith("strings") }).isAtLeast(15)
        }
    }
}
