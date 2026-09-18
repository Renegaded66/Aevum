package com.d_drostes_apps.aevum.domain.digital

/**
 * M18.131: Wächter für die „Gleich gesperrt"-Vorwarnung.
 *
 * WARUM EIGENE KLASSE (statt eines HashSet im Service):
 * Der Service hält sein `warnedPkgs` nur im Speicher. Wird er neu gestartet
 * (Reboot, Limit-Änderung, Prozess-Tod — passiert täglich), ist die
 * Erinnerung weg und der Nutzer bekommt dieselbe Warnung erneut. Für eine
 * Sperr-Ankündigung ist das besonders störend, weil jede Wiederholung wie
 * eine neue nahende Sperre wirkt.
 *
 * Deshalb: Zustand als Tages-Key persistieren (genau wie die Sperr-Logik
 * pro Kalendertag rechnet). Der Schlüssel enthält das Datum — beim
 * Tageswechsel greift die Warnung also automatisch wieder, ohne Aufräumjob
 * (ein Set würde unbegrenzt wachsen und müsste gepflegt werden).
 *
 * Die Klasse ist bewusst Android-frei und damit JVM-testbar: sie bekommt
 * einen [Store], den die App über SharedPreferences und der Test über eine
 * Map implementiert.
 */
class LimitWarningGuard(private val store: Store) {

    /** Persistenz-Abstraktion — hält den Test frei von Android. */
    interface Store {
        fun contains(key: String): Boolean
        fun put(key: String)
        /** Entfernt einen Schlüssel (Rücknahme / Test-Aufräumen). */
        fun remove(key: String)
    }

    /**
     * Ist die Warnung für diese App an diesem Tag schon gezeigt worden?
     * Reine Abfrage — ändert nichts.
     */
    fun wasWarnedToday(packageName: String, nowMs: Long): Boolean =
        store.contains(key(packageName, nowMs))

    /**
     * Markiert die Warnung als gezeigt.
     *
     * @return true, wenn DIESER Aufruf sie neu gesetzt hat; false, wenn sie
     *         bereits gesetzt war. Der Aufrufer sendet die Notification nur
     *         bei true — dadurch ist „genau einmal pro App und Tag" auch
     *         dann garantiert, wenn zwei Prüfläufe dicht aufeinander folgen.
     */
    fun markWarned(packageName: String, nowMs: Long): Boolean {
        val k = key(packageName, nowMs)
        if (store.contains(k)) return false
        store.put(k)
        return true
    }

    /** Setzt die Markierung zurück (z. B. nach „Limit erhöht" oder Test). */
    fun clear(packageName: String, nowMs: Long) {
        store.remove(key(packageName, nowMs))
    }

    /**
     * Schlüssel-Format: `limit_warn_<yyyy-mm-dd>_<packageName>`.
     *
     * Das Datum in ISO-Form macht den Schlüssel sortier- und lesbar; der
     * Paketname steht dahinter, damit ein Präfix-Scan pro App möglich ist.
     * Die Zeitzone kommt vom Aufrufer über [nowMs] (Systemzeit), damit der
     * Tageswechsel zur lokalen Mitternacht geschieht — nicht zu UTC.
     */
    fun key(packageName: String, nowMs: Long): String {
        val date = java.time.Instant.ofEpochMilli(nowMs)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
        return "limit_warn_${date}_$packageName"
    }
}

/**
 * M18.131: SharedPreferences-Implementierung des [LimitWarningGuard.Store].
 *
 * Eine eigene Prefs-Datei statt der vorhandenen: der Zustand ist reine
 * Laufzeit-Bequemlichkeit („nicht zweimal warnen") und soll bei einem
 * Daten-Reset (Backup-Restore, „alle Daten löschen") nicht mitgeschleppt
 * werden.
 */
class SharedPrefsLimitWarningStore(
    private val prefs: android.content.SharedPreferences
) : LimitWarningGuard.Store {
    override fun contains(key: String): Boolean = prefs.contains(key)
    override fun put(key: String) { prefs.edit().putBoolean(key, true).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}
