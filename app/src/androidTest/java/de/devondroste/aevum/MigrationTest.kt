package de.devondroste.aevum

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.devondroste.aevum.data.db.AppDatabase
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "aevum-migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate2To3_matchesRoomSchema_afterM55Install() {
        helper.createDatabase(dbName, 2).apply {
            execSQL("CREATE TABLE IF NOT EXISTS category (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, color TEXT NOT NULL, icon TEXT NOT NULL, is_system INTEGER NOT NULL, sort_order INTEGER NOT NULL)")
            execSQL("CREATE TABLE IF NOT EXISTS tag (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, color TEXT)")
            execSQL("CREATE TABLE IF NOT EXISTS activity_type (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, default_category_id TEXT, is_system INTEGER NOT NULL DEFAULT 1, properties_json TEXT, FOREIGN KEY(default_category_id) REFERENCES category(id) ON DELETE SET NULL)")
            execSQL("CREATE TABLE IF NOT EXISTS place_geofence (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, category_id TEXT, latitude REAL NOT NULL, longitude REAL NOT NULL, radius_meters REAL NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, FOREIGN KEY(category_id) REFERENCES category(id) ON DELETE SET NULL)")
            execSQL("CREATE INDEX IF NOT EXISTS index_place_geofence_enabled ON place_geofence(enabled)")
            execSQL("CREATE INDEX IF NOT EXISTS index_place_geofence_category_id ON place_geofence(category_id)")
            execSQL("CREATE TABLE IF NOT EXISTS detection_event (id TEXT PRIMARY KEY NOT NULL, raw_event_id TEXT, source_id TEXT NOT NULL, kind TEXT NOT NULL, start_at INTEGER NOT NULL, end_at INTEGER, confidence REAL NOT NULL DEFAULT 1.0, place_id TEXT, metadata_json TEXT, created_at INTEGER NOT NULL)")
            execSQL("CREATE TABLE IF NOT EXISTS data_source (id TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, permission_state TEXT NOT NULL DEFAULT 'UNKNOWN', last_sync_at INTEGER, config_json TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            close()
        }

        helper.runMigrationsAndValidate(dbName, 3, true, AppDatabase.MIGRATION_2_3).close()
    }

    @Test
    fun migratedPlaceGeofence_hasActivityTypeForeignKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("$dbName-fk")
        dbFile.delete()
        context.getDatabasePath("$dbName-fk-journal").delete()

        val sqlite = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL("CREATE TABLE category (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, color TEXT NOT NULL, icon TEXT NOT NULL, is_system INTEGER NOT NULL, sort_order INTEGER NOT NULL)")
        sqlite.execSQL("CREATE TABLE activity_type (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, default_category_id TEXT, is_system INTEGER NOT NULL DEFAULT 1, properties_json TEXT)")
        sqlite.execSQL("CREATE TABLE place_geofence (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, category_id TEXT, latitude REAL NOT NULL, longitude REAL NOT NULL, radius_meters REAL NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, FOREIGN KEY(category_id) REFERENCES category(id) ON DELETE SET NULL)")
        sqlite.version = 2
        sqlite.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, "$dbName-fk")
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .build()
        migrated.openHelper.writableDatabase.query("PRAGMA foreign_key_list(place_geofence)").use { cursor ->
            val referencedTables = mutableSetOf<String>()
            while (cursor.moveToNext()) referencedTables += cursor.getString(cursor.getColumnIndexOrThrow("table"))
            assertTrue("place_geofence must reference activity_type after 2→3 migration", "activity_type" in referencedTables)
            assertTrue("place_geofence must preserve category reference", "category" in referencedTables)
        }
        migrated.close()
    }
}
