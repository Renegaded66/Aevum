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
}
