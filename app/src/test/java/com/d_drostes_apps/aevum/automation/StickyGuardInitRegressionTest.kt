package com.d_drostes_apps.aevum.automation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * M18.123-Regression (Kanban t_7ecd653a): App-Crash beim Start nach
 * M18.122 — die SharedPrefs-Sticky-Guards wurden in PROPERTY-Initia-
 * lisierungen der 5 Foreground-Services konstruiert:
 *   private val stickyGuard = StickyGuardService(
 *       SharedPrefsStickyGuardPersistence(this, "..."))
 *
 * Android erzeugt Service-Instanzen via Class.newInstance() und ruft
 * attach(context, ...) ERST NACH dem Konstruktor auf — ContextWrapper
 * .mBase ist während der Property-Init also noch null und
 * getSharedPreferences(this) wirft sofort eine NullPointerException in
 * ActivityThread.handleCreateService. Der uncaught Crash in der
 * Service-Erstellung killt den Prozess, BEVOR die UI (Dashboard)
 * erscheint — exakt das gemeldete "stuerzt direkt nach dem Update ab".
 *
 * Diese Tests scannen die 5 Service-Quellen und erzwingen das sichere
 * Muster: sharedPrefs-Gestuetzte Guards nur als lateinit var mit Init
 * in onCreate — niemals als Property-Init. Ein erneuter Rueckfall auf
 * das M18.122-Muster failt hier sofort, ohne Emulator/Device.
 */
class StickyGuardInitRegressionTest {

    private val services: List<Pair<String, String>> = listOf(
        "domain/liveactivity/LiveActivityService.kt",
        "automation/geofence/GeofenceForegroundService.kt",
        "automation/apptracking/AppTrackingService.kt",
        "domain/digital/AppBlockService.kt",
        "automation/activityrecognition/DriveDetectionService.kt",
    ).map { rel ->
        // Gradle-Unit-Tests laufen mit CWD = <projekt>/app (Modul-Dir),
        // IDE-ausfuehre mit CWD = <projekt>. Beide Kandidaten probieren.
        val root = File(".").absoluteFile
        val candidate = listOf(
            File(".").resolve("src/main/java/com/d_drostes_apps/aevum/$rel"),
            File("..").resolve("app/src/main/java/com/d_drostes_apps/aevum/$rel"),
        ).firstOrNull { it.exists() }
            ?: error("Quelldatei nicht gefunden: $rel (CWD=${root.path})")
        rel to candidate.readText()
    }

    @Test
    fun `SharedPrefs-Guard wird nicht als Property-Init konstruiert`() {
        // M18.122-Muster: StickyGuardService(SharedPrefsStickyGuardPersistence(
        // DIREKT als Feld-Initialisierer (kein lateinit). Das crasht beim
        // Service-Start mit NPE (Context noch nicht attached).
        for ((name, src) in services) {
            assertWithMessage("$name: Guard darf NICHT im Property-Init stehen")
                .that(src.contains("private val stickyGuard = com.d_drostes_apps.aevum.automation.StickyGuardService("))
                .isFalse()
        }
    }

    @Test
    fun `alle 5 Services deklarieren stickyGuard als lateinit var`() {
        for ((name, src) in services) {
            assertWithMessage("$name: stickyGuard muss lateinit var sein")
                .that(src.contains("private lateinit var stickyGuard"))
                .isTrue()
        }
    }

    @Test
    fun `SharedPrefsStickyGuardPersistence steht nur in onCreate`() {
        // Die Konstruktion darf ausschliesslich NACH der Context-Attach-
        // Garantie passieren — onCreate (oder spaeter). VOR onCreate ist
        // es der NPE-Crash.
        for ((name, src) in services) {
            val creationIndex = src.indexOf("SharedPrefsStickyGuardPersistence(")
            assertWithMessage("$name: SharedPrefsStickyGuardPersistence muss vorhanden sein")
                .that(creationIndex)
                .isGreaterThan(-1)
            val onCreateIndex = src.indexOf("override fun onCreate()")
            assertWithMessage("$name: onCreate muss vorhanden sein")
                .that(onCreateIndex)
                .isGreaterThan(-1)
            assertWithMessage("$name: Konstruktion muss NACH onCreate stehen (Context attacht)")
                .that(creationIndex)
                .isGreaterThan(onCreateIndex)
        }
    }

    @Test
    fun `kein Context-Zugriff in der Property-Init-Zone der Services`() {
        // Harte Regel: KEIN getSharedPreferences/getSystemService in der
        // Property-Init-Zone (Zeilen vor onCreate). Auch andere Context-
        // Aufrufe wuerden dort NPE-crashen.
        for ((name, src) in services) {
            // Kommentare entfernen, damit Doku-Hinweise mit Beispielcode
            // (z.B. "getSharedPreferences(this) crasht...") nicht faelschlich
            // als echter Aufruf zaehlen.
            val header = src.substringBefore("override fun onCreate()")
                .lineSequence()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")
            assertWithMessage("$name: getSharedPreferences in der Property-Init-Zone")
                .that(header.contains("getSharedPreferences("))
                .isFalse()
            assertWithMessage("$name: getSystemService in der Property-Init-Zone")
                .that(header.contains("getSystemService("))
                .isFalse()
        }
    }
}
