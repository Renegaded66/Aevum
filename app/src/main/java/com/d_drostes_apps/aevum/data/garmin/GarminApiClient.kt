package com.d_drostes_apps.aevum.data.garmin

import android.content.Context
import com.d_drostes_apps.aevum.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Garmin-Fassade: Authentifizierung + Datenabruf DIREKT bei Garmin
 * (DirectGarminClient — seit M18.66-FIX11, kein Bridge-Server mehr).
 *
 * Jeder Nutzer authentifiziert sich mit SEINEN Garmin-Credentials
 * (SSO-Login in DirectGarminClient; das Passwort wird NIE auf dem
 * Gerät gespeichert, die DI-Tokens liegen lokal in SharedPreferences).
 *
 * M18.87: Der frühere Bridge-Pfad (Cloudflare-Quick-Tunnel-URL,
 * X-Aevum-Key-Header, Bridge-User-UUID) wurde für die
 * Play-Store-Veröffentlichung entfernt — er war seit M18.66-FIX11
 * toter Code (alle öffentlichen Methoden gehen direkt an Garmin).
 */
@Singleton
class GarminApiClient @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val directClient: DirectGarminClient
) {
    private val prefs by lazy {
        context.getSharedPreferences("aevum_garmin", Context.MODE_PRIVATE)
    }

    /** M18.58: Letzter erfolgreicher Sync (ms) — für die Status-Karte. */
    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    /**
     * M18.66-FIX11: Direkter Garmin-Login — kein Bridge-Server.
     * App spricht direkt mit sso.garmin.com → diauth.garmin.com.
     * @return null bei Erfolg, sonst Fehlermeldung (deutsch, anzeigbar).
     */
    suspend fun connect(email: String, password: String): String? = withContext(Dispatchers.IO) {
        when (val result = directClient.login(email, password)) {
            is DirectGarminClient.LoginResult.Success -> null
            is DirectGarminClient.LoginResult.NeedsMfa -> context.getString(R.string.garmin_error_mfa)
            is DirectGarminClient.LoginResult.Error -> result.message
        }
    }

    /** M18.66-FIX11: Direkter Disconnect — löscht lokale Token. */
    suspend fun disconnect(): Boolean = withContext(Dispatchers.IO) {
        directClient.disconnect()
        true
    }

    suspend fun getStatus(): GarminStatus = withContext(Dispatchers.IO) {
        directClient.getStatus()
    }

    suspend fun getToday(dateIso: String): GarminDayData? = withContext(Dispatchers.IO) {
        directClient.getToday(dateIso)
    }

    /**
     * M18.65: [fresh]=true umgeht den Server-Cache (fragt Garmin direkt).
     * Der manuelle Sync nutzt das, damit die letzte Nacht nach dem
     * Aufwachen sofort mit den finalen Garmin-Daten aktualisiert wird
     * (Garmin korrigiert Schlafzeiten nachträglich; der Server-Cache
     * für "heute" ist nur 10 Min frisch).
     */
    suspend fun getSleep(dateIso: String, fresh: Boolean = false): GarminSleepData? = withContext(Dispatchers.IO) {
        directClient.getSleep(dateIso)
    }

    suspend fun getActivities(limit: Int = 20): List<GarminRemoteActivity> = withContext(Dispatchers.IO) {
        directClient.getActivities(limit)
    }

    companion object {
        private const val KEY_LAST_SYNC = "last_sync_at"
    }
}

data class GarminStatus(
    val connected: Boolean,
    val error: String? = null
)

data class GarminDayData(
    val date: String,
    val steps: Int,
    val distanceMeters: Double,
    val activeCalories: Int,
    val totalCalories: Int
)

data class GarminSleepData(
    val date: String,
    val startGmtMs: Long,
    val endGmtMs: Long,
    val sleepTimeSeconds: Long,
    val deepSeconds: Long,
    val remSeconds: Long
)

data class GarminRemoteActivity(
    val activityId: String,
    val name: String,
    val type: String,
    val startGmt: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val calories: Int
)
