package com.d_drostes_apps.aevum.data.pc

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kopplung mit einem PC-Tracker: Adresse + Zugangstoken, gespeichert in
 * SharedPreferences (gleiches Muster wie der Garmin-Login — kein Konto,
 * keine Cloud, alles bleibt auf dem Gerät).
 */
@Singleton
class PcTrackerPrefs @Inject constructor(
    @ApplicationContext context: Context
) {
    data class StoredConnection(
        val host: String,
        val port: Int,
        val deviceId: String,
        val deviceName: String,
        val token: String,
        val lastSyncAt: Long
    )

    private val prefs = context.getSharedPreferences("aevum_pc_tracker", Context.MODE_PRIVATE)

    fun load(): StoredConnection? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        if (host.isBlank() || token.isBlank()) {
            return null
        }
        return StoredConnection(
            host = host,
            port = prefs.getInt(KEY_PORT, 47825),
            deviceId = prefs.getString(KEY_DEVICE_ID, null).orEmpty(),
            deviceName = prefs.getString(KEY_DEVICE_NAME, null).orEmpty(),
            token = token,
            lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L)
        )
    }

    fun save(connection: StoredConnection) {
        prefs.edit()
            .putString(KEY_HOST, connection.host)
            .putInt(KEY_PORT, connection.port)
            .putString(KEY_DEVICE_ID, connection.deviceId)
            .putString(KEY_DEVICE_NAME, connection.deviceName)
            .putString(KEY_TOKEN, connection.token)
            .putLong(KEY_LAST_SYNC, connection.lastSyncAt)
            .apply()
    }

    /** Nur den Server-Zeitstempel des letzten Abrufs fortschreiben. */
    fun saveLastSync(atMs: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC, atMs).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * Automatischer Hintergrund-Sync (Default an): holt Aufzeichnungen still
     * ab, sobald der PC im gleichen WLAN ist. Fehler erscheinen nur beim
     * manuellen Sync — der Hintergrund-Lauf meldet nichts.
     */
    fun isAutoSyncEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_SYNC, true)

    fun setAutoSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
    }

    /**
     * M18.147: Zeitstempel des letzten stillen Sync-Versuchs (Erfolg wie
     * Fehlschlag). Drosselt die App-Öffnen-Kicks (PcSyncGate), damit häufiges
     * Öffnen der App nicht mehrfach pro Minute synchronisiert.
     */
    fun lastAttemptAt(): Long = prefs.getLong(KEY_LAST_ATTEMPT, 0L)

    fun setLastAttemptAt(atMs: Long) {
        prefs.edit().putLong(KEY_LAST_ATTEMPT, atMs).apply()
    }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_TOKEN = "token"
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_AUTO_SYNC = "auto_sync_enabled"
        const val KEY_LAST_ATTEMPT = "last_attempt_at"
    }
}
