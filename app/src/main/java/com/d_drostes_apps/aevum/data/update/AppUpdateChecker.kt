package com.d_drostes_apps.aevum.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Ein in den öffentlichen GitHub-Releases gefundenes App-Update. */
data class AppUpdate(
    val versionName: String,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSizeBytes: Long,
    val htmlUrl: String
)

/**
 * App 1.0.34: Sucht in den ÖFFENTLICHEN GitHub-Releases nach einer neueren
 * Aevum-Version. Devon: „Außerdem will ich in den einstellungen einen button
 * um nach updates zu suchen im github repo. Und natürlich auch herunterladen
 * und installieren. […] Ohne irgendwelche tokens, weil das alles veröffentlicht
 * wird." — bewusst OHNE jeden Token/jedes Konto: nur der anonyme, öffentliche
 * API-Endpunkt. Die Rate reicht für einen Check auf Knopfdruck locker.
 *
 * Jeder Fehler (offline, Rate-Limit, API-Änderung) endet ehrlich in [Result.Failed]
 * — „konnte nicht prüfen" ist etwas anderes als „kein Update".
 */
@Singleton
class AppUpdateChecker @Inject constructor() {

    sealed interface Result {
        data class Available(val update: AppUpdate) : Result

        /** Keine neuere Version gefunden. */
        data object UpToDate : Result

        /** Prüfung nicht möglich (offline/Rate-Limit/API-Änderung). */
        data object Failed : Result
    }

    suspend fun check(currentVersionName: String, isDebugBuild: Boolean): Result =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    // GitHub verlangt einen User-Agent; kein Token, kein Konto.
                    setRequestProperty("User-Agent", "AevumApp")
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    return@withContext Result.Failed
                }
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                val update = selectNewest(body, currentVersionName, isDebugBuild)
                if (update != null) Result.Available(update) else Result.UpToDate
            } catch (_: Exception) {
                Result.Failed
            } finally {
                connection?.disconnect()
            }
        }

    companion object {
        const val RELEASES_URL =
            "https://api.github.com/repos/Renegaded66/Aevum/releases?per_page=30"

        /**
         * App-Releases tragen dieses Tag-Präfix — der PC-Tracker nutzt
         * „pc-tracker-", beide Produkte teilen sich ein Repository.
         */
        const val TAG_PREFIX = "app-"

        /**
         * Reine Auswahl-Logik (JVM-testbar): neueste stabile App-Release mit
         * passendem APK-Asset. Debug-/Test-Builds laden die Debug-APK,
         * reguläre Builds die Store-APK — die Play-Store-Fassung aktualisiert
         * sich ohnehin über den Store.
         */
        fun selectNewest(
            releasesJson: String,
            currentVersionName: String,
            isDebugBuild: Boolean
        ): AppUpdate? {
            val releases = try {
                JSONArray(releasesJson)
            } catch (_: Exception) {
                return null // unerwartete Antwort (kein Array) → „kein Update"
            }
            val current = parseVersion(currentVersionName) ?: return null
            var best: AppUpdate? = null
            var bestVersion: List<Int>? = null
            for (i in 0 until releases.length()) {
                val release = releases.optJSONObject(i) ?: continue
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                val tag = release.optString("tag_name")
                if (!tag.startsWith(TAG_PREFIX, ignoreCase = true)) continue
                val versionText = tag.substring(TAG_PREFIX.length)
                val version = parseVersion(versionText) ?: continue
                if (compareVersions(version, current) <= 0) continue
                val asset = findAsset(release.optJSONArray("assets"), isDebugBuild) ?: continue
                if (bestVersion == null || compareVersions(version, bestVersion) > 0) {
                    bestVersion = version
                    best = AppUpdate(
                        versionName = versionText,
                        tag = tag,
                        notes = release.optString("body"),
                        apkUrl = asset.url,
                        apkName = asset.name,
                        apkSizeBytes = asset.size,
                        htmlUrl = release.optString("html_url")
                    )
                }
            }
            return best
        }

        private data class Asset(val url: String, val name: String, val size: Long)

        private fun findAsset(assets: JSONArray?, isDebugBuild: Boolean): Asset? {
            if (assets == null) return null
            for (i in 0 until assets.length()) {
                val entry = assets.optJSONObject(i) ?: continue
                val name = entry.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val isDebugAsset = name.contains("debug", ignoreCase = true)
                if (isDebugAsset != isDebugBuild) continue
                val url = entry.optString("browser_download_url")
                if (url.isBlank()) continue
                return Asset(url, name, entry.optLong("size"))
            }
            return null
        }

        /** „1.0.34-debug" → [1, 0, 34]; null bei unbrauchbarem Text. */
        fun parseVersion(raw: String): List<Int>? {
            val cleaned = raw.trim().removePrefix("v").substringBefore("-")
            if (cleaned.isBlank()) return null
            val parts = cleaned.split(".")
            val numbers = ArrayList<Int>(parts.size)
            for (part in parts) {
                val value = part.toIntOrNull() ?: return null
                numbers.add(value)
            }
            return numbers
        }

        /** Zahlenweiser Vergleich fehlender Stellen als 0 (1.1 == 1.1.0). */
        fun compareVersions(a: List<Int>, b: List<Int>): Int {
            val size = maxOf(a.size, b.size)
            for (i in 0 until size) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x - y
            }
            return 0
        }
    }
}
