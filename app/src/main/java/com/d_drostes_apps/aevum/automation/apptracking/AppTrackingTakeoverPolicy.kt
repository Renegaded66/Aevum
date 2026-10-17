package com.d_drostes_apps.aevum.automation.apptracking

/**
 * M18.151: Übergabe-Regeln zwischen den SMARTPHONE-Aufzeichnungen
 * („Digital" = [SOURCE_SCREEN_AUTO] und App-zugewiesene Aufzeichnung =
 * [SOURCE_APP_TRACKING]).
 *
 * User-Spezifikation (wörtlich):
 *  - „Und auch nach den zwei Minuten Threshold, wenn also die Aufzeichnung
 *    von Digital schon gestartet ist und ich dann Instagram öffne, stoppt
 *    die Aufzeichnung von Digital und die neue Aufzeichnung von der
 *    zugewiesenen Activity wird gestartet."
 *  - „… wenn ich 2 Apps Activitys zugeordnet habe und dann eine Aufzeichnung
 *    von einer App gerade stattfindet und ich dann die App wechsle, dann soll
 *    die alte Aufzeichnung stoppen und die neue beginnen. Und zwar direkt."
 *  - Rangfolge (zwingend): „… das bezieht sich nur auf Smartphone-
 *    Aufzeichnungen — Geofence-, sportliche, manuell gestartete und
 *    Autofahrt-Aufzeichnungen haben immer Vorrang und dürfen nicht durch
 *    Digitales unterbrochen werden, egal ob Standard-Digital oder
 *    App-zugewiesen."
 *
 * Entscheidungslogik für eine neu in den Vordergrund gekommene GETRACKTE App
 * — bewusst ohne Android-Abhängigkeiten, damit die Regeln per Unit-Test
 * festgenagelt sind; die Verdrahtung sichert AppTrackingDigitalWiringTest:
 *
 *  - Läuft NICHTS → [Action.START_APP] (M18.67-Verhalten).
 *  - Läuft die EIGENE App-Session (App A → App B) → [Action.STOP_AND_START]
 *    (A endet exakt jetzt, B beginnt sofort).
 *  - Läuft „Digital" ([SOURCE_SCREEN_AUTO]) → [Action.TAKE_OVER_DIGITAL]:
 *    Digital endet exakt jetzt, die App-Activity beginnt. (Das war der
 *    gemeldete Bug: Digital lief einfach weiter.)
 *  - Läuft alles andere (Geofence/Autofahrt/Sport/manuell/Kalender/etc.)
 *    → [Action.KEEP_FOREIGN]: NICHTS anfassen, kein Start (Rangfolge).
 *
 * Bewusst NICHT in der Übernahme-Liste: CALENDAR_AUTO. Der Kalender-Lauf ist
 * ein Fallback („läuft, wenn nichts anderes läuft") und hatte schon vor
 * M18.151 Vorrang vor den Smartphone-Aufzeichnungen — daran ändert diese
 * Übergabe nichts.
 *
 * M19.3 (User-Report: „Oft zeichnet der einfach weiter digital auf obwohl
 * ich etwas bestimmtes geöffnet habe. […] Hauptsache nichts anderes wird
 * aufgezeichnet."): Zwei Nachfass-Regeln gegen die beobachtete
 * Unzuverlässigkeit — beide bewusst als PURE Funktionen hier, damit sie
 * per Unit-Test festgenagelt sind:
 *
 *  - [onRepeatedForeground]: Auch wenn DIESE getrackte App bereits als
 *    „aktuell" gilt, wird der Aufzeichnungs-Zustand nachgeprüft; ist
 *    zwischenzeitlich wieder „Digital" angelaufen, wird übernommen
 *    (Rückgabe TAKE_OVER_DIGITAL), sonst null (nichts tun).
 *  - [onStopRace]: Schlägt ein Digital-Stopp im Race-Fenster fehl, sagt
 *    diese Regel, was der frische Zustand bedeutet: Ziel erreicht
 *    (nichts läuft mehr), erneut nachfassen (wieder Digital) oder
 *    Rangfolge (fremde Quelle).
 */
object AppTrackingTakeoverPolicy {

    /** Quellen-Typ der Bildschirm-Aufzeichnung („Digital"). */
    const val SOURCE_SCREEN_AUTO = "SCREEN_AUTO"

    /** Quellen-Typ der App-zugewiesenen Aufzeichnung. */
    const val SOURCE_APP_TRACKING = "APP_TRACKING"

    enum class Action {
        /** Nichts läuft — App-Activity starten. */
        START_APP,

        /** Eigene App-Session läuft — Wechsel: alte stoppen, neue starten. */
        STOP_AND_START,

        /** „Digital" läuft — übernehmen: Digital stoppen, App-Activity starten. */
        TAKE_OVER_DIGITAL,

        /** Fremde Session (Vorrang) — nichts tun. */
        KEEP_FOREIGN
    }

    /** M19.3: Ergebnis der Nachprüfung nach einem ins Leere gegriffenen Digital-Stopp. */
    enum class StopRace {
        /** Es läuft nichts (mehr) — „nichts anderes wird aufgezeichnet" ist bereits erreicht. */
        OBJECTIVE_MET,

        /** Wieder „Digital" angelaufen — der Stopp muss EINMAL nachfassen. */
        RETRY_STOP,

        /** Höherpriore Session hat übernommen — Rangfolge, nichts anfassen. */
        KEEP_FOREIGN
    }

    /**
     * @param liveSourceType sourceType der aktuell laufenden Session
     *        (null = keine Session live).
     * @param isOwnSession true, wenn die laufende Session von diesem
     *        Service gestartet wurde (sourceType = APP_TRACKING + eigene ID).
     */
    fun onTrackedForeground(liveSourceType: String?, isOwnSession: Boolean): Action = when {
        liveSourceType == null -> Action.START_APP
        isOwnSession -> Action.STOP_AND_START
        liveSourceType == SOURCE_SCREEN_AUTO -> Action.TAKE_OVER_DIGITAL
        else -> Action.KEEP_FOREIGN
    }

    /**
     * M19.3: Was bedeutet der FRISCHE Live-Zustand, wenn ein Digital-Stopp
     * ins Leere griff (die Session wechselte im Race-Fenster)?
     *
     * @param freshSourceType sourceType des frisch gelesenen Zustands
     *        (null = keine Session).
     * @param freshIsLive lebt der frisch gelesene Zustand überhaupt?
     */
    fun onStopRace(freshSourceType: String?, freshIsLive: Boolean): StopRace = when {
        // Nichts (mehr) live — das Ziel ist bereits erreicht; der frühere
        // stille Abbruch hätte hier fälschlich „aufgegeben".
        !freshIsLive -> StopRace.OBJECTIVE_MET
        // „Digital" ist wieder da (Neuansatz) → genau EINMAL nachfassen.
        freshSourceType == SOURCE_SCREEN_AUTO -> StopRace.RETRY_STOP
        // Etwas Höherpriores hat übernommen → Rangfolge, nichts anfassen.
        else -> StopRace.KEEP_FOREIGN
    }

    /**
     * M19.3: Nachprüfung, wenn die getrackte App schon „aktuell" ist
     * ([currentTrackedApp] == Vordergrund), aber zwischen zwei Polls Zeit
     * vergangen ist. Rückgabe: TAKE_OVER_DIGITAL (übernehmen) oder null
     * (nichts tun).
     *
     * WARUM: Mit dem 2-s-Takt aus M19.3 kann innerhalb einer App-Sitzung
     * mehrfach passieren, dass „Digital" erneut angelaufen ist (der
     * Digital-Neuansatz nach einem früheren App-Ende ist asynchron). Ein
     * blindes „gleiche App → return" ließ es dann bis zum nächsten
     * App-Wechsel weiterlaufen.
     *
     * @param liveSourceType sourceType der laufenden Session (null = keine).
     * @param liveIsLive lebt die Session überhaupt?
     * @param isOwnSession läuft die eigene App-Tracking-Session?
     */
    fun onRepeatedForeground(
        liveSourceType: String?,
        liveIsLive: Boolean,
        isOwnSession: Boolean
    ): Action? = when {
        // Gar nichts live: Nichts zu tun — auch ein bewusst/manuell
        // gestoppter Zustand wird nicht automatisch neu gestartet.
        !liveIsLive -> null
        // Unsere Aufzeichnung läuft — alles korrekt.
        isOwnSession -> null
        // „Digital" läuft, obwohl diese getrackte App im Vordergrund ist
        // → übernehmen (unten läuft die race-sichere Übergabe).
        liveSourceType == SOURCE_SCREEN_AUTO -> Action.TAKE_OVER_DIGITAL
        // Fremde Quelle (Geofence/Fahrt/Sport/manuell/Kalender) → Rangfolge.
        else -> null
    }
}
