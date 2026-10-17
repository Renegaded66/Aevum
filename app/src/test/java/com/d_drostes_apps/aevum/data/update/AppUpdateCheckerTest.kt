package com.d_drostes_apps.aevum.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * App 1.0.34: Tests der reinen Auswahl-Logik des In-App-Updaters — arbeitet
 * nur auf dem JSON der öffentlichen GitHub-Releases, kein Netz, keine Geräte.
 */
class AppUpdateCheckerTest {

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<Triple<String, String, Long>> = emptyList(),
        body: String = ""
    ): String {
        val assetsJson = assets.joinToString(",") { (name, url, size) ->
            """{"name":"$name","browser_download_url":"$url","size":$size}"""
        }
        val escapedBody = body.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            {"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,
             "html_url":"https://github.com/Renegaded66/Aevum/releases/tag/$tag",
             "body":"$escapedBody",
             "assets":[$assetsJson]}
        """.trimIndent()
    }

    private fun releases(vararg entries: String) = entries.joinToString(",", "[", "]")

    // ---- parseVersion ----

    @Test
    fun parseVersionMitPraefixUndSuffix() {
        assertThat(AppUpdateChecker.parseVersion("1.0.34")).containsExactly(1, 0, 34).inOrder()
        assertThat(AppUpdateChecker.parseVersion("v1.0.34")).containsExactly(1, 0, 34).inOrder()
        assertThat(AppUpdateChecker.parseVersion("1.0.34-debug")).containsExactly(1, 0, 34).inOrder()
    }

    @Test
    fun parseVersionLehntUnbrauchbareTexteAb() {
        assertThat(AppUpdateChecker.parseVersion("")).isNull()
        assertThat(AppUpdateChecker.parseVersion("abc")).isNull()
        assertThat(AppUpdateChecker.parseVersion("1.x")).isNull()
    }

    // ---- compareVersions ----

    @Test
    fun compareVersionsZahlenweise() {
        assertThat(
            AppUpdateChecker.compareVersions(
                AppUpdateChecker.parseVersion("1.0.34")!!,
                AppUpdateChecker.parseVersion("1.0.33")!!
            )
        ).isGreaterThan(0)
        assertThat(
            AppUpdateChecker.compareVersions(
                AppUpdateChecker.parseVersion("1.1")!!,
                AppUpdateChecker.parseVersion("1.1.0")!!
            )
        ).isEqualTo(0)
        assertThat(
            AppUpdateChecker.compareVersions(
                AppUpdateChecker.parseVersion("1.9.9")!!,
                AppUpdateChecker.parseVersion("1.10.0")!!
            )
        ).isLessThan(0)
    }

    // ---- selectNewest ----

    @Test
    fun findetNeuereVersionUndWaehltDebugApkFuerDebugBuild() {
        val json = releases(
            release(
                tag = "app-1.0.33",
                assets = listOf(
                    Triple("Aevum-1.0.33-Debug.apk", "https://example/old-debug.apk", 100L)
                )
            ),
            release(
                tag = "app-1.0.34",
                assets = listOf(
                    Triple("Aevum-1.0.34.apk", "https://example/new.apk", 200L),
                    Triple("Aevum-1.0.34-Debug.apk", "https://example/new-debug.apk", 300L)
                ),
                body = "Neu: Live-Kanal"
            )
        )
        val result = AppUpdateChecker.selectNewest(json, "1.0.33", isDebugBuild = true)
        assertThat(result).isNotNull()
        assertThat(result!!.versionName).isEqualTo("1.0.34")
        assertThat(result.apkName).isEqualTo("Aevum-1.0.34-Debug.apk")
        assertThat(result.apkUrl).isEqualTo("https://example/new-debug.apk")
        assertThat(result.apkSizeBytes).isEqualTo(300L)
        assertThat(result.notes).isEqualTo("Neu: Live-Kanal")
    }

    @Test
    fun regulärerBuildBekommtDieStoreApkNichtDieDebugApk() {
        val json = releases(
            release(
                tag = "app-1.0.34",
                assets = listOf(
                    Triple("Aevum-1.0.34.apk", "https://example/new.apk", 200L),
                    Triple("Aevum-1.0.34-Debug.apk", "https://example/new-debug.apk", 300L)
                )
            )
        )
        val result = AppUpdateChecker.selectNewest(json, "1.0.33", isDebugBuild = false)
        assertThat(result!!.apkName).isEqualTo("Aevum-1.0.34.apk")
    }

    @Test
    fun gleichAlteVersionIstKeinUpdate() {
        val json = releases(
            release(
                tag = "app-1.0.33",
                assets = listOf(Triple("Aevum-1.0.33-Debug.apk", "https://example/a.apk", 100L))
            )
        )
        assertThat(AppUpdateChecker.selectNewest(json, "1.0.33", isDebugBuild = true)).isNull()
    }

    @Test
    fun ueberspringtDraftsPrereleasesUndAndereProdukte() {
        val json = releases(
            // Draft und Prerelease zählen nie.
            release("app-1.0.99", draft = true, assets = listOf(Triple("Aevum-1.0.99-Debug.apk", "https://example/d.apk", 1L))),
            release("app-1.0.98", prerelease = true, assets = listOf(Triple("Aevum-1.0.98-Debug.apk", "https://example/p.apk", 1L))),
            // Der PC-Tracker teilt sich das Repository, ist aber ein anderes Produkt.
            release("pc-tracker-1.4.0", assets = listOf(Triple("AevumPcTracker-1.4.0-win-x64.zip", "https://example/pc.zip", 1L))),
            // Ohne APK-Asset kein Update.
            release("app-1.0.97")
        )
        assertThat(AppUpdateChecker.selectNewest(json, "1.0.33", isDebugBuild = true)).isNull()
    }

    @Test
    fun waehltDieNeuesteVonMehreren() {
        val json = releases(
            release("app-1.0.34", assets = listOf(Triple("Aevum-1.0.34-Debug.apk", "https://example/34.apk", 1L))),
            release("app-1.0.36", assets = listOf(Triple("Aevum-1.0.36-Debug.apk", "https://example/36.apk", 1L))),
            release("app-1.0.35", assets = listOf(Triple("Aevum-1.0.35-Debug.apk", "https://example/35.apk", 1L)))
        )
        val result = AppUpdateChecker.selectNewest(json, "1.0.33", isDebugBuild = true)
        assertThat(result!!.versionName).isEqualTo("1.0.36")
    }

    @Test
    fun passendesAssetFehltDannKeinUpdate() {
        // Nur ein Debug-APK online, aber ein regulärer Build fragt an.
        val json = releases(
            release("app-1.0.34", assets = listOf(Triple("Aevum-1.0.34-Debug.apk", "https://example/d.apk", 1L)))
        )
        assertThat(AppUpdateChecker.selectNewest(json, "1.0.33", isDebugBuild = false)).isNull()
    }

    @Test
    fun kaputtesJsonErgibtKeinUpdate() {
        assertThat(AppUpdateChecker.selectNewest("kein json", "1.0.33", isDebugBuild = true)).isNull()
        assertThat(AppUpdateChecker.selectNewest("{}", "1.0.33", isDebugBuild = true)).isNull()
    }
}
