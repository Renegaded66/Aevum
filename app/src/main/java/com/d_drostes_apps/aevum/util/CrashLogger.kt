package com.d_drostes_apps.aevum.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * M17.4: Crash-Logger.
 *
 * Installiert einen [Thread.UncaughtExceptionHandler] der jeden unbehandelten
 * Crash-Trace in eine Datei schreibt und eine Notification mit kurzem Hinweis
 * postet. Wird aus [com.d_drostes_apps.aevum.AevumApplication.onCreate]
 * installiert.
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
 * M18.125 (User: "Android/data ist leer / ich habe keine SD-Karte, speichere
 * die Logs unter Downloads"): Auf Android 13+ ist `/sdcard/Android/data/`
 * für Dateimanager UNSICHTBAR (nicht leer — versteckt). Deshalb wird jeder
 * Crash zusätzlich in den ÖFFENTLICHEN Downloads-Ordner gespiegelt
 * (MediaStore.Downloads, ab API 29 OHNE Storage-Permission möglich) —
 * `Downloads/Aevum/aevum_crash_<Zeitstempel>.log` — damit der User die
 * Datei direkt sehen und weiterleiten kann.
 *
 * M18.125: Zusätzlich wird ein Logcat-Schnappschuss (letzte Zeilen mit
 * AndroidRuntime/FATAL/Aevum-Tags) an den Trace angehängt — der
 * Stacktrace allein sagt oft nicht, in welchem Modus/Service der Crash
 * passierte (fahrt-gekoppelter Hintergrund-Crash).
 *
 * Warum KEIN Timber / Firebase Crashlytics / Sentry: einfachster lokaler
 * Crash-Logger. Kein Cloud-Setup, kein Privacy-Issue, kein Backend.
 * Genug, um den nächsten Crash zu diagnostizieren.
 */
object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val LOG_FILE_NAME = "last-crash.log"
    private const val PREV_CRASH_FILE_NAME = "previous-crash.log"
    private const val DOWNLOADS_SUBDIR = "Aevum"
    private const val LOGCAT_LINES = 600

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
                Log.e(TAG, "Crash geschrieben ($LOG_FILE_NAME + Downloads/Aevum; siehe Files-App)", throwable)
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
            // M18.125: Logcat-Schnappschuss für Kontext (Service-Modi,
            // letzte Events vor dem Crash). Best effort — schlägt fehl,
            // ist der Rest der Datei trotzdem da.
            appendLine()
            appendLine("----- Logcat-Ausschnitt (letzte Zeilen) -----")
            appendLine(captureLogcatTail())
        }
        current.writeText(content)
        // M18.125: Öffentlicher Spiegel nach Downloads (Android/data ist
        // auf Android 13+ für Dateimanager unsichtbar).
        mirrorToDownloads(context, content)
    }

    /** M18.125: Letzte [LOGCAT_LINES] Logcat-Zeilen, gefiltert auf die für
     *  den Aevum-Kontext relevanten Tags. Timeout 1,5s — der Handler darf
     *  den Prozess nicht unnötig aufhalten. Schlicht sequenziell geschrieben
     *  (var + explizite Zuweisungen), damit der Kotlin-Compiler keinen
     *  Desync in verschachtelten try-Ausdrücken mit use{} erzeugt. */
    private fun captureLogcatTail(): String {
        var result = "(Logcat nicht verfügbar)"
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", LOGCAT_LINES.toString())
            )
            try {
                val text = process.inputStream.bufferedReader().use { reader ->
                    reader.readText()
                }
                process.waitFor(1, TimeUnit.SECONDS)
                val filtered = text.lineSequence()
                    .filter { line: String ->
                        line.contains("AndroidRuntime") ||
                            line.contains("FATAL") ||
                            line.contains("Aevum") ||
                            line.contains("Drive") ||
                            line.contains("LiveActivity") ||
                            line.contains("CrashLogger") ||
                            line.contains("StickyGuard")
                    }
                    .take(150)
                    .joinToString("\n")
                result = if (filtered.isNotBlank()) filtered else
                    "(keine relevanten Logcat-Zeilen verfügbar)"
            } finally {
                try { process.destroy() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            result = "(Logcat nicht verfügbar: ${e.message})"
        }
        return result
    }

    /** M18.125: Crash-Trace in den öffentlichen Downloads-Ordner spiegeln
     *  (MediaStore.Downloads, API 29+ — kein Storage-Permission nötig). */
    private fun mirrorToDownloads(context: Context, content: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val fileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(Date()) + "_aevum_crash.log"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOADS_SUBDIR
                )
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return
            resolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
            Log.i(TAG, "Crash-Log gespiegelt nach Downloads/Aevum/$fileName")
        } catch (e: Exception) {
            // Spiegel ist nur Komfort — der interne Pfad bleibt der
            // verlässliche Speicherort.
            Log.w(TAG, "Downloads-Spiegel fehlgeschlagen: ${e.message}")
        }
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
