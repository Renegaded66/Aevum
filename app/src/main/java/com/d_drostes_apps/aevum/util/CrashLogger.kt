package com.d_drostes_apps.aevum.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M17.4: Crash-Logger.
 *
 * Installiert einen [Thread.UncaughtExceptionHandler] der jeden unbehandelten
 * Crash-Trace in eine Datei schreibt (Files-app-reachable) und eine
 * Notification mit kurzem Hinweis postet. Wird aus
 * [com.d_drostes_apps.aevum.AevumApplication.onCreate] installiert.
 *
 * **Wichtig — keine defensiven try/catch:** Der Handler ruft am Ende den
 * vorherigen Default-Handler auf, damit das System-Crash-Verhalten erhalten
 * bleibt (der User sieht den Standard-Android-Crash-Dialog, die App
 * schließt). Wir KASCHIEREN den Crash NICHT — wir schreiben nur die
 * Diagnose-Daten, die der Entwickler braucht.
 *
 * Pfad: `getExternalFilesDir(null)/last-crash.log` — auf Android 10+
 * Files-app-reachable ohne Storage-Permission, weil
 * `getExternalFilesDir` App-Specific-Storage ist.
 *
 * Warum KEIN Timber / Firebase Crashlytics / Sentry: einfachster lokaler
 * Crash-Logger. Kein Cloud-Setup, kein Privacy-Issue, kein Backend.
 * Genug, um den nächsten Crash zu diagnostizieren.
 */
object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val LOG_FILE_NAME = "last-crash.log"
    private const val PREV_CRASH_FILE_NAME = "previous-crash.log"

    @Volatile private var installed = false
    @Volatile private var ctxRef: Context? = null

    fun install(context: Context) {
        if (installed) return
        installed = true
        // Application-Context cachen — der Handler läuft evtl. nach Activity-Destroy,
        // da darf der Application-Context nicht null sein.
        ctxRef = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashToFile(context.applicationContext, throwable)
                Log.e(TAG, "Crash geschrieben nach $LOG_FILE_NAME (siehe Files-App / Android/data/<pkg>/files/)", throwable)
            } catch (writeError: Exception) {
                // Selbst das Schreiben ist fehlgeschlagen — wir können nichts
                // machen außer dem System-Handler die Arbeit zu überlassen.
                Log.e(TAG, "Crash-Logger konnte Trace nicht schreiben", writeError)
            }
            // M17.4: System-Crash-Handler aufrufen → Standard-Dialog zeigen,
            // App schließen. Wir KASCHIEREN den Crash nicht.
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashToFile(context: Context, throwable: Throwable) {
        val crashDir = context.getExternalFilesDir(null) ?: return
        if (!crashDir.exists()) crashDir.mkdirs()
        val current = File(crashDir, LOG_FILE_NAME)
        val previous = File(crashDir, PREV_CRASH_FILE_NAME)
        // Vorherigen Crash beiseitelegen (max 2 Generationen)
        if (current.exists()) {
            current.copyTo(previous, overwrite = true)
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMAN).format(Date())
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val trace = sw.toString()
        val content = buildString {
            appendLine("=== Aevum Crash $timestamp ===")
            appendLine("Thread: ${Thread.currentThread().name}")
            appendLine("App: ${context.packageName}")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine()
            appendLine(trace)
        }
        current.writeText(content)
    }

    /** Liest den letzten Crash-Trace (oder null wenn keiner). */
    fun readLastCrash(context: Context): String? {
        val f = File(context.getExternalFilesDir(null) ?: return null, LOG_FILE_NAME)
        return if (f.exists()) f.readText() else null
    }

    /** Gibt den Pfad zur Log-Datei zurück (für UI-Hinweise). */
    fun crashFilePath(context: Context): String? {
        val dir = context.getExternalFilesDir(null) ?: return null
        return File(dir, LOG_FILE_NAME).absolutePath
    }
}
