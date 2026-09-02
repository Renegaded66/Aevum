package com.d_drostes_apps.aevum.data.manager

import android.content.Context
import android.net.Uri
import android.util.Log
import com.d_drostes_apps.aevum.R
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
            } ?: return@withContext ExportResult.Error(context.getString(R.string.export_error_open_target))

            ExportResult.Success(
                message = context.getString(R.string.export_success, tables.size, json.length / 1024)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Export fehlgeschlagen", e)
            ExportResult.Error(context.getString(R.string.export_error_failed, e.message ?: context.getString(R.string.data_unknown_error)))
        }
    }

    // ------------------------------------------------------------------
    // BACKUP (ZIP)
    // ------------------------------------------------------------------

    /**
     * Erstellt ein ZIP mit DB + WAL + SHM + ALLEN SharedPreferences/DataStore-
     * Dateien. WAL wird mitgenommen, damit auch noch nicht gecheckpointete
     * Transaktionen im Backup sind.
     *
     * M18.72-FIX: Seit Einführung der Backup-Funktion sind neue Datenquellen
     * dazugekommen (Lebensprofil-Geburtstag, Timeline-Ansicht, Garmin-Credentials,
     * App-Sprache, Insights-Period, DataStore). Diese liegen NICHT in der DB,
     * sondern in SharedPreferences/DataStore — ohne sie wäre ein Restore
     * unvollständig. Jetzt: alle `aevum_*`-Prefs + DataStore werden mitgesichert.
     */
    suspend fun createBackup(target: Uri): ExportResult = withContext(Dispatchers.IO) {
        try {
            // WAL vor dem Kopieren checkpointen, damit die DB-Datei konsistent ist
            checkpointWal()

            val files = mutableListOf<Pair<File, String>>()
            files += listOf(dbFile to "aevum_database", walFile to "aevum_database-wal", shmFile to "aevum_database-shm")
                .filter { it.first.exists() }
            // Preferences + DataStore (alle User-sichtbaren Daten außerhalb der DB)
            files += collectPreferenceFiles()

            context.contentResolver.openOutputStream(target)?.use { out ->
                ZipOutputStream(out).use { zip ->
                    for ((file, entryName) in files) {
                        zip.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: return@withContext ExportResult.Error(context.getString(R.string.export_error_open_target))

            val sizeKb = files.sumOf { it.first.length() } / 1024
            ExportResult.Success(
                message = context.getString(R.string.backup_success, files.size, sizeKb)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Backup fehlgeschlagen", e)
            ExportResult.Error(context.getString(R.string.backup_error_failed, e.message ?: context.getString(R.string.data_unknown_error)))
        }
    }

    /**
     * Stellt ein ZIP-Backup wieder her. Prüft:
     * 1. ZIP enthält eine gültige aevum_database-Datei
     * 2. Schema-Version der Backup-DB passt zur aktuellen App-Version
     * 3. SQLite-Integrität (PRAGMA quick_check)
     *
     * M18.72-FIX: Zusätzlich zur DB werden die mitgesicherten
     * SharedPreferences/DataStore-Dateien (aevum_* etc.) wiederhergestellt.
     */
    suspend fun restoreBackup(source: Uri): ExportResult = withContext(Dispatchers.IO) {
        try {
            val tempDir = File(context.cacheDir, "restore_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            // 1. ZIP entpacken (alle Dateien — DB UND Preferences)
            val restoredDb = File(tempDir, "aevum_database")
            // Paar: (entpackte Datei, relativer Pfad im App-Datenverzeichnis)
            val restoredPrefs = mutableListOf<Pair<File, String>>()
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
                            // WAL/SHM nicht wiederherstellen — Room baut sie neu auf.
                            // Preferences/DataStore-Dateien sammeln und später kopieren.
                            if (!entry.name.endsWith("-wal") && !entry.name.endsWith("-shm")) {
                                // WICHTIG: Einträge in Unterverzeichnissen (z. B.
                                // shared_prefs/aevum_language.xml) brauchen ein
                                // existierendes Zielverzeichnis — sonst wirft
                                // FileOutputStream FileNotFoundException
                                // ("Open failed: enoent ...") und der gesamte
                                // Restore bricht ab.
                                targetFile.parentFile?.mkdirs()
                                FileOutputStream(targetFile).use { zip.copyTo(it) }
                                restoredPrefs += targetFile to entry.name
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return@withContext ExportResult.Error(context.getString(R.string.backup_error_open))

            if (!foundDb) {
                tempDir.deleteRecursively()
                return@withContext ExportResult.Error(context.getString(R.string.backup_error_no_db))
            }

            // 2. Versions-Check
            val backupVersion = queryUserVersion(android.database.sqlite.SQLiteDatabase.openDatabase(restoredDb.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY))
            val currentVersion = CURRENT_SCHEMA_VERSION
            if (backupVersion != currentVersion) {
                tempDir.deleteRecursively()
                return@withContext ExportResult.Error(
                    context.getString(R.string.backup_error_version, backupVersion, currentVersion)
                )
            }

            // 3. Integritäts-Check
            val integrityOk = checkIntegrity(restoredDb)
            if (!integrityOk) {
                tempDir.deleteRecursively()
                return@withContext ExportResult.Error(context.getString(R.string.backup_error_corrupt))
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
            // DataStore: Kein close() in DataStore 1.1.1 — aber der Restore
            // erzwingt ohnehin einen App-Neustart (needsRestart=true), und
            // DataStore liest seine .preferences_pb beim nächsten Start
            // frisch von der Platte → die wiederhergestellte Datei greift.
            // Pending Writes im selben Prozess-Fenster sind nicht zu erwarten
            // (der User führt keinen parallelen Schreibzugriff aus).
            val oldBackup = File(dbFile.parentFile, "aevum_database.bak")
            if (dbFile.exists()) {
                oldBackup.delete()
                dbFile.copyTo(oldBackup)
            }
            walFile.delete()
            shmFile.delete()
            dbFile.delete()
            restoredDb.copyTo(dbFile)

            // 5. Preferences/DataStore wiederherstellen (M18.72)
            //    Zielpfad = applicationInfo.dataDir + relativer ZIP-Pfad,
            //    damit shared_prefs/*.xml wirklich unter
            //    <dataDir>/shared_prefs/ landen (Android liest Prefs nur
            //    dort) und DataStore-Dateien unter <dataDir>/files/.
            for ((prefFile, relativePath) in restoredPrefs) {
                try {
                    val dest = File(context.applicationInfo.dataDir, relativePath)
                    dest.parentFile?.mkdirs()
                    prefFile.copyTo(dest, overwrite = true)
                } catch (e: Exception) {
                    Log.w(TAG, "Pref-Restore fehlgeschlagen für $relativePath (nicht kritisch)", e)
                }
            }

            tempDir.deleteRecursively()
            ExportResult.Success(
                message = context.getString(R.string.backup_restore_success),
                needsRestart = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Restore fehlgeschlagen", e)
            ExportResult.Error(context.getString(R.string.backup_restore_error, e.message ?: context.getString(R.string.data_unknown_error)))
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

            // 3. SharedPreferences löschen (M18.72: ALLE bekannten Prefs,
            //    nicht nur drei — sonst bleiben Reste nach "Alle Daten löschen")
            val prefDir = File(context.applicationInfo.dataDir, "shared_prefs")
            prefDir.listFiles()?.forEach { it.delete() }
            // DataStore-Dateien löschen
            listOf(
                "aevum_preferences.preferences_pb",
                "aevum_preferences.preferences_pb.tmp",
                "aevum_preferences.preferences_pb.bak"
            ).forEach { name ->
                File(context.filesDir, name).delete()
            }
            File(context.filesDir, "datastore").deleteRecursively()

            // 4. Cache leeren
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }

            ExportResult.Success(
                message = context.getString(R.string.data_deleted_all),
                needsRestart = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Daten löschen fehlgeschlagen", e)
            ExportResult.Error(context.getString(R.string.data_delete_error, e.message ?: context.getString(R.string.data_unknown_error)))
        }
    }

    // ------------------------------------------------------------------
    // INTERN
    // ------------------------------------------------------------------

    /**
     * Sammelt alle SharedPreferences/DataStore-Dateien, die User-Daten
     * außerhalb der DB enthalten (M18.72):
     * - aevum_language (App-Sprache)
     * - aevum_insights (gewählte Periode)
     * - aevum_timeline (Zoom/Ansicht)
     * - aevum_lifeview (Geburtstag, erwartetes Alter)
     * - aevum_zone_state, aevum_screen_events, aevum_prefs, automation_prefs,
     *   life_profile_prefs, aevum_garmin, aevum_garmin_direct
     * - DataStore: aevum_preferences (inkl. .preferences_pb-Dateien)
     */
    private fun collectPreferenceFiles(): List<Pair<File, String>> {
        val dataDir = context.applicationInfo.dataDir
        val prefDir = File(dataDir, "shared_prefs")
        val files = mutableListOf<Pair<File, String>>()
        if (prefDir.exists()) {
            prefDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    files += file to "shared_prefs/${file.name}"
                }
            }
        }
        // DataStore-Dateien (im files-Verzeichnis)
        val filesDir = context.filesDir
        listOf("aevum_preferences.preferences_pb", "aevum_preferences.preferences_pb.tmp", "aevum_preferences.preferences_pb.bak")
            .forEach { name ->
                val f = File(filesDir, name)
                if (f.exists()) files += f to "files/$name"
            }
        // .bak von DataStore (ältere Versionen)
        File(filesDir, "datastore").listFiles()?.forEach { f ->
            if (f.isFile) files += f to "files/datastore/${f.name}"
        }
        return files
    }

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
         * M18.72-FIX: War fälschlich auf 22 eingefroren — seit Version 38
         * schlug JEDER Restore mit Versions-Mismatch fehl.
         * M18.94-FIX: 39 → 40 (MIGRATION_39_40). Der Kommentar war auf 39
         * stehen geblieben, während AppDatabase auf 40 migrierte — Restore
         * brach erneut mit "Bitte zuerst die App aktualisieren".
         */
        private const val CURRENT_SCHEMA_VERSION = 40
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
