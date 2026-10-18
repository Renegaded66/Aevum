package com.d_drostes_apps.aevum.automation.pctracker

import android.content.Context
import android.util.Log
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.pc.PcTrackerClient
import com.d_drostes_apps.aevum.data.pc.PcTrackerPrefs
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M22: Holt Steuerbefehle des PCs ab und führt sie aus — auch wenn am Handy
 * gerade NICHTS aufzeichnet.
 *
 * Bisher gab es nur den Live-Relay: Während einer HANDY-Session meldet die
 * App ihren Zustand an `POST /api/live` und bekommt als Antwort
 * Pause/Fortsetzen/Stopp (PC-Tracker 1.4.0). Ein Start vom PC aus lief
 * deshalb komplett am Handy vorbei. Jetzt bietet der PC zusätzlich
 * `GET /api/commands` an; dieser Runner wird vom dauerhaft laufenden
 * [com.d_drostes_apps.aevum.automation.apptracking.AppTrackingService]
 * im Takt aufgerufen (Pull-Modell — der PC baut nie eine Verbindung zum
 * Handy auf).
 *
 * Bewusst still: PC aus / WLAN weg ist der Normalfall und darf nichts
 * stören (kein Fehler-Toast, nur Log).
 */
@Singleton
class PcRemoteCommandRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PcTrackerPrefs,
    private val client: PcTrackerClient,
    private val liveActivityManager: LiveActivityManager,
    private val activityTypeRepository: ActivityTypeRepository
) {

    /** Ergebnis eines Abrufs — nur für Log/Diagnose, nie für die UI-Pflicht. */
    sealed interface Outcome {
        data object NoConnection : Outcome
        data object NoCommand : Outcome
        data object Unreachable : Outcome
        data object AlreadyRunning : Outcome
        data class UnknownActivity(val activityKey: String) : Outcome
        data class Started(val activityName: String) : Outcome
    }

    suspend fun pollOnce(): Outcome {
        val connection = prefs.load() ?: return Outcome.NoConnection
        val remote = try {
            client.fetchCommand(connection.host, connection.port, connection.token)
        } catch (e: Exception) {
            // LAN weg, PC aus, Token veraltet — der nächste Takt versucht es
            // erneut. Kein Log-Spam: nur beim ersten Mal je Prozess wäre
            // schön, ist aber ohne Zustand hier nicht billig zu haben.
            return Outcome.Unreachable
        }
        val live = liveActivityManager.liveSession.value
        val type = remote?.activityKey?.let { key ->
            runCatching { activityTypeRepository.getById(key).first() }.getOrNull()
        }

        return when (val decision = PcRemoteCommandPolicy.decide(
            command = remote?.let { PcRemoteCommandPolicy.Command(it.command, it.activityKey) },
            typeExists = type != null,
            liveActivityTypeId = live?.activityTypeId,
            liveIsRunning = live?.isLive == true
        )) {
            PcRemoteCommandPolicy.Decision.Ignore -> Outcome.NoCommand
            is PcRemoteCommandPolicy.Decision.UnknownActivity ->
                Outcome.UnknownActivity(decision.activityKey)
            PcRemoteCommandPolicy.Decision.AlreadyRunning -> Outcome.AlreadyRunning
            is PcRemoteCommandPolicy.Decision.Start -> {
                // Der Befehl ist Nutzerabsicht — er darf eine laufende
                // Auto-Session (App-Aufzeichnung/Digital) ablösen; start()
                // schneidet Überlappungen sauber weg.
                liveActivityManager.start(
                    activityTypeId = decision.activityTypeId,
                    sourceType = SOURCE_PC_REMOTE,
                    note = context.getString(R.string.pc_remote_start_note)
                )
                LiveActivityService.start(context)
                Log.i(TAG, "Startbefehl vom PC ausgeführt: ${type?.name ?: decision.activityTypeId}")
                Outcome.Started(type?.name.orEmpty())
            }
        }
    }

    companion object {
        /** Quell-Typ der Session — vom PC gestartet. */
        const val SOURCE_PC_REMOTE = "PC_REMOTE"

        private const val TAG = "PcRemoteCommand"
    }
}
