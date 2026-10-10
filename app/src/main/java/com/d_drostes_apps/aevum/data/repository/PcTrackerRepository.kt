package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.DataSourceDao
import com.d_drostes_apps.aevum.data.model.DataSource
import com.d_drostes_apps.aevum.data.pc.DiscoveredPc
import com.d_drostes_apps.aevum.data.pc.PcDiscovery
import com.d_drostes_apps.aevum.data.pc.PcSessionImporter
import com.d_drostes_apps.aevum.data.pc.PcTrackerClient
import com.d_drostes_apps.aevum.data.pc.PcTrackerPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kopplung + Synchronisation mit einem Windows-PC im Heimnetz.
 * Alles läuft direkt zwischen Handy und PC — kein Server, keine Cloud.
 */
@Singleton
class PcTrackerRepository @Inject constructor(
    private val client: PcTrackerClient,
    private val discovery: PcDiscovery,
    private val prefs: PcTrackerPrefs,
    private val importer: PcSessionImporter,
    private val dataSourceDao: DataSourceDao
) {

    sealed class PairOutcome {
        data class Success(val deviceName: String) : PairOutcome()
        object InvalidCode : PairOutcome()
        data class RateLimited(val retryAfterSeconds: Int) : PairOutcome()
        object NotReachable : PairOutcome()
    }

    sealed class SyncOutcome {
        data class Success(
            val inserted: Int,
            val updated: Int,
            val skippedUserEdited: Int,
            val createdTypes: List<String>
        ) : SyncOutcome()
        object NotConnected : SyncOutcome()

        /** Token abgelaufen/ungültig — am PC wurde der Zugang zurückgesetzt. */
        object Unauthorized : SyncOutcome()
        object NotReachable : SyncOutcome()
    }

    private val _connection = MutableStateFlow(prefs.load())
    val connection: StateFlow<PcTrackerPrefs.StoredConnection?> = _connection

    suspend fun discover(): List<DiscoveredPc> = discovery.discover()

    suspend fun pair(host: String, port: Int, code: String, clientName: String): PairOutcome {
        val cleaned = normalizeCode(code)
        if (cleaned.isEmpty()) {
            return PairOutcome.InvalidCode
        }
        return try {
            val token = client.pair(host, port, cleaned, clientName)
            val connection = PcTrackerPrefs.StoredConnection(
                host = host,
                port = port,
                deviceId = token.deviceId,
                deviceName = token.name.ifBlank { host },
                token = token.token,
                lastSyncAt = 0L
            )
            prefs.save(connection)
            _connection.value = connection
            dataSourceDao.insert(
                DataSource(
                    id = dataSourceId(token.deviceId),
                    type = DATA_SOURCE_TYPE,
                    name = connection.deviceName,
                    configJson = JSONObject()
                        .put("host", host)
                        .put("port", port)
                        .put("deviceId", token.deviceId)
                        .toString()
                )
            )
            PairOutcome.Success(connection.deviceName)
        } catch (e: PcTrackerClient.PcHttpException) {
            when {
                e.statusCode == 429 -> PairOutcome.RateLimited(e.retryAfterSeconds ?: 120)
                else -> PairOutcome.InvalidCode
            }
        } catch (_: IOException) {
            PairOutcome.NotReachable
        }
    }

    suspend fun syncNow(): SyncOutcome {
        val connection = _connection.value ?: return SyncOutcome.NotConnected
        return try {
            // Kleiner Überlapp beim since-Wert: Grenzfälle an der Sync-Kante
            // sind dank externalId ohnehin idempotent.
            val since = (connection.lastSyncAt - OVERLAP_MS).coerceAtLeast(0L)
            val response = client.fetchSessions(
                host = connection.host,
                port = connection.port,
                token = connection.token,
                sinceMs = since
            )
            val result = importer.import(
                deviceName = response.deviceName.ifBlank { connection.deviceName },
                sessions = response.sessions
            )
            prefs.saveLastSync(response.serverTime)
            _connection.value = connection.copy(lastSyncAt = response.serverTime)
            dataSourceDao.updateLastSync(dataSourceId(connection.deviceId), response.serverTime)
            SyncOutcome.Success(
                inserted = result.inserted,
                updated = result.updated,
                skippedUserEdited = result.skippedUserEdited,
                createdTypes = result.createdTypes
            )
        } catch (e: PcTrackerClient.PcHttpException) {
            if (e.statusCode == 401) SyncOutcome.Unauthorized else SyncOutcome.NotReachable
        } catch (_: IOException) {
            SyncOutcome.NotReachable
        }
    }

    suspend fun disconnect() {
        val connection = _connection.value
        prefs.clear()
        _connection.value = null
        if (connection != null) {
            dataSourceDao.deleteById(dataSourceId(connection.deviceId))
        }
    }

    /** Kopplungs-Code normalisieren: Nutzer tippen oft „482 913" mit Leerzeichen. */
    fun normalizeCode(raw: String): String = raw.filter { it.isDigit() }

    private fun dataSourceId(deviceId: String) = "pc_$deviceId"

    companion object {
        const val DATA_SOURCE_TYPE = "pc_tracker"
        private const val OVERLAP_MS = 3_600_000L
    }
}
