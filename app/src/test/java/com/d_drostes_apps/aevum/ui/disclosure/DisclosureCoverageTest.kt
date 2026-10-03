package com.d_drostes_apps.aevum.ui.disclosure

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * M18.145: Vollständigkeits-Guard für die prominenten Offenlegungen.
 *
 * ── WARUM DIESER TEST EXISTIERT ───────────────────────────────────────────
 *
 * Die Play-Ablehnung zu 1.0.19 (versionCode 20) lautet wörtlich:
 *
 *   „Anfragen zur Nutzereinwilligung und zu Laufzeitberechtigungen in der
 *    App geht keine unmittelbare Offenlegung in der App voraus."
 *
 * M18.139/M18.141 haben daraufhin Dialoge eingebaut — aber nur an den
 * Stellen, die damals bekannt waren (Trigger-Screen, Geofence-Editor,
 * Kalender-Banner). Drei Einstiege blieben unangetastet und führten weiter
 * direkt in die Systemeinstellungen:
 *
 *   1. `DigitalBalanceScreen` — „Balance" ist ein Tab in der
 *      Bottom-Navigation, ein Prüfer landet hier ohne jeden Umweg.
 *   2. `AppTrackingScreen` — über den Balance-Tab erreichbar, derselbe
 *      Nutzungszugriff, ebenfalls direkt.
 *   3. `CalendarRulesScreen` (PermanentDenied-Zweig) — der Banner-Button
 *      ging am Gate vorbei in die App-Einstellungen.
 *
 * Der Fehler der ersten Runde war die Prüfmethode: die damaligen Guards
 * schauten auf **zwei bekannte Dateien**. Dieser Test prüft stattdessen die
 * **Gegenrichtung** — er sucht in allen Screen-Dateien nach Wegen, die eine
 * sensible Systemseite öffnen, und verlangt für jede ein Gate. Ein neuer
 * Screen kann die Offenlegung damit nicht mehr unbemerkt umgehen.
 *
 * ── KONVENTION, DIE DER TEST DURCHSETZT ──────────────────────────────────
 *
 * UI-Screens heißen `*Screen.kt` (bestehende Konvention im Projekt:
 * `TriggerSettingsScreen`, `DigitalBalanceScreen`, `AppTrackingScreen`,
 * `CalendarRulesScreen`, `AutomationScreens`, …). Nur diese Dateien werden
 * auf Direkt-Sprünge geprüft — ViewModels sind die *Ziele* des Gate-Callbacks,
 * nicht die Aufrufer; sie dürfen die Settings-Intents weiterhin enthalten.
 */
class DisclosureCoverageTest {

    private fun sourceFile(relative: String): File {
        val candidates = listOf(
            File(".").resolve(relative),
            File("..").resolve("app/$relative"),
            File("../app").resolve(relative),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Datei nicht gefunden: $relative (CWD=${File(".").absolutePath})")
    }

    private fun kotlinSource(relative: String): String = sourceFile(relative).readText()

    private fun mainJavaRoot(): File =
        listOf(
            File("app/src/main/java"),
            File("../app/src/main/java"),
            File("src/main/java"),
        ).firstOrNull { it.exists() }
            ?: error("app/src/main/java nicht gefunden (CWD=${File(".").absolutePath})")

    /** Alle `*Screen*.kt`-Dateien unter app/src/main/java. */
    private fun allScreenSources(): List<Pair<String, String>> =
        mainJavaRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name.contains("Screen") }
            .map { it.path to it.readText() }
            .toList()

    /** Zeilen ohne Kommentare — sonst zählen bloße Erwähnungen als Treffer. */
    private fun codeLines(source: String): List<String> =
        source.lines().filter { line ->
            val t = line.trimStart()
            !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*")
        }

    private fun codeHits(source: String, needle: String): List<String> =
        codeLines(source).filter { it.contains(needle) }

    private val balanceScreen =
        "src/main/java/com/d_drostes_apps/aevum/ui/screens/digitalbalance/DigitalBalanceScreen.kt"
    private val appTrackingScreen =
        "src/main/java/com/d_drostes_apps/aevum/ui/screens/apptracking/AppTrackingScreen.kt"
    private val calendarScreen =
        "src/main/java/com/d_drostes_apps/aevum/ui/screens/calendar/CalendarRulesScreen.kt"

    /** Die drei Screens, die 1.0.19 noch am Gate vorbeigeleitet hat. */
    private val gatedScreens = mapOf(
        "Balance-Tab (DigitalBalanceScreen)" to balanceScreen,
        "App-Aufzeichnung (AppTrackingScreen)" to appTrackingScreen,
        "Kalender-Regeln (CalendarRulesScreen)" to calendarScreen,
    )

    // ── 1. Jeder dieser Screens nutzt das Gate vollständig ────────────────

    @Test
    fun `jeder Screen mit Sonderzugriff nutzt das Disclosure-Gate`() {
        gatedScreens.forEach { (label, path) ->
            val src = kotlinSource(path)
            assertWithMessage("$label erzeugt kein Permission-Disclosure-Gate")
                .that(src.contains("rememberPermissionDisclosureGate()")).isTrue()
            assertWithMessage(
                "$label rendert den Erklärungsdialog nicht — das Gate bliebe " +
                    "gesetzt und die Aktion liefe nie"
            ).that(src.contains("PermissionDisclosureDialog(")).isTrue()
            assertWithMessage("$label schließt den Dialog nicht über consent()")
                .that(src.contains("permissionGate.consent")).isTrue()
            assertWithMessage("$label schließt den Dialog nicht über dismiss()")
                .that(src.contains("permissionGate.dismiss()")).isTrue()
        }
    }

    // ── 2. Kein Screen umgeht das Gate per direktem Methodenverweis ───────

    /**
     * Ein direkter Methodenverweis (`onOpenSettings = viewModel::openSettings`)
     * ist genau der Bypass, der die Ablehnung erzeugt hat: der Sprung passiert
     * ohne ein Wort in der App. Der Aufruf muss im Gate-Callback liegen.
     */
    @Test
    fun `kein Screen oeffnet den Sonderzugriff per direktem Methodenverweis`() {
        val forbidden = listOf(
            "viewModel::openUsageAccessSettings",
            "viewModel::openSettings",
            "viewModel::openCalendarSettings",
        )
        gatedScreens.forEach { (label, path) ->
            val src = kotlinSource(path)
            forbidden.forEach { ref ->
                assertWithMessage(
                    "$label übergibt `$ref` direkt — das umgeht den " +
                        "Erklärungsdialog. Der Aufruf muss im Gate-Callback liegen."
                ).that(codeHits(src, ref)).isEmpty()
            }
        }
    }

    // ── 3. Projektweite Suche über ALLE Screen-Dateien ────────────────────

    /**
     * Sucht in jeder `*Screen*.kt`-Datei nach Stellen, die einen Sonderzugriff
     * öffnen. Für jede muss die Datei ein Gate besitzen.
     *
     * Das ist der Unterschied zu M18.141: nicht „die zwei bekannten Dateien
     * sind ok", sondern „keine Screen-Datei darf ohne Gate auskommen".
     */
    @Test
    fun `projektweit oeffnet keine Screen-Datei einen Sonderzugriff ohne Gate`() {
        val markers = listOf(
            "ACTION_USAGE_ACCESS_SETTINGS",
            "UsageStatsPermission.openSettings",
            "openUsageAccessSettings(",
        )
        val offenders = mutableListOf<String>()
        var screenedFiles = 0

        allScreenSources().forEach { (path, src) ->
            val hits = markers.flatMap { codeHits(src, it) }
            if (hits.isEmpty()) return@forEach
            screenedFiles++

            val hasGate = src.contains("rememberPermissionDisclosureGate()") &&
                src.contains("PermissionDisclosureDialog(")

            if (!hasGate) {
                offenders += "$path\n      " + hits.joinToString("\n      ") { it.trim() }
            }
        }

        assertWithMessage(
            "Diese Screen-Dateien öffnen einen Sonderzugriff OHNE vorgeschalteten " +
                "Erklärungsdialog — genau der Grund der Play-Ablehnung:\n" +
                offenders.joinToString("\n\n")
        ).that(offenders).isEmpty()

        // Gegenprobe der Suchbasis: der Test muss die bekannten Screens
        // tatsächlich gefunden haben, sonst prüft er nichts.
        assertWithMessage(
            "Die Suche hat keinen einzigen Screen mit Sonderzugriff gefunden — " +
                "dann kann dieser Test nichts absichern."
        ).that(screenedFiles).isAtLeast(2)
    }

    // ── 4. Gegenprobe: die Erkennung findet einen echten Bypass ───────────

    /**
     * Ein Guard, der nie fehlschlagen kann, ist wertlos. Diese Gegenprobe
     * simuliert einen direkten Sprung und stellt sicher, dass die
     * Erkennungslogik ihn wirklich als Beanstandung ausweisen würde.
     */
    @Test
    fun `die Erkennung findet einen eingebauten Bypass`() {
        val fakeScreen = """
            package com.d_drostes_apps.aevum.ui.screens.fake

            @Composable
            fun FakeScreen(viewModel: FakeViewModel) {
                Button(onClick = { viewModel.openUsageAccessSettings() }) { Text("Allow") }
            }
        """.trimIndent()

        val markers = listOf(
            "ACTION_USAGE_ACCESS_SETTINGS",
            "UsageStatsPermission.openSettings",
            "openUsageAccessSettings(",
        )
        val hits = markers.flatMap { codeHits(fakeScreen, it) }
        assertWithMessage("Die Markersuche erkennt den Direktaufruf nicht")
            .that(hits).isNotEmpty()

        val hasGate = fakeScreen.contains("rememberPermissionDisclosureGate()") &&
            fakeScreen.contains("PermissionDisclosureDialog(")
        assertWithMessage("Ein Screen ohne Gate würde fälschlich als geschützt gelten")
            .that(hasGate).isFalse()

        // Dieselbe Datei MIT Gate muss durchgehen.
        val gatedScreen = fakeScreen.replace(
            "fun FakeScreen(viewModel: FakeViewModel) {",
            "fun FakeScreen(viewModel: FakeViewModel) {\n" +
                "    val permissionGate = rememberPermissionDisclosureGate()\n" +
                "    permissionGate.pending?.let { PermissionDisclosureDialog( ) }"
        )
        assertWithMessage("Ein gate-geschützter Screen würde fälschlich beanstandet")
            .that(
                gatedScreen.contains("rememberPermissionDisclosureGate()") &&
                    gatedScreen.contains("PermissionDisclosureDialog(")
            ).isTrue()
    }

    // ── 5. Der richtige Dialog je Berechtigung ────────────────────────────

    @Test
    fun `die Screens nutzen den passenden Berechtigungs-Dialog`() {
        listOf(
            "Balance-Tab" to kotlinSource(balanceScreen),
            "App-Aufzeichnung" to kotlinSource(appTrackingScreen),
        ).forEach { (label, src) ->
            assertWithMessage("$label fragt nicht den Nutzungszugriff-Dialog an")
                .that(src.contains("PermissionDisclosureKind.USAGE_ACCESS")).isTrue()
        }
        assertWithMessage("Kalender-Screen fragt nicht den Kalender-Dialog an")
            .that(kotlinSource(calendarScreen).contains("PermissionDisclosureKind.CALENDAR"))
            .isTrue()
    }
}
