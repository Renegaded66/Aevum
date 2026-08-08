package com.d_drostes_apps.aevum.data.manager

import android.content.Context
import android.net.Uri
import android.util.Log
import com.d_drostes_apps.aevum.data.db.AppDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.55: Zentrale Daten-Verwaltung für Datenschutz, Export und Backup.
 *
 * Alle Operationen arbeiten auf der SQLite-Datei direkt (nicht über Room-Entities),
 * damit JEDE Tabelle — auch zukünftige — automatisch erfasst wird.
 *
 * - [exportJson]: Vollständiger Daten-Export als JSON (alle Tabellen, alle Zeilen).
 * - [createBackup]: Konsistentes ZIP-Backup (DB + WAL + SHM) für schnellen Restore.
 * - [restoreBackup]: Restore mit Versions- und Integritätsprüfung.
 * - [deleteAllData]: Komplettes Löschen aller lokalen Daten.
 */
@Singleton
class DataManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dbFile: File
        get() = context.getDatabasePath("aevum_database")

    private val walFile: File
        get() = File(dbFile.parentFile, "aevum_database-wal")

    private val shmFile: File
        get() = File(dbFile.parentFile, "aevum_database-shm")

    // ------------------------------------------------------------------
    // EXPORT (JSON)
    // ------------------------------------------------------------------

    /**
     * Exportiert ALLE Tabellen als JSON. Format:
     * {
     *   "app": "Aevum",
     *   "exportedAt": "2026-08-08T14:30:00Z",
     *   "schemaVersion": 22,
     *   "tables": {
     *     "activity_session": [ { "id": "...", ... }, ... ],
     *     ...
     *   }
     * }
     */
    suspend fun exportJson(target: Uri): ExportResult = withContext(Dispatchers.IO) {
        try {
            val db = openReadableDb()
            val tables = queryTableNames(db)
            val root = JSONObject()
            root.put("app", "Aevum")
            root.put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
            root.put("schemaVersion", queryUserVersion(db))
            val tablesJson = JSONObject()
            for (table in tables) {
                val rows = JSONArray()
                db.rawQuery("SELECT * FROM \"$table\"", null).use { cursor ->
                    val columnNames = cursor.columnNames
                    while (cursor.moveToNext()) {
                        val row = JSONObject()
                        for (i in columnNames.indices) {
                            row.put(columnNames[i], readValue(cursor, i))
                        }
                        rows.put(row)
                    }
                }
                tablesJson.put(table, rows)
            }
            root.put("tables", tablesJson)
            db.close()

            val json = root.toString(2)
            context.contentResolver.openOutputStream(target)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: return@withContext ExportResult.Error("Zieldatei konnte nicht geöffnet werden")

            ExportResult.Success(
                message = "Export erstellt: ${tables.size} Tabellen, ${json.length / 1024} KB"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Export fehlgeschlagen", e)
            ExportResult.Error("Export fehlgeschlagen: ${e.message ?: "unbekannter Fehler"}")
        }
    }

    // ------------------------------------------------------------------
    // BACKUP (ZIP)
    // ------------------------------------------------------------------

    /**
     * Erstellt ein ZIP mit DB + WAL + SHM. WAL wird mitgenommen, damit
     * auch noch nicht gecheckpointete Transaktionen im Backup sind.
     */
    suspend fun createBackup(target: Uri): ExportResult = withContext(Dispatchers.IO) {
        try {
            // WAL vor dem Kopieren checkpointen, damit die DB-Datei konsistent ist
            checkpointWal()

            val files = listOf(dbFile to "aevum_database", walFile to "aevum_database-wal", shmFile to "aevum_database-shm")
                .filter { it.first.exists() }

            context.contentResolver.openOutputStream(target)?.use { out ->
                ZipOutputStream(out).use { zip ->
                    for ((file, entryName) in files) {
                        zip.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: return@withContext ExportResult.Error("Zieldatei konnte nicht geöffnet werden")

            val sizeKb = files.sumOf { it.first.length() } / 1024
            ExportResult.Success(
                message = "Backup erstellt: ${files.size} Dateien, $sizeKb KB"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Backup fehlgeschlagen", e)
            ExportResult.Error("Backup fehlgeschlagen: ${e.message ?: "unbekannter Fehler"}")
        }
    }

    /**
     * Stellt ein ZIP-Backup wieder her. Prüft:
     * 1. ZIP enthält eine gültige aevum_database-Datei
     * 2. Schema-Version der Backup-DB passt zur aktuellen App-Version
     * 3. SQLite-Integrität (PRAGMA quick_check)
     */
    suspend fun restoreBackup(source: Uri): ExportResult = withContext(Dispatchers.IO) {
        try {
            val tempDir = File(context.cacheDir, "restore_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            // 1. ZIP entpacken
            val restoredDb = File(tempDir, "aevum_database")
            var foundDb = false
            context.contentResolver.openInputStream(source)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val targetFile = File(tempDir, entry.name)
                        if (entry.name == "aevum_database") {
                            FileOutputStream(targetFile).use { zip.copyTo(it) }
                            foundDb = true
                        } else {
                            // WAL/SHM nicht wiederherstellen — Room baut sie neu auf
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return@withContext ExportResult.Error("Backup-Datei konnte nicht geöffnet werden")

            if (!foundDb) {
                tempDir.deleteRecursively()
                return@withContext ExportResult.Error("Keine gültige Datenbank im Backup gefunden")
            }

            // 2. Versions-Check
            val backupVersion = queryUserVersion(android.database.sqlite.SQLiteDatabase.openDatabase(restoredDb.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY))
            val currentVersion = CURRENT_SCHEMA_VERSION
            if (backupVersion != currentVersion) {
                tempDir.deleteRecursively()
                return@withContext ExportResult.Error(
                    "Backup-Version ($backupVersion) passt nicht zur App-Version ($currentVersion). " +
                        "Bitte zuerst die App aktualisieren."
                )
            }

            // 3. Integritäts-Check
            val integrityOk = checkIntegrity(restoredDb)
            if (!integrityOk) {
                tempDir.deleteRecursively()
                return@withContext ExportResult.Error("Backup-Datei ist beschädigt (Integritätsprüfung fehlgeschlagen)")
            }

            // 4. Aktuelle DB ersetzen (mit Sicherung der alten).
            //    WICHTIG (M18.56-Fix): Room zuerst schließen, sonst
            //    korrumpiert das Ersetzen der offenen Datei die DB.
            try {
                val deps = EntryPointAccessors.fromApplication(context, DataManagerDeps::class.java)
                deps.database().close()
            } catch (e: Exception) {
                Log.w(TAG, "Room-Close vor Restore fehlgeschlagen (nicht kritisch)", e)
            }
            val oldBackup = File(dbFile.parentFile, "aevum_database.bak")
            if (dbFile.exists()) {
                oldBackup.delete()
                dbFile.copyTo(oldBackup)
            }
            walFile.delete()
            shmFile.delete()
            dbFile.delete()
            restoredDb.copyTo(dbFile)

            tempDir.deleteRecursively()
            ExportResult.Success(
                message = "Backup wiederhergestellt. Die App startet neu.",
                needsRestart = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Restore fehlgeschlagen", e)
            ExportResult.Error("Wiederherstellung fehlgeschlagen: ${e.message ?: "unbekannter Fehler"}")
        }
    }

    // ------------------------------------------------------------------
    // DATENSCHUTZ
    // ------------------------------------------------------------------

    /**
     * Löscht ALLE lokalen Daten (DB + SharedPreferences + Cache).
     * Die App muss danach neu gestartet werden.
     *
     * WICHTIG (M18.56-Fix): Room hält die DB-Datei offen. Ein direktes
     * Löschen der Dateien während Room läuft korrumpiert die Datenbank
     * (WAL-Desync) — danach schlagen ALLE DB-Operationen stillschweigend
     * fehl. Deshalb: erst Room sauber schließen (EntryPoint), dann löschen.
     */
    suspend fun deleteAllData(): ExportResult = withContext(Dispatchers.IO) {
        try {
            // 1. Room-Datenbank sauber schließen (WAL-Checkpoint + Close)
            try {
                val deps = EntryPointAccessors.fromApplication(context, DataManagerDeps::class.java)
                deps.database().close()
            } catch (e: Exception) {
                Log.w(TAG, "Room-Close vor Löschen fehlgeschlagen (nicht kritisch)", e)
            }

            // 2. DB-Dateien löschen
            dbFile.delete()
            walFile.delete()
            shmFile.delete()
            File(dbFile.parentFile, "aevum_database.bak").delete()

            // 3. SharedPreferences löschen
            context.getSharedPreferences("aevum_prefs", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("automation_prefs", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("life_profile_prefs", Context.MODE_PRIVATE).edit().clear().commit()

            // 4. Cache leeren
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }

            ExportResult.Success(
                message = "Alle Daten wurden gelöscht. Die App startet neu.",
                needsRestart = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Daten löschen fehlgeschlagen", e)
            ExportResult.Error("Löschen fehlgeschlagen: ${e.message ?: "unbekannter Fehler"}")
        }
    }

    // ------------------------------------------------------------------
    // INTERN
    // ------------------------------------------------------------------

    private fun openReadableDb(): android.database.sqlite.SQLiteDatabase =
        android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )

    private fun queryTableNames(db: android.database.sqlite.SQLiteDatabase): List<String> {
        val tables = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' ORDER BY name",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) tables.add(cursor.getString(0))
        }
        return tables
    }

    private fun queryUserVersion(db: android.database.sqlite.SQLiteDatabase): Int =
        db.version

    private fun readValue(cursor: android.database.Cursor, index: Int): Any {
        return when (cursor.getType(index)) {
            android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
            android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
            android.database.Cursor.FIELD_TYPE_BLOB -> {
                val bytes = cursor.getBlob(index)
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
            else -> cursor.getString(index)
        }
    }

    private fun checkpointWal() {
        try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )
            db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).close()
            db.close()
        } catch (e: Exception) {
            Log.w(TAG, "WAL-Checkpoint fehlgeschlagen (nicht kritisch)", e)
        }
    }

    private fun checkIntegrity(dbFile: File): Boolean {
        return try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            val result = db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else "error"
            }
            db.close()
            result == "ok"
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "DataManager"

        /**
         * Aktuelle Room-Schema-Version (muss mit `version = N` in AppDatabase.kt
         * übereinstimmen). Bei jeder DB-Migration hier mitziehen.
         */
        private const val CURRENT_SCHEMA_VERSION = 22
    }
}

/**
 * M18.56: Hilt-EntryPoint, damit DataManager die Room-Datenbank vor
 * Löschen/Restore sauber schließen kann (sonst WAL-Desync → alle
 * DB-Operationen schlagen stillschweigend fehl).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DataManagerDeps {
    fun database(): AppDatabase
}

sealed class ExportResult {
    data class Success(val message: String, val needsRestart: Boolean = false) : ExportResult()
    data class Error(val message: String) : ExportResult()
}
