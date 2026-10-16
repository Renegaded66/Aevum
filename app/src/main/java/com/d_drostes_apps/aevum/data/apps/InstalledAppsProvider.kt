package com.d_drostes_apps.aevum.data.apps

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Eine installierte, startbare App (Basis für die Auswahl-Listen). */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?
)

/**
 * M18.147: Zentraler, inkrementeller Katalog der installierten Apps.
 *
 * Vorher lief `PackageManager.getInstalledApplications` + Launch-Intent-
 * Prüfung + Icon-Dekodierung BEI JEDEM State-Rebuild des App-Aufzeichnungs-
 * ViewModels — und zwar auf dem Main-Thread (combine-Transform). Ergebnis
 * (User-Meldung): Der Bildschirm fror beim Öffnen ~3 s ein und zeigte dann
 * alles auf einmal; auch jeder Klick (App verschieben) und jeder
 * Suchbuchstabe stieß den vollen Scan erneut an.
 *
 * Jetzt: Der Scan läuft NUR hier, auf Dispatchers.IO, und meldet das
 * Ergebnis in [CHUNK_SIZE]-Häppchen ([scanProgressive]) — die Listen füllen
 * sich sichtbar nach und nach, während die UI flüssig bleibt. Icons werden
 * prozessweit gecacht (max. 144 px Kantenlänge — angezeigt werden 28 dp,
 * der Cache bleibt dadurch klein). Ein warmer Start liefert den letzten
 * Stand sofort ([cachedApps]); der Neu-Scan aktualisiert ihn im Hintergrund.
 */
@Singleton
class InstalledAppsProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()
    private val iconMisses = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var cached: List<InstalledApp>? = null

    /** Letzter vollständiger Scan (null = noch nie geladen) — sofort nutzbar. */
    fun cachedApps(): List<InstalledApp>? = cached

    /**
     * Scannt alle installierten, startbaren Apps und meldet Zwischenstände.
     * Läuft komplett auf [Dispatchers.IO]. [onChunk] kommt in Paket-Reihenfolge
     * des Systems (unsortiert), [onComplete] liefert die fertig sortierte,
     * vollständige Liste (auch für bereits gecachte Apps — sie deckt
     * Deinstallationen auf).
     */
    suspend fun scanProgressive(
        chunkSize: Int = CHUNK_SIZE,
        onChunk: suspend (List<InstalledApp>) -> Unit,
        onComplete: suspend (List<InstalledApp>) -> Unit
    ) = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val self = context.packageName
        val infos = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != self }
        } catch (_: Exception) {
            emptyList()
        }

        val all = ArrayList<InstalledApp>(infos.size)
        var chunk = ArrayList<InstalledApp>(chunkSize)
        for (info in infos) {
            coroutineContext.ensureActive()
            // Nur Apps mit Launcher-Eintrag (gleiche Semantik wie vorher).
            val launchable = try {
                pm.getLaunchIntentForPackage(info.packageName) != null
            } catch (_: Exception) {
                false
            }
            if (!launchable) continue
            val label = try {
                pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                info.packageName
            }
            val app = InstalledApp(info.packageName, label, iconFor(pm, info.packageName))
            all += app
            chunk += app
            if (chunk.size >= chunkSize) {
                onChunk(chunk)
                chunk = ArrayList(chunkSize)
            }
        }
        if (chunk.isNotEmpty()) onChunk(chunk)

        val sorted = all.sortedBy { it.label.lowercase(Locale.getDefault()) }
        cached = sorted
        onComplete(sorted)
    }

    private fun iconFor(pm: PackageManager, packageName: String): ImageBitmap? {
        iconCache[packageName]?.let { return it }
        if (iconMisses.contains(packageName)) return null
        return try {
            val drawable = pm.getApplicationIcon(packageName)
            val width = drawable.intrinsicWidth.coerceAtLeast(1)
            val height = drawable.intrinsicHeight.coerceAtLeast(1)
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            val capped = if (width > ICON_MAX_PX || height > ICON_MAX_PX) {
                val scale = ICON_MAX_PX.toFloat() / maxOf(width, height)
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap,
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            val image = capped.asImageBitmap()
            iconCache[packageName] = image
            image
        } catch (_: Exception) {
            iconMisses.add(packageName)
            null
        }
    }

    companion object {
        /** Klein genug fürs sichtbare Nachladen, groß genug für flüssiges Scrollen. */
        const val CHUNK_SIZE = 24

        /** Angezeigt werden 28 dp — 144 px decken selbst 48-dp-Kacheln in 3× ab. */
        private const val ICON_MAX_PX = 144
    }
}
