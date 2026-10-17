package com.d_drostes_apps.aevum.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App 1.0.34: Lädt ein Update-APK aus den öffentlichen GitHub-Releases und
 * öffnet den System-Installationsdialog. Das APK landet im privaten
 * App-Ordner (kein Speicher-Zugriff, kein Download-Manager nötig) und wird
 * über den FileProvider für den Installer freigegeben.
 */
@Singleton
class AppUpdateInstaller @Inject constructor() {

    /** Lädt das APK nach filesDir/updates/; onProgress meldet 0–100. */
    suspend fun download(
        context: Context,
        update: AppUpdate,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "updates").apply { mkdirs() }
        val target = File(directory, update.apkName)
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "AevumApp")
                // GitHub liefert Assets über einen Redirect — GET folgt ihm.
                instanceFollowRedirects = true
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("HTTP $status beim APK-Download")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.apkSizeBytes
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                    output.flush()
                }
            }
            target
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Öffnet den Installationsdialog des Systems. Android zeigt dabei (einmalig
     * pro App) die Freigabe „Unbekannte Apps installieren" — das ist der
     * normale, sichere Weg für App-Updates außerhalb des Play Stores.
     */
    fun install(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
