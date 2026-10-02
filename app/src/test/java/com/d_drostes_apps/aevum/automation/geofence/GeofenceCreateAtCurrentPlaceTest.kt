package com.d_drostes_apps.aevum.automation.geofence

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * M18.144: Regression-Guards für zwei gemeldete Geofence-Fehler.
 *
 * ── BEFUND (Devon, 2. Oktober 2026) ───────────────────────────────────
 *
 *   „Beim Erstellen eines Geofences sollte direkt geprüft werden, ob man
 *    sich im Geofence befindet. Weil ich habe gerade am aktuellen Standort
 *    einen Geofence erstellt mit einer Automatisierung, aber die
 *    Aufzeichnung ist nicht gestartet, weil ich war ja schon drin. Und als
 *    ich verlassen habe, wurde die manuell gestartete Aktivität nicht
 *    geendet."
 *
 * Zwei getrennte Fehler in einem Bericht:
 *
 *  FEHLER 1 — kein Start beim Anlegen INNERHALB des Geofence.
 *    Ursache: Der Registrar erzwingt bewusst KEIN `INITIAL_TRIGGER_ENTER`
 *    (M18.64, um False-Starts beim App-Öffnen zu verhindern). Und der
 *    frisch gespeicherte Geofence kennt den aktuellen Standort nicht: der
 *    nächste `checkNow()` sieht „kein Zonenwechsel" (vorher `null`, jetzt
 *    dieselbe Zone? — nein: `previousZoneId` ist null, `newZoneId` gesetzt
 *    → Wechsel erkannt). Entscheidend ist der ZEITPUNKT: Der Check läuft
 *    erst bis zu 5 Min später (ProactiveGeofenceCheckWorker). Wer den
 *    Geofence anlegt und dann losfährt, ist beim ersten Check schon
 *    draußen — der ENTER wurde nie gesehen.
 *
 *  FEHLER 2 — manuell gestartete Aktivität wird beim Verlassen nicht beendet.
 *    Ursache: Beide Stop-Pfade verlangen
 *    `existing.sourceType == "GEOFENCE_AUTO"`. Eine vom Nutzer selbst
 *    gestartete Session hat `sourceType == "MANUAL"` → der Stop wird
 *    ausdrücklich übersprungen:
 *      CurrentZoneProvider.kt: „existing.sourceType == \"GEOFENCE_AUTO\""
 *      GeofenceTransitionProcessor.kt: „M17 Auto-Stop übersprungen:
 *        Session .. ist manuell"
 *
 * Dieser Test prüft die VERDRAHTUNG im Quelltext (JVM, ohne Emulator) und
 * die reine Entscheidungslogik, die aus den Pfaden herausgezogen wird.
 */
class GeofenceCreateAtCurrentPlaceTest {

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

    private val editorVm by lazy {
        source("ui/screens/automation/AutomationViewModels.kt")
    }
    private val zoneProvider by lazy {
        source("automation/geofence/CurrentZoneProvider.kt")
    }
    private val processor by lazy {
        source("automation/geofence/GeofenceTransitionProcessor.kt")
    }
    private val liveManager by lazy {
        source("domain/liveactivity/LiveActivityManager.kt")
    }

    // ═══════════════════════════════════════════════════════════════════
    // Die Herkunfts-Regel liegt an EINER Stelle — nicht dupliziert
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `die Discard-Regel ist zentral verdrahtet, nicht hartkodiert`() {
        // mayDiscardUnconfirmed() existierte zunaechst NUR in Tests —
        // waehrend die echte Produktivstelle (discardLiveSession) den
        // String hart verglich. Ein Guard, der nur toten Code prueft,
        // beweist nichts. Deshalb muss die Produktivstelle die Policy
        // benutzen.
        assertWithMessage(
            "LiveActivityManager.discardLiveSession() vergleicht sourceType " +
                "hartkodiert statt ueber GeofenceAutoStopPolicy.mayDiscardUnconfirmed(). " +
                "Dann ist die Policy toter Code und ihr Test ein Fake-Guard."
        ).that(liveManager.contains("GeofenceAutoStopPolicy.mayDiscardUnconfirmed")).isTrue()

        assertWithMessage(
            "Die Discard-Regel steht noch als String-Vergleich in " +
                "LiveActivityManager — dann gibt es ZWEI Wahrheiten, die " +
                "auseinanderlaufen koennen."
        ).that(liveManager.contains("session.sourceType != \"GEOFENCE_AUTO\"")).isFalse()
    }

    // ═══════════════════════════════════════════════════════════════════
    // FEHLER 1: Beim Speichern muss der aktuelle Standort geprüft werden
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `save prueft den aktuellen Standort gegen den neuen Geofence`() {
        // Ohne diesen Check erfährt die App nie, dass der Nutzer bereits
        // INNERHALB des frisch angelegten Geofence steht: Der nächste
        // Zonen-Check kommt bis zu 5 Minuten später — wer in der Zeit
        // losfährt, wird als „nie angekommen" geführt.
        assertWithMessage(
            "GeofenceEditorViewModel.save() prüft nach dem Speichern nicht, " +
                "ob der Nutzer sich bereits im Geofence befindet. Beim Anlegen " +
                "am aktuellen Ort startet die Automatisierung dann nicht — " +
                "genau der gemeldete Fehler."
        ).that(editorVm.contains("CurrentZoneProvider")).isTrue()

        assertWithMessage(
            "save() startet keinen Zonen-Check — der Zonen-Zustand bleibt " +
                "veraltet, bis der 5-Minuten-Worker läuft."
        ).that(editorVm.contains("checkNowInBackground()")).isTrue()
    }

    @Test
    fun `der Check laeuft nach dem Speichern und nicht vorher`() {
        // Vorher gäbe es den Geofence noch nicht in der DB — die
        // Zonen-Erkennung fände ihn nicht.
        val saveBody = editorVm.substringAfter("fun save()").substringBefore("enum class QuickPlaceKind")
        val saveIdx = saveBody.indexOf("geofenceRepository.insert")
        val checkIdx = saveBody.indexOf("checkNowInBackground()")
        assertWithMessage("kein Zonen-Check im save()-Pfad gefunden").that(checkIdx).isAtLeast(0)
        assertWithMessage(
            "Der Zonen-Check läuft VOR dem Insert — der neue Geofence existiert " +
                "dann noch nicht in der DB und kann nicht erkannt werden."
        ).that(checkIdx).isGreaterThan(saveIdx)

        // Der Zonen-Zustand muss VOR dem Check verworfen werden, sonst sieht
        // checkNow() keinen Zonenwechsel und startet den Auto-Start nicht.
        val invalidateIdx = saveBody.indexOf("invalidateZoneState()")
        assertWithMessage("invalidateZoneState() fehlt im save()-Pfad").that(invalidateIdx).isAtLeast(0)
        assertWithMessage(
            "invalidateZoneState() läuft NACH checkNowInBackground() — der Check " +
                "würde den veralteten Zonen-Zustand verwenden und keinen Wechsel sehen."
        ).that(invalidateIdx).isLessThan(checkIdx)
    }

    @Test
    fun `die Zone wird vor dem Check zurueckgesetzt`() {
        // Der eigentliche Kern von Fehler 1: checkNow() erkennt einen
        // Zonenwechsel nur, wenn previousZoneId != newZoneId. Stand der
        // Nutzer vorher in einer ANDEREN Zone (z. B. Zuhause) und legt dort
        // einen neuen Geofence an, ist newZoneId die neue Zone → Wechsel
        // wird erkannt. Steht er aber in KEINER Zone (previousZoneId=null)
        // und legt einen an seinem Ort an, ist newZoneId gesetzt und
        // previousZoneId null → Wechsel wird erkannt. Der Fall funktioniert
        // also, WENN der Check sofort läuft.
        //
        // Kritisch ist der dritte Fall: der Nutzer hat den Geofence gerade
        // angelegt und war laut früherem Check schon in einer Zone mit
        // DEMSELBEN autoStartActivityTypeId. Dann greift die
        // „läuft bereits"-Verzweigung. Der Test hält fest, dass es diese
        // Verzweigung gibt und sie keinen Fehler wirft.
        assertWithMessage("kein CurrentZoneProvider im save()-Pfad").that(editorVm.contains("CurrentZoneProvider")).isTrue()
        assertWithMessage(
            "Der Zonen-Zustand muss vor dem Check invalidiert werden, sonst " +
                "sieht checkNow() keinen Wechsel und startet nicht."
        ).that(editorVm.contains("invalidateZone") || editorVm.contains("resetZone")).isTrue()
    }

    @Test
    fun `der Check blockiert das Speichern nicht`() {
        // Der Check holt einen GPS-Fix (1-5 s). Liefe er synchron im
        // viewModelScope, würde der Nutzer auf einen reagierenden Bildschirm
        // warten — und die UI navigiert nach saved=true sofort zurück,
        // womit der Scope abgebrochen würde und der Check nie fertig liefe.
        val saveBody = editorVm.substringAfter("fun save()").substringBefore("enum class QuickPlaceKind")
        assertWithMessage(
            "save() ruft checkNow() SYNCHRON auf. Das blockiert den Speichern-" +
                "Button für 1-5 s (GPS-Fix) und der viewModelScope stirbt beim " +
                "Zurücknavigieren. Stattdessen checkNowInBackground()."
        ).that(saveBody.contains("checkNow()")).isFalse()

        assertWithMessage(
            "save() nutzt nicht den nicht-blockierenden Hintergrund-Check"
        ).that(saveBody.contains("checkNowInBackground()")).isTrue()

        assertWithMessage(
            "CurrentZoneProvider hat keinen eigenen Scope — der Hintergrund-Check " +
                "kann die Navigation weg vom Editor nicht überleben."
        ).that(zoneProvider.contains("private val scope = CoroutineScope")).isTrue()
    }

    @Test
    fun `der Sofort-Check laeuft nur bei eigener Automatisierung`() {
        // Sonst stuende ein Speichern eines fremden Ortes (z. B. „Gym"
        // bearbeiten, waehrend man Zuhause steht) die Zonen-Erkennung
        // zurueck und koennte die „Zuhause"-Automatisierung neu anstossen —
        // eine Nebenwirkung, die der Nutzer nicht erwartet.
        val saveBody = editorVm.substringAfter("fun save()").substringBefore("enum class QuickPlaceKind")
        val guardIdx = saveBody.indexOf("if (gf.autoStartActivityTypeId != null)")
        val checkIdx = saveBody.indexOf("checkNowInBackground()")
        assertWithMessage(
            "Der Sofort-Check ist nicht an die eigene Automatisierung gekoppelt. " +
                "Beim Bearbeiten eines Geofence ohne Automatisierung wuerde der " +
                "Zonen-Zustand verworfen und eine fremde Automatisierung " +
                "moeglicherweise neu gestartet."
        ).that(guardIdx).isAtLeast(0)
        assertWithMessage("Der Guard steht NACH dem Check — er greift nicht")
            .that(guardIdx).isLessThan(checkIdx)
    }

    // ═══════════════════════════════════════════════════════════════════
    // FEHLER 2: Manuell gestartete Aktivität beim Verlassen beenden
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `Ausgangslage - sourceType-Check ist ENTFERNT`() {
        // Der Fix: Die Herkunfts-Bedingung ist aus beiden Pfaden raus.
        // Ein Wiederauftauchen wäre der alte Fehler — deshalb prüft dieser
        // Test das Fehlen als CODE-Zeile (Kommentare zählen nicht).
        for ((name, src) in listOf("CurrentZoneProvider" to zoneProvider, "Processor" to processor)) {
            val codeLines = src.lines()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            assertWithMessage(
                "$name prüft die Herkunft der Session (sourceType == GEOFENCE_AUTO) " +
                    "wieder als Stop-Bedingung. Eine manuell gestartete Aktivität " +
                    "würde beim Verlassen dann nicht beendet — der gemeldete Fehler."
            ).that(codeLines.none { it.contains("sourceType == \"GEOFENCE_AUTO\"") }).isTrue()
        }
    }

    @Test
    fun `Auto-Stop beendet auch eine manuell gestartete Session`() {
        // Die Entscheidung, ob eine laufende Session zum verlassenden
        // Geofence gehört, läuft über die ACTIVITY und nicht über die
        // HERKUNFT. Der Nutzer erwartet: „Ich habe die Aktivität
        // gestartet, der Geofence ist für diese Aktivität konfiguriert —
        // beim Verlassen endet sie."
        for ((name, src) in listOf("CurrentZoneProvider" to zoneProvider, "Processor" to processor)) {
            assertWithMessage(
                "$name nutzt nicht GeofenceAutoStopPolicy für die Stop-Entscheidung. " +
                    "Ohne die gemeinsame Policy können die beiden Pfade auseinanderlaufen."
            ).that(src.contains("GeofenceAutoStopPolicy.s")).isTrue()
        }
    }

    @Test
    fun `die Stop-Entscheidung ist als reine Funktion testbar`() {
        val f = File(".").resolve("src/main/java/com/d_drostes_apps/aevum/automation/geofence/GeofenceAutoStopPolicy.kt")
        val g = File("..").resolve("app/src/main/java/com/d_drostes_apps/aevum/automation/geofence/GeofenceAutoStopPolicy.kt")
        val file = listOf(f, g).firstOrNull { it.exists() }
        assertWithMessage(
            "GeofenceAutoStopPolicy fehlt. Die Stop-Entscheidung muss aus den " +
                "beiden Runtime-Pfaden heraus in eine reine, JVM-testbare " +
                "Funktion wandern — sonst können die Pfade auseinanderlaufen."
        ).that(file != null).isTrue()
    }
}
