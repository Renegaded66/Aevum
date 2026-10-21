package com.d_drostes_apps.aevum.data.manager

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * M27: Backup-Audit als Test.
 *
 * Das Backup ist ABSICHTLICH dateibasiert (DB + SharedPreferences/DataStore),
 * nicht tabellenbasiert. Dadurch landen neue Features automatisch mit im ZIP.
 * Dieser Test hält die drei Eigenschaften fest, die das tragen:
 *
 *  1. createBackup nimmt die DB-DATEI (nicht eine Liste alter Tabellen),
 *  2. ALLE SharedPreferences werden eingesammelt (nicht eine Auswahl),
 *  3. der Versionscheck nutzt die EINE Schema-Konstante und die
 *     Migrations-Policy — keine eingefrorene Zahl.
 */
class BackupCoverageWiringTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/d_drostes_apps/aevum/$relative"),
            File("app/src/main/java/com/d_drostes_apps/aevum/$relative")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Quelldatei fehlt: $relative (CWD=${File(".").absolutePath})")
        return file.readText()
    }

    @Test
    fun `Backup-nimmt-die-komplette-Datenbankdatei-mit`() {
        val manager = source("data/manager/DataManager.kt")
        assertThat(manager).contains("files += listOf(dbFile to \"aevum_database\"")
        assertThat(manager).contains("walFile to \"aevum_database-wal\"")
        assertThat(manager).contains("collectPreferenceFiles()")
    }

    @Test
    fun `alle-SharedPreferences-werden-eingesammelt-nicht-eine-Auswahl`() {
        val manager = source("data/manager/DataManager.kt")
        // Der Verzeichnis-Scan ist der Future-Proof-Teil: neue Prefs-Dateien
        // (z. B. aevum_addiction) landen ohne Codeänderung im Backup.
        assertThat(manager).contains("prefDir.listFiles()?.forEach")
        assertThat(manager).contains("\"shared_prefs/\${file.name}\"")
        assertThat(manager).contains("File(filesDir, \"datastore\").listFiles()")
    }

    @Test
    fun `Restore-nutzt-die-eine-Schema-Konstante-und-die-Migrations-Policy`() {
        val manager = source("data/manager/DataManager.kt")
        assertThat(manager).contains("AppDatabase.SCHEMA_VERSION")
        assertThat(manager).contains("BackupVersionPolicy.decide")
        assertThat(manager).contains("BackupVersionPolicy.Decision.NEWER")
        // Die alte, eingefrorene 40 darf nicht zurückkommen.
        assertThat(manager).doesNotContain("private const val CURRENT_SCHEMA_VERSION = 40")
    }

    @Test
    fun `Sucht-Detektor-Tabellen-liegen-in-der-Datenbank-und-damit-im-Backup`() {
        val database = source("data/db/AppDatabase.kt")
        assertThat(database).contains("AddictionWatchApp::class")
        assertThat(database).contains("AddictionSession::class")
    }
}
