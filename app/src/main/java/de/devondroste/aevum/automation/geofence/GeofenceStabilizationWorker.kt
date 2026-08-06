package de.devondroste.aevum.automation.geofence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.devondroste.aevum.automation.geofence.GeofenceDebouncer.ConfirmationResult
import de.devondroste.aevum.data.repository.TriggerEventRepository
import kotlinx.coroutines.flow.first

/**
 * M11.2: Bestätigt einen pendenden Geofence-Übergang nach Ablauf der
 * Stabilisierungszeit. Wenn der Übergang immer noch pending ist
 * (nicht durch GPS-Flattern verworfen), wird er an den
 * GeofenceTransitionProcessor weitergegeben.
 *
 * Wird vom GeofenceBroadcastReceiver mit einer Verzögerung von
 * STABILIZATION_MS (2 Minuten) via WorkManager gequeued.
 *
 * M16.7: GPS-Spannungs-Filter ("Devon-Heuristik"). Vor der finalen
 * Bestätigung prüft der Worker, ob seit dem pending-Zeitpunkt für
 * *andere* Geofences Echo-Trigger im Konsolidierungs-Fenster
 * (90s) stattgefunden haben. Wenn ja, ist der pending-Trigger Teil
 * eines Bursts (mehrere Geofence-Ränder touchen GPS-Drift) und wird
 * auf anchorQuality="LOW" gesetzt — der Trigger landet weiterhin in
 * der DB fürs Debugging, aber die Travel-Rule-Engine filtert ihn raus.
 * Damit entstehen keine Phantom-Fahrten wie "Gym → Home 11–13 Uhr"
 * wenn der User in Wirklichkeit zu Hause war.
 */
class GeofenceStabilizationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GeofenceEntryPoint {
        fun debouncer(): GeofenceDebouncer
        fun processor(): GeofenceTransitionProcessor
        // M16.7: Für die Burst-Erkennung brauchen wir Zugriff auf die DB.
        fun triggerEventRepository(): TriggerEventRepository
    }

    override suspend fun doWork(): Result {
        val geofenceId = inputData.getString(KEY_GEOFENCE_ID) ?: return Result.failure()
        val transitionName = inputData.getString(KEY_TRANSITION) ?: return Result.failure()
        val occurredAt = inputData.getLong(KEY_OCCURRED_AT, System.currentTimeMillis())
        val transition = runCatching { GeofenceTransition.valueOf(transitionName) }.getOrNull()
            ?: return Result.failure()

        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, GeofenceEntryPoint::class.java
        )
        val debouncer = entryPoint.debouncer()
        val processor = entryPoint.processor()

        val result = debouncer.confirmPending(geofenceId, transition, System.currentTimeMillis())
        when (result) {
            ConfirmationResult.Confirmed -> {
                // Stabilisiert! Vor der Verarbeitung prüfen wir auf Burst.
                // M16.7: Wenn in dem engen Fenster [occurredAt - 90s, occurredAt + 5s]
                // für ANDERE Geofences Trigger eingetragen wurden, ist der
                // pending-Trigger sehr wahrscheinlich Teil eines GPS-Drift-Bursts
                // und nicht eine echte User-Bewegung. Wir markieren ihn deshalb
                // mit anchorQuality = "LOW" — die Travel-Rule-Engine filtert
                // LOW-Anchors bereits (TriggerPairCandidateRuleEngine.evaluate).
                val isBurst = checkForBurst(
                    entryPoint.triggerEventRepository(),
                    debouncer,
                    geofenceId,
                    occurredAt
                )
                if (isBurst) {
                    android.util.Log.d(
                        "StabilizationWorker",
                        "Burst erkannt für $geofenceId $transition @ $occurredAt — anchorQuality=LOW"
                    )
                    // Wir rufen processTransition trotzdem auf — der Processor
                    // entscheidet anhand der Anchor-Quality, ob er den Trigger
                    // weiterverarbeitet. Er setzt anchorQuality=LOW und persistiert
                    // trotzdem (fürs Debugging), aber die Rule-Engine filtert ihn.
                    processor.processTransition(
                        geofenceId = geofenceId,
                        transition = transition,
                        occurredAt = occurredAt,
                        // M16.7: Übergeben anchorQualityOverride, damit der
                        // Processor den Trigger auf "LOW" zwingt — auch wenn
                        // der SleepShield ihn nicht auf LOW setzen würde.
                        anchorQualityOverride = "LOW"
                    )
                } else {
                    // Stabilisiert! Trigger verarbeiten.
                    processor.processTransition(
                        geofenceId = geofenceId,
                        transition = transition,
                        occurredAt = occurredAt
                    )
                }
            }
            ConfirmationResult.Cancelled -> {
                // GPS-Flattern hat den pendenten Übergang verworfen.
            }
            ConfirmationResult.AlreadyEmitted -> {
                // Wurde bereits bestätigt (z.B. durch ein früheres Event).
            }
        }
        return Result.success()
    }

    /**
     * M16.7: Prüft, ob seit dem pending-Zeitpunkt in einem engen Fenster
     * Echo-Trigger für andere Geofences stattgefunden haben — entweder im
     * Debouncer-Speicher (pending-Trigger, noch nicht persistiert) oder
     * bereits in der DB persistiert.
     *
     * Logik:
     *  - Wir laden alle persistierten Trigger im ±95s-Fenster um [occurredAt].
     *  - Wir fragen den Debouncer nach aktuell-pending-Triggern.
     *  - Wir suchen Trigger (aus beiden Quellen), die
     *      (a) NICHT für [ownGeofenceId] sind (Echo-Schutz) UND
     *      (b) einen anderen Geofence-Identifier haben (anderes Geofence).
     *  - Wenn wir mindestens einen finden, ist es ein Burst.
     *
     * Diese Schwelle ist konservativ (1 reicht) — das Fenster ist so eng,
     * dass ein einzelner Trigger für ein anderes Geofence in <95s praktisch
     * nur durch GPS-Drift entstehen kann.
     */
    private suspend fun checkForBurst(
        repo: TriggerEventRepository,
        debouncer: GeofenceDebouncer,
        ownGeofenceId: String,
        occurredAt: Long
    ): Boolean {
        val windowStart = occurredAt - BURST_WINDOW_MS
        val windowEnd = occurredAt + 5_000L
        val candidates = repo.getByDateRange(windowStart, windowEnd).first()

        // (a) Pending-Trigger im Debouncer — diese sind noch nicht in der DB
        // und würden sonst durch die reine DB-Abfrage übersehen. Beispiel:
        // 11:00:00 Gym-EXIT pending, 11:00:05 Home-EXIT pending, 11:00:30
        // Gym-Stabilization läuft → DB hat noch keinen Eintrag für Home-EXIT,
        // aber der Debouncer kennt ihn.
        val pendingGeofenceIds = debouncer.currentlyPendingGeofenceIds()

        // (b) DB-Trigger im Fenster für andere Geofences
        val burstFromDb = candidates.any { trigger ->
            trigger.geofenceId != null &&
                trigger.geofenceId != ownGeofenceId &&
                // Nicht der eigene Trigger, den wir gerade bestätigen
                !(trigger.occurredAt == occurredAt && trigger.geofenceId == ownGeofenceId)
        }
        // (c) Pending-Trigger für andere Geofences
        val burstFromPending = pendingGeofenceIds.any { it != ownGeofenceId }
        return burstFromDb || burstFromPending
    }

    companion object {
        const val KEY_GEOFENCE_ID = "geofence_id"
        const val KEY_TRANSITION = "transition"
        const val KEY_OCCURRED_AT = "occurred_at"

        const val WORK_PREFIX = "aevum.geofence.stabilize."

        // M16.7: Burst-Detection-Fenster. 90s ist derselbe Wert wie
        // GeofenceDebouncer.CONSOLIDATION_WINDOW_MS — damit sind Burst-
        // Detection im Receiver und im Stabilization-Worker konsistent.
        const val BURST_WINDOW_MS = 90_000L
    }
}