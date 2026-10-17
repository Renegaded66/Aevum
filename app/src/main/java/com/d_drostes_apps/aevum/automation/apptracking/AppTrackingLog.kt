package com.d_drostes_apps.aevum.automation.apptracking

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M20: DIAGNOSE-Protokoll der App-Aufzeichnung.
 *
 * Warum es existiert: Die bisherigen Zuverlässigkeits-Runden (M19.2/M19.3)
 * konnten am echten Gerät nicht nachvollzogen werden — Fehlschläge der
 * Wiederbelebung (z. B. `ForegroundServiceStartNotAllowedException` aus dem
 * Hintergrund), Faltungs-Zustände und Übergabe-Entscheidungen wurden nur in
 * Logcat geloggt, den beim Nutzer niemand liest. Dieses Protokoll schreibt
 * die ENTSCHEIDENDEN Ereignisse in einen kleinen Ringpuffer (SharedPrefs,
 * letzte [MAX_LINES] Zeilen — direkt in der App-Anzeige les- und
 * kopierbar) ZUSÄTZLICH als Datei in den App-spezifischen Speicher
 * (`getExternalFilesDir/apptracking-log.txt`).
 *
 * Bewusst OHNE Android-Logcat-Abhängigkeit (kein Log.d-Zwang) und ohne
 * jeden Poll zu loggen: Nur ZustandsWECHSEL und Fehlschläge kommen rein
 * (sonst wäre der Puffer nach 8 Minuten voll). Beispiele:
 *  - "Service onCreate (screenOn=true)"
 *  - "START(boot) ok" / "START(boot) blockiert — Akku-Optimierung"
 *  - "Vordergrund: null -> com.duolingo"
 *  - "Digital gestoppt (Race-sicher) — com.duolingo übernimmt"
 *  - "Worker: Digital-Skip — getrackte App 'com.duolingo' im Vordergrund"
 *
 * Thread-sicher (synchronized): Aufrufer sind Service-Poll (IO),
 * Broadcast-Receiver (IO), Worker (suspend) und App-Start (Main) gemischt.
 */
object AppTrackingLog {

    private const val PREFS = "aevum_apptracking_diagnostics"
    private const val KEY = "log_lines_v1"
    private const val MAX_LINES = 240
    private const val FILE_NAME = "apptracking-log.txt"
    private const val MAX_FILE_BYTES = 256 * 1024L

    private val lock = Any()
    private val fmt = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY)

    fun i(context: Context, message: String) = add(context.applicationContext, "I", message)

    fun w(context: Context, message: String) = add(context.applicationContext, "W", message)

    private fun add(context: Context, level: String, message: String) {
        val line = "${fmt.format(Date())} [$level] $message"
        synchronized(lock) {
            try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val current = prefs.getString(KEY, null)
                    ?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
                val trimmed = (current + line).takeLast(MAX_LINES)
                prefs.edit().putString(KEY, trimmed.joinToString("\n")).apply()
            } catch (_: Exception) {
                // Diagnose darf NIE etwas kaputt machen.
            }
            try {
                val dir = context.getExternalFilesDir(null) ?: context.filesDir
                val file = File(dir, FILE_NAME)
                if (file.exists() && file.length() > MAX_FILE_BYTES) {
                    // Rotation: die zweite Hälfte behalten — der Puffer bleibt
                    // beschränkt, die jüngere Vergangenheit überlebt.
                    val all = file.readLines()
                    file.writeText(all.takeLast((all.size / 2).coerceAtLeast(1)).joinToString("\n", postfix = "\n"))
                }
                file.appendText(line + "\n")
            } catch (_: Exception) {
            }
        }
    }

    /** Alle Zeilen (älteste zuerst) für die Anzeige in der App. */
    fun read(context: Context): List<String> = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun readText(context: Context): String = read(context).joinToString("\n")

    fun clear(context: Context) {
        synchronized(lock) {
            try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
            } catch (_: Exception) {
            }
            try {
                val dir = context.getExternalFilesDir(null) ?: context.filesDir
                File(dir, FILE_NAME).delete()
            } catch (_: Exception) {
            }
        }
    }
}
